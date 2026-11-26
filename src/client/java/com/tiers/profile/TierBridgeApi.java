package com.tiers.profile;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tiers.TiersClient;
import com.tiers.profile.types.MCTiersProfile;
import com.tiers.profile.types.PvPTiersProfile;
import com.tiers.profile.types.SubtiersProfile;
import net.minecraft.client.Minecraft;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicInteger;

import static com.tiers.TiersClient.httpClient;
import static com.tiers.TiersClient.userAgent;

/** Queries the public tier APIs directly from the Minecraft client. */
public final class TierBridgeApi {
    private static final String[] OTHER_PROVIDERS = {"pvphq", "mcpvp", "sapvp"};
    private static final String MCTIERS = "https://mctiers.net/api/v2";
    private static final String PVPTIERS = "https://pvptiers.com/api";
    private static final String PVPHQ = "https://pvphq.com/api/v1/players/by-name/";
    private static final String MCPVP = "https://www.mcpvp.com/tiers/data";
    private static final String SAPVP = "https://www.sapvp.com/api/players/search/";
    private static final List<String> MCPVP_KITS = List.of("sword", "pot", "uhc", "smp", "crystal", "shield", "mace",
            "netherite-pot", "diamond-smp", "early-game", "late-game", "end-game", "creeper", "cart", "bow", "spear");
    private static final ConcurrentHashMap<String, CacheEntry> PROFILE_CACHE = new ConcurrentHashMap<>();
    private static final long CACHE_MILLIS = TimeUnit.MINUTES.toMillis(2);
    private static final ConcurrentHashMap<String, RequestGate> PROVIDER_LIMITS = new ConcurrentHashMap<>();
    private static final ExecutorService PROVIDER_LIMITER_EXECUTOR = Executors.newFixedThreadPool(20, runnable -> {
        Thread thread = new Thread(runnable, "tiers-provider-request-limiter");
        thread.setDaemon(true);
        return thread;
    });
    private static final RequestGate MCPVP_REQUEST_GATE = new RequestGate(6, TimeUnit.MILLISECONDS.toNanos(200));
    private static final ExecutorService MCPVP_LIMITER_EXECUTOR = Executors.newFixedThreadPool(8, runnable -> {
        Thread thread = new Thread(runnable, "tiers-mcpvp-request-limiter");
        thread.setDaemon(true);
        return thread;
    });

    private TierBridgeApi() {}

    public static void lookupName(String name, java.util.function.Consumer<JsonObject> success,
                                  java.util.function.Consumer<Throwable> failure) {
        if (name.matches("(?i)^[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}$")) {
            lookupUuid(name, success, failure);
            return;
        }
        lookup(name, "", success, failure);
    }

    public static void lookupUuid(String uuid, java.util.function.Consumer<JsonObject> success,
                                  java.util.function.Consumer<Throwable> failure) {
        String compact = uuid.replace("-", "").toLowerCase();
        if (!compact.matches("[0-9a-f]{32}")) {
            Minecraft.getInstance().execute(() -> failure.accept(new IllegalArgumentException("Invalid Minecraft UUID")));
            return;
        }
        lookup("", compact, success, failure);
    }

