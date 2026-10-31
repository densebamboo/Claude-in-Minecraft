package com.claudemod.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerActivityTracker {
    private static final int MAX_EVENTS = 100;
    /**
     * How many recent events to scan back when looking for a merge candidate.
     * This lets PickedUp(dirt) x10 accumulate even when Mined(dirt) events
     * interleave at the head of the list.
     */
    private static final int MERGE_SCAN_DEPTH = 12;

    private static final Map<UUID, List<ActivityEvent>> LOGS = new ConcurrentHashMap<>();

    private PlayerActivityTracker() {}

    public static void log(UUID playerId, ActivityEvent event) {
        List<ActivityEvent> list = LOGS.computeIfAbsent(playerId, k -> new ArrayList<>(MAX_EVENTS + 1));
        synchronized (list) {
            String newKey = event.mergeKey();
            if (newKey != null) {
                int limit = Math.min(list.size(), MERGE_SCAN_DEPTH);
                for (int i = 0; i < limit; i++) {
                    ActivityEvent candidate = list.get(i);
                    if (newKey.equals(candidate.mergeKey())) {
                        long gap = event.timestamp() - candidate.timestamp();
                        if (gap >= 0 && gap < event.mergeWindowMs()) {
                            // Merge in-place: update the existing slot and move it to the front
                            ActivityEvent merged = candidate.mergeWith(event);
                            list.remove(i);
                            list.add(0, merged);
                            return;
                        }
                        break; // Found a matching key but outside the time window — don't scan further
                    }
                }
            }
            list.add(0, event);
            while (list.size() > MAX_EVENTS) list.remove(list.size() - 1);
        }
    }

    public static List<ActivityEvent> recent(UUID playerId) {
        List<ActivityEvent> list = LOGS.get(playerId);
        if (list == null) return List.of();
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }
}
