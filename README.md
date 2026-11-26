# TierBridge Tier Tagger 26.2

Client-side Fabric mod based on PvPTiers Tiers. Player lookups run directly from Minecraft to the public MCTiers, PvPTiers, PvPHQ, MCPVP, and SAPVP APIs. The mod has no local API server or bundled rankings website.

The game client resolves player names/UUIDs, queries the providers asynchronously, caches profile results, and combines their rankings. MCTiers and PvPTiers profile routes are tried by username first and UUID as a fallback. PvPHQ and SAPVP use their public player search routes, while MCPVP searches its overall tier data and falls back to kit queries if needed.

MCTiers/PvPTiers region and ranking metadata populate the profile header. Region conflicts follow SA, AU, ME, AS, AF, EU, NA. Retired rankings are retained; active rankings win an equal-tier tie, while weaker active tiers do not replace stronger retired tiers.