    private static void lookup(String requestedName, String requestedUuid,
                               java.util.function.Consumer<JsonObject> success,
                               java.util.function.Consumer<Throwable> failure) {
        String cacheKey = !requestedUuid.isEmpty() ? requestedUuid : requestedName.toLowerCase();
        CacheEntry cached = PROFILE_CACHE.get(cacheKey);
        if (cached != null && cached.expiresAt > System.currentTimeMillis()) {
            Minecraft.getInstance().execute(() -> success.accept(cached.profile.deepCopy()));
            return;
        }

        CompletableFuture<String> nameFuture = requestedName.isEmpty()
                ? resolveName(requestedUuid).exceptionally(ignored -> "")
                : CompletableFuture.completedFuture(requestedName);
        CompletableFuture<String> uuidFuture = requestedUuid.isEmpty()
                ? resolveUuid(requestedName).exceptionally(ignored -> "")
                : CompletableFuture.completedFuture(requestedUuid);

        List<CompletableFuture<ProviderData>> providers = List.of(
                byNameThenUuid("mctiers", requestedName, uuidFuture),
                byNameThenUuid("pvptiers", requestedName, uuidFuture),
                nameFuture.thenCompose(name -> name.isEmpty()
                ? CompletableFuture.completedFuture(ProviderData.empty("pvphq"))
                : fetchProvider("pvphq", PVPHQ + encode(name))),
                nameFuture.thenCompose(name -> searchMcpvp(name, requestedUuid)),
                nicknameThenUuid(requestedName, uuidFuture));

        ProviderData[] current = {
                ProviderData.empty("mctiers"), ProviderData.empty("pvptiers"), ProviderData.empty("pvphq"),
                ProviderData.empty("mcpvp"), ProviderData.empty("sapvp")
        };
        Object updateLock = new Object();
        Runnable emitPartial = () -> {
            synchronized (updateLock) {
                try {
                    List<ProviderData> snapshot = List.of(current.clone());
                    JsonObject profile = combine(nameFuture.getNow(""), requestedUuid, uuidFuture.getNow(""), snapshot);
                    Minecraft.getInstance().execute(() -> success.accept(profile));
                } catch (RuntimeException exception) {
                    TiersClient.LOGGER.debug("Could not publish partial TierBridge profile", exception);
                }
            }
        };

        // Publish the known identity immediately. Later callbacks replace it with the
        // best data received so far, so a slow provider never blocks the other tiers.
        emitPartial.run();
        nameFuture.whenComplete((name, error) -> emitPartial.run());
        uuidFuture.whenComplete((uuid, error) -> emitPartial.run());
        for (int i = 0; i < providers.size(); i++) {
            final int index = i;
            providers.get(i).whenComplete((provider, error) -> {
                if (error == null && provider != null) {
                    synchronized (updateLock) {
                        current[index] = provider;
                    }
                }
                emitPartial.run();
            });
        }

        CompletableFuture.allOf(providers.toArray(CompletableFuture[]::new)).orTimeout(30, TimeUnit.SECONDS).whenComplete((ignored, error) -> {
            if (error != null) {
                TiersClient.LOGGER.debug("TierBridge provider lookup did not finish cleanly", error);
                return;
            }
            try {
                JsonObject profile = combine(nameFuture.join(), requestedUuid, uuidFuture.join(), List.of(current.clone()));
                long cacheTime = object(profile, "rankings").size() > 0 ? CACHE_MILLIS : TimeUnit.SECONDS.toMillis(15);
                PROFILE_CACHE.put(cacheKey, new CacheEntry(profile.deepCopy(), System.currentTimeMillis() + cacheTime));
            } catch (RuntimeException exception) {
                TiersClient.LOGGER.debug("Could not cache TierBridge profile", exception);
            }
        });
    }

    private static CompletableFuture<ProviderData> byNameThenUuid(String source, String name, CompletableFuture<String> uuidFuture) {
        if (name.isEmpty()) return uuidFuture.thenCompose(uuid -> uuid.isEmpty()
                ? CompletableFuture.completedFuture(ProviderData.empty(source)) : fetchProvider(source, uuidUrl(source, uuid)));
        return fetchProvider(source, nameUrl(source, name)).thenCompose(result -> {
            if (!result.rankings.isEmpty()) return CompletableFuture.completedFuture(result);
            return uuidFuture.thenCompose(uuid -> uuid.isEmpty() ? CompletableFuture.completedFuture(result)
                    : fetchProvider(source, uuidUrl(source, uuid)).thenApply(fallback -> fallback.rankings.isEmpty() ? result : fallback));
        });
    }

