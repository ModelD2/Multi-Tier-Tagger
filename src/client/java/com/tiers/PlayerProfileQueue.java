package com.tiers;

import com.tiers.profile.PlayerProfile;
import com.tiers.profile.Status;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class PlayerProfileQueue {
    // TierBridge waits for several providers, unlike the original mod's quick identity
    // lookup. Keep more profiles moving at once, while provider requests remain bounded.
    private static final int MAX_CONCURRENT_LOOKUPS = 10;
    private static final ConcurrentLinkedDeque<PlayerProfile> queue = new ConcurrentLinkedDeque<>();
    private static final Set<PlayerProfile> activeProfiles = ConcurrentHashMap.newKeySet();
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "tiers-profile-queue");
        thread.setDaemon(true);
        return thread;
    });

    static {
        scheduler.scheduleAtFixedRate(PlayerProfileQueue::processNext, 0, 50, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(PlayerProfileQueue::recoverFailedRequests, 0, 1, TimeUnit.MINUTES);
    }

    public static void enqueue(PlayerProfile playerProfile) {
        queue.add(playerProfile);
    }

    private synchronized static void processNext() {
        // Requests complete asynchronously on Minecraft's client thread. Retire finished
        // entries before filling the available slots so a slow provider cannot stall the
        // entire online-player queue.
        activeProfiles.removeIf(profile -> profile.status != Status.SEARCHING);

        while (activeProfiles.size() < MAX_CONCURRENT_LOOKUPS) {
            PlayerProfile next = queue.poll();
            if (next == null)
                return;
            if (next.status != Status.SEARCHING)
                continue;

            activeProfiles.add(next);
            next.buildRequest();
        }
    }

    public synchronized static void putFirstInQueue(PlayerProfile playerProfile) {
        if (activeProfiles.contains(playerProfile))
            return;

        queue.remove(playerProfile);
        queue.addFirst(playerProfile);
    }

    public synchronized static void putLastInQueue(PlayerProfile playerProfile) {
        if (activeProfiles.contains(playerProfile))
            return;

        queue.remove(playerProfile);
        queue.addLast(playerProfile);
    }

    public synchronized static void changeToFirstInQueue(PlayerProfile playerProfile) {
        if (activeProfiles.contains(playerProfile)) return;

        if (queue.contains(playerProfile)) {
            queue.remove(playerProfile);
            queue.addFirst(playerProfile);
        }
    }

    public synchronized static void clearQueue() {
        queue.clear();
    }

    public synchronized static void removeFromQueue(PlayerProfile playerProfile) {
        queue.remove(playerProfile);
    }

    private static void recoverFailedRequests() {
        for (PlayerProfile playerProfile : PlayerProfile.failedPlayerProfiles) {
            PlayerProfile.failedPlayerProfiles.remove(playerProfile);
            playerProfile.prepareToRebuild();
            putLastInQueue(playerProfile);
        }
    }
}