    private static CompletableFuture<ProviderData> nicknameThenUuid(String name, CompletableFuture<String> uuidFuture) {
        if (name.isEmpty()) return uuidFuture.thenCompose(uuid -> uuid.isEmpty()
                ? CompletableFuture.completedFuture(ProviderData.empty("sapvp"))
                : fetchProvider("sapvp", SAPVP + "uuid?id=" + encode(dashed(uuid))));
        return fetchProvider("sapvp", SAPVP + "nickname?name=" + encode(name)).thenCompose(result -> {
            if (!result.rankings.isEmpty()) return CompletableFuture.completedFuture(result);
            return uuidFuture.thenCompose(uuid -> uuid.isEmpty() ? CompletableFuture.completedFuture(result)
                    : fetchProvider("sapvp", SAPVP + "uuid?id=" + encode(dashed(uuid)))
                    .thenApply(fallback -> fallback.rankings.isEmpty() ? result : fallback));
        });
    }

    private static CompletableFuture<ProviderData> searchMcpvp(String name, String uuid) {
        String query = name.isEmpty() ? uuid : name;
        if (query.isEmpty()) return CompletableFuture.completedFuture(ProviderData.empty("mcpvp"));
        // The overall view contains a player's contributing kit ranks, so one filtered
        // request can replace the old 16-request sweep across every individual kit.
        String overallUrl = MCPVP + "?view=overall&q=" + encode(query) + "&limit=100&offset=0";
        return getMcpvpJson(overallUrl)
                .thenCompose(payload -> mcpvpHasPlayerShape(payload)
                        ? CompletableFuture.completedFuture(mcpvpPlayer(payload, name, uuid))
                        : searchMcpvpByKit(name, uuid))
                .exceptionallyCompose(error -> {
                    TiersClient.LOGGER.debug("MCPVP overall search failed for {}: {}", query, error.toString());
                    return searchMcpvpByKit(name, uuid);
                });
    }

    private static CompletableFuture<ProviderData> searchMcpvpByKit(String name, String uuid) {
        String query = name.isEmpty() ? uuid : name;
        List<CompletableFuture<JsonElement>> requests = new ArrayList<>();
        CompletableFuture<ProviderData> firstMatch = new CompletableFuture<>();
        AtomicInteger completed = new AtomicInteger();
        for (String kit : MCPVP_KITS) {
            String url = MCPVP + "?view=kit&kit=" + encode(kit) + "&q=" + encode(query) + "&limit=100&offset=0";
            CompletableFuture<JsonElement> request = getMcpvpJson(url).whenComplete((payload, error) -> {
                if (error == null) {
                    ProviderData candidate = mcpvpPlayer(payload, name, uuid);
                    if (!candidate.rankings.isEmpty()) firstMatch.complete(candidate);
                }
                if (completed.incrementAndGet() == MCPVP_KITS.size())
                    firstMatch.complete(ProviderData.empty("mcpvp"));
            }).exceptionally(ignored -> null);
            requests.add(request);
        }
        return firstMatch.thenApply(match -> {
            for (CompletableFuture<JsonElement> request : requests) if (!request.isDone()) request.cancel(true);
            return match;
        });
    }

    private static boolean mcpvpHasPlayerShape(JsonElement payload) {
        if (payload == null || !payload.isJsonObject()) return false;
        JsonObject root = payload.getAsJsonObject();
        if (root.has("data") && root.get("data").isJsonObject()) root = root.getAsJsonObject("data");
        return root.has("players") || root.has("kitTiers") || root.has("name") || root.has("username") || root.has("uuid");
    }

    private static ProviderData mcpvpPlayer(JsonElement payload, String name, String uuid) {
        if (!payload.isJsonObject()) return ProviderData.empty("mcpvp");
        JsonObject root = payload.getAsJsonObject();
        if (root.has("data") && root.get("data").isJsonObject()) root = root.getAsJsonObject("data");
        List<JsonObject> candidates = new ArrayList<>();
        JsonElement players = root.get("players");
        if (players != null && players.isJsonArray())
            for (JsonElement item : players.getAsJsonArray()) if (item.isJsonObject()) candidates.add(item.getAsJsonObject());
        JsonObject groups = object(root, "kitTiers");
        for (Map.Entry<String, JsonElement> group : groups.entrySet())
            if (group.getValue().isJsonArray()) for (JsonElement item : group.getValue().getAsJsonArray())
                if (item.isJsonObject()) candidates.add(item.getAsJsonObject());
        if (root.has("name") || root.has("username")) candidates.add(root);
        String targetUuid = uuid.replace("-", "").toLowerCase();
        for (JsonObject candidate : candidates) {
            String candidateUuid = string(candidate, "uuid", "").replace("-", "").toLowerCase();
            String candidateName = string(candidate, "name", string(candidate, "username", ""));
            if ((!targetUuid.isEmpty() && targetUuid.equals(candidateUuid)) ||
                    (!name.isEmpty() && name.equalsIgnoreCase(candidateName))) {
                ProviderData parsed = parseProvider("mcpvp", candidate);
                return parsed;
            }
        }
        return ProviderData.empty("mcpvp");
    }

    private static CompletableFuture<ProviderData> fetchProvider(String provider, String url) {
        return getLimitedJson(url).thenApply(payload -> parseProvider(provider, payload))
                .exceptionally(error -> {
                    TiersClient.LOGGER.debug("{} lookup failed: {}", provider, error.toString());
                    return ProviderData.empty(provider);
                });
    }

    private static CompletableFuture<JsonElement> getJson(String url) {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
                .header("User-Agent", userAgent).header("Accept", "application/json")
                .timeout(Duration.ofSeconds(5)).GET().build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode() + " from " + url);
            return JsonParser.parseString(response.body());
        });
    }

    private static CompletableFuture<JsonElement> getLimitedJson(String url) {
        String host = URI.create(url).getHost().toLowerCase();
        RequestGate gate = PROVIDER_LIMITS.computeIfAbsent(host,
                ignored -> new RequestGate(4, TimeUnit.MILLISECONDS.toNanos(200)));
        return sendGated(url, gate, PROVIDER_LIMITER_EXECUTOR);
    }

    private static CompletableFuture<JsonElement> getMcpvpJson(String url) {
        return sendGated(url, MCPVP_REQUEST_GATE, MCPVP_LIMITER_EXECUTOR);
    }

    private static CompletableFuture<JsonElement> sendGated(String url, RequestGate gate, ExecutorService executor) {
        return CompletableFuture.runAsync(gate::acquire, executor).thenCompose(ignored -> {
            try {
                return getJson(url).whenComplete((payload, error) -> gate.release());
            } catch (RuntimeException error) {
                gate.release();
                return CompletableFuture.failedFuture(error);
            }
        });
    }

    private static CompletableFuture<String> resolveUuid(String name) {
        return getLimitedJson("https://api.mojang.com/users/profiles/minecraft/" + encode(name)).thenApply(json -> {
            if (!json.isJsonObject() || !json.getAsJsonObject().has("id")) throw new IllegalStateException("Name not found");
            return json.getAsJsonObject().get("id").getAsString().toLowerCase();
        }).exceptionallyCompose(error -> getLimitedJson("https://api.minecraftservices.com/minecraft/profile/lookup/name/" + encode(name))
                .thenApply(json -> {
                    if (!json.isJsonObject() || !json.getAsJsonObject().has("id")) throw new IllegalStateException("Name not found");
                    return json.getAsJsonObject().get("id").getAsString().toLowerCase();
                }));
    }

    private static CompletableFuture<String> resolveName(String uuid) {
        return getLimitedJson("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid.replace("-", ""))
                .thenApply(json -> json.isJsonObject() ? string(json.getAsJsonObject(), "name", "") : "");
    }

    private static String nameUrl(String source, String name) {
        return source.equals("mctiers") ? MCTIERS + "/profile/by-name/" + encode(name)
                : PVPTIERS + "/search_profile/" + encode(name.toLowerCase());
    }

    private static String uuidUrl(String source, String uuid) {
        return source.equals("mctiers") ? MCTIERS + "/profile/" + encode(dashed(uuid))
                : PVPTIERS + "/profile/" + encode(uuid.replace("-", "").toLowerCase());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String dashed(String uuid) {
        String compact = uuid.replace("-", "");
        return compact.length() == 32 ? compact.substring(0, 8) + "-" + compact.substring(8, 12) + "-" + compact.substring(12, 16) + "-" + compact.substring(16, 20) + "-" + compact.substring(20) : uuid;
    }

    private static ProviderData parseProvider(String provider, JsonElement payload) {
        if (payload == null || !payload.isJsonObject()) return ProviderData.empty(provider);
        JsonObject profile = payload.getAsJsonObject();
        for (String wrapper : List.of("data", "player", "profile", "result")) {
            if (profile.has(wrapper) && profile.get(wrapper).isJsonObject()) {
                profile = profile.getAsJsonObject(wrapper);
                break;
            }
        }
        JsonObject metadata = new JsonObject();
        copyMetadata(profile, metadata, "region", "region", "regionCode", "region_code");
        copyMetadata(profile, metadata, "uuid", "uuid", "id", "raw_id");
        copyMetadata(profile, metadata, "points", "points", "score", "totalPoints", "total_points");
        copyMetadata(profile, metadata, "overall", "overall", "overallPosition", "overall_position", "overallRank", "overall_rank", "globalRank", "global_rank", "rank", "position", "placement");
        String name = string(profile, "name", string(profile, "username", string(profile, "nickname", "")));
        JsonObject raw = firstObject(profile, "rankings", "ranked", "tiers", "kitRanks", "contributingKits", "gamemodes", "kits");
        JsonObject rankings = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : raw.entrySet()) {
            JsonObject tier = tierRow(provider, entry.getValue());
            if (tier != null) mergeTier(rankings, sourceMode(entry.getKey()), tier);
        }
        if (raw.size() == 0) {
            JsonElement list = first(profile, "rankings", "ranked", "tiers", "contributingKits", "gamemodes", "kits");
            if (list != null && list.isJsonArray()) for (JsonElement item : list.getAsJsonArray()) {
                if (!item.isJsonObject()) continue;
                JsonObject row = item.getAsJsonObject();
                String mode = string(row, "gamemode", string(row, "gametype", string(row, "tierList", string(row, "mode", string(row, "kit", string(row, "key", string(row, "name", "")))))));
                JsonElement rank = row.has("rank") ? row.get("rank") : row;
                JsonObject tier = tierRow(provider, rank);
                if (!mode.isEmpty() && tier != null) mergeTier(rankings, sourceMode(mode), tier);
            }
        }
        return new ProviderData(provider, name, rankings, metadata);
    }

    private static JsonObject tierRow(String provider, JsonElement value) {
        String label = tierLabel(provider, value);
        if (label.isEmpty()) return null;
        JsonObject raw = value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
        JsonObject row = new JsonObject();
        row.addProperty("label", label);
        row.addProperty("tier", numberString(label.replaceAll("\\D", ""), 0));
        String displayLabel = label.replaceFirst("^R", "");
        row.addProperty("pos", displayLabel.startsWith("H") ? 0 : displayLabel.startsWith("M") ? 2 : 1);
        row.addProperty("retired", raw.has("retired") && raw.get("retired").getAsBoolean() || label.startsWith("R"));
        row.addProperty("source", provider);
        return row;
    }

    private static String tierLabel(String provider, JsonElement value) {
        if (value == null || value.isJsonNull()) return "";
        JsonObject object = value.isJsonObject() ? value.getAsJsonObject() : null;
        JsonElement raw = object == null ? value : first(object, "tier", "name", "code", "value", "rank", "display", "tierName");
        if (raw == null || raw.isJsonNull()) return "";
        String label;
        if (provider.equals("sapvp") && raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isNumber()) {
            int index = raw.getAsInt();
            if (index < 1 || index > 10) return "";
            label = (index % 2 == 0 ? "HT" : "LT") + (5 - ((index - 1) / 2));
        } else if (raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isNumber()) {
            int tier = raw.getAsInt();
            int pos = object == null ? 0 : number(object, "pos", number(object, "position", 0));
            label = (pos == 0 ? "HT" : "LT") + tier;
        } else {
            label = raw.getAsString().trim().toUpperCase().replace(" ", "").replace("HIGHTIER", "HT").replace("MIDTIER", "MT").replace("LOWTIER", "LT");
            if (label.matches("[1-6]")) {
                int pos = object == null ? 0 : number(object, "pos", number(object, "position", 0));
                label = (pos == 0 ? "HT" : "LT") + label;
            }
            if (!label.matches("R?(HT|MT|LT|ST)[1-6]")) return "";
        }
        if (object != null && object.has("retired") && object.get("retired").getAsBoolean() && !label.startsWith("R")) label = "R" + label;
        return label;
    }

    private static JsonObject combine(String requestedName, String requestedUuid, String resolvedUuid, List<ProviderData> providers) {
        JsonObject payload = new JsonObject();
        String name = requestedName;
        if (name.isEmpty()) for (ProviderData provider : providers) if (!provider.name.isEmpty()) { name = provider.name; break; }
        String uuid = !requestedUuid.isEmpty() ? requestedUuid : resolvedUuid;
        if (uuid.isEmpty()) for (ProviderData provider : providers) {
            uuid = string(provider.metadata, "uuid", "").replace("-", "");
            if (!uuid.isEmpty()) break;
        }
        if (uuid.isEmpty()) uuid = "00000000000000000000000000000000";
        payload.addProperty("name", name.isEmpty() ? uuid : name);
        payload.addProperty("uuid", dashed(uuid));
        JsonObject sources = new JsonObject();
        JsonObject merged = new JsonObject();
        JsonObject mcMetadata = new JsonObject();
        JsonObject pvpMetadata = new JsonObject();
        for (ProviderData provider : providers) {
            JsonObject rows = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : provider.rankings.entrySet()) {
                JsonObject tier = entry.getValue().getAsJsonObject().deepCopy();
                rows.add(entry.getKey(), tier);
                mergeTier(merged, canonicalMode(entry.getKey()), tier);
            }
            sources.add(provider.source, rows);
            if (provider.source.equals("mctiers")) mcMetadata = provider.metadata;
            if (provider.source.equals("pvptiers")) pvpMetadata = provider.metadata;
        }
        payload.add("sources", sources);
        payload.add("rankings", merged);
        String region = chooseRegion(string(mcMetadata, "region", ""), string(pvpMetadata, "region", ""));
        payload.addProperty("region", region);
        int mcPoints = number(mcMetadata, "points", 0), pvpPoints = number(pvpMetadata, "points", 0);
        int mcOverall = number(mcMetadata, "overall", 0), pvpOverall = number(pvpMetadata, "overall", 0);
        payload.addProperty("points", mcPoints > 0 ? mcPoints : pvpPoints);
        payload.addProperty("overall", mcOverall > 0 ? mcOverall : pvpOverall);
        return payload;
    }

    private static String chooseRegion(String first, String second) {
        List<String> order = List.of("SA", "AU", "ME", "AS", "AF", "EU", "NA");
        return List.of(first.toUpperCase(), second.toUpperCase()).stream().filter(order::contains)
                .min(Comparator.comparingInt(order::indexOf)).orElse("??");
    }

    private static void mergeTier(JsonObject target, String mode, JsonObject candidate) {
        if (mode.isEmpty()) return;
        JsonObject current = object(target, mode);
        if (current.size() == 0 || compareRankings(candidate, current) < 0) target.add(mode, candidate.deepCopy());
    }

    private static JsonObject firstObject(JsonObject parent, String... keys) {
        JsonElement element = first(parent, keys);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static JsonElement first(JsonObject parent, String... keys) {
        for (String key : keys) if (parent.has(key) && !parent.get(key).isJsonNull()) return parent.get(key);
        return null;
    }

    private static void copyMetadata(JsonObject from, JsonObject to, String dest, String... keys) {
        JsonElement value = first(from, keys);
        if (value == null) return;
        if (dest.equals("region") && value.isJsonObject()) value = first(value.getAsJsonObject(), "code", "name", "region");
        if (value != null) to.add(dest, value.deepCopy());
    }

    private static String canonicalMode(String mode) {
        String normalized = mode.toLowerCase().trim().replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "early_game", "earlygame" -> "uhc";
            case "shield" -> "axe";
            case "crystal" -> "vanilla";
            case "spear_mace" -> "spear";
            case "diamond_smp", "diasmp" -> "dia_smp";
            case "neth_pot", "nethpot", "netheritepot", "netherite_pot" -> "nethop";
            default -> normalized;
        };
    }

    private static String sourceMode(String mode) {
        String normalized = mode.toLowerCase().trim().replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "nethpot", "netheritepot", "netherite_pot" -> "neth_pot";
            default -> normalized;
        };
    }

    private record ProviderData(String source, String name, JsonObject rankings, JsonObject metadata) {
        static ProviderData empty(String source) { return new ProviderData(source, "", new JsonObject(), new JsonObject()); }
    }

    private static final class RequestGate {
        private final Semaphore concurrency;
        private final long intervalNanos;
        private long nextStartNanos;

        private RequestGate(int maxConcurrent, long intervalNanos) {
            this.concurrency = new Semaphore(maxConcurrent);
            this.intervalNanos = intervalNanos;
        }

        private void acquire() {
            concurrency.acquireUninterruptibly();
            long delay;
            synchronized (this) {
                long now = System.nanoTime();
                long start = Math.max(now, nextStartNanos);
                nextStartNanos = start + intervalNanos;
                delay = start - now;
            }
            if (delay > 0) LockSupport.parkNanos(delay);
        }

        private void release() {
            concurrency.release();
        }
    }

    private record CacheEntry(JsonObject profile, long expiresAt) {}

    public static void applyProfile(JsonObject payload, PlayerProfile player) {
        String returnedName = string(payload, "name", player.name);
        String returnedUuid = string(payload, "uuid", player.uuid).replace("-", "");
        if (returnedUuid.length() != 32) throw new IllegalStateException("TierBridge profile has no valid UUID");
        player.name = returnedName;
        player.uuid = returnedUuid;
        player.nameChanged = !player.inGameName.equalsIgnoreCase(returnedName);
        player.targetName = player.nameChanged ? player.inGameName : returnedName;

        JsonObject sources = object(payload, "sources");
        JsonObject otherRankings = new JsonObject();
        for (String sourceName : OTHER_PROVIDERS) {
            JsonObject source = object(sources, sourceName);
            for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
                String mode = canonicalMode(entry.getKey());
                JsonObject tier = entry.getValue().isJsonObject() ? entry.getValue().getAsJsonObject() : null;
                if (tier == null || !tier.has("label")) continue;
                JsonObject existing = object(otherRankings, mode);
                if (existing.size() == 0 || compareRankings(tier, existing) < 0)
                    otherRankings.add(mode, tier.deepCopy());
            }
        }

        player.profileMCTiers = new MCTiersProfile(makeProfile(payload, canonicalRankings(object(payload, "rankings")), true).toString());
        player.profilePvPTiers = new PvPTiersProfile(makeProfile(payload, object(sources, "pvptiers"), false).toString());
        player.profileSubtiers = new SubtiersProfile(makeProfile(payload, otherRankings, true).toString());
        player.profileMCTiers.setOnUpdate(player::updateAppendingText);
        player.profilePvPTiers.setOnUpdate(player::updateAppendingText);
        player.profileSubtiers.setOnUpdate(player::updateAppendingText);
        player.updateAppendingText();
        player.status = Status.READY;
        TiersClient.readyPlayerProfiles.put(player.targetName, player);
    }

    private static JsonObject makeProfile(JsonObject original, JsonObject rankingLabels, boolean canonicalizeModes) {
        JsonObject profile = new JsonObject();
        profile.addProperty("name", string(original, "name", "Unknown"));
        profile.addProperty("region", string(original, "region", "??"));
        profile.addProperty("points", number(original, "points", "0"));
        profile.addProperty("overall", number(original, "overall", "0"));
        JsonObject rankings = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : rankingLabels.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject raw = entry.getValue().getAsJsonObject();
            String label = string(raw, "label", "");
            int tier = number(raw, "tier", label.replaceAll("\\D", ""));
            String pos = string(raw, "pos", label.replaceFirst("^R", "").startsWith("H") ? "0" : "1");
            if (tier < 1 || tier > 5) continue;
            JsonObject normalized = new JsonObject();
            normalized.addProperty("tier", tier);
            normalized.addProperty("pos", pos);
            normalized.add("peak_tier", null);
            normalized.add("peak_pos", null);
            normalized.addProperty("attained", number(raw, "attained", "0"));
            normalized.addProperty("retired", raw.has("retired") ? raw.get("retired").getAsBoolean() : label.startsWith("R"));
            rankings.add(canonicalizeModes ? canonicalMode(entry.getKey()) : sourceMode(entry.getKey()), normalized);
        }
        profile.add("rankings", rankings);
        return profile;
    }

    private static JsonObject canonicalRankings(JsonObject source) {
        JsonObject result = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            String mode = canonicalMode(entry.getKey());
            JsonObject ranking = entry.getValue().isJsonObject() ? entry.getValue().getAsJsonObject() : null;
            if (ranking == null) continue;
            JsonObject existing = object(result, mode);
            if (existing.size() == 0 || compareRankings(ranking, existing) < 0) result.add(mode, ranking.deepCopy());
        }
        return result;
    }

    private static int compareRankings(JsonObject first, JsonObject second) {
        String a = string(first, "label", "");
        String b = string(second, "label", "");
        int tierComparison = compareLabels(a, b);
        if (tierComparison != 0) return tierComparison;
        boolean retiredA = first.has("retired") && first.get("retired").getAsBoolean();
        boolean retiredB = second.has("retired") && second.get("retired").getAsBoolean();
        return Boolean.compare(retiredA, retiredB);
    }

    private static int compareLabels(String first, String second) {
        int rankA = labelRank(first), rankB = labelRank(second);
        return Integer.compare(rankA, rankB);
    }

    private static int labelRank(String label) {
        String normalized = label.toUpperCase().replaceFirst("^R", "");
        int tier = numberString(normalized.replaceAll("\\D", ""), 99);
        int placement = normalized.startsWith("H") ? 0 : normalized.startsWith("M") ? 1 : normalized.startsWith("L") ? 2 : 3;
        return tier * 4 + placement;
    }

    private static JsonObject object(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) return new JsonObject();
        return parent.getAsJsonObject(key);
    }

    private static String string(JsonObject object, String key, String fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }

    private static int number(JsonObject object, String key, String fallback) {
        return numberString(string(object, key, fallback), 0);
    }

    private static int number(JsonObject object, String key, int fallback) {
        return numberString(string(object, key, Integer.toString(fallback)), fallback);
    }

    private static int numberString(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return fallback; }
    }
}
