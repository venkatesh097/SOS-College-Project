package com.example.loracommunication.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tracks and de-duplicates packets based on (node, id, type).
 * Keeps an LRU cache of recently processed packet keys.
 */
public class PacketDeduplicator {
    private final Map<String, Boolean> seenKeys;

    public PacketDeduplicator(final int maxEntries) {
        this.seenKeys = Collections.synchronizedMap(new LinkedHashMap<String, Boolean>(maxEntries, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > maxEntries;
            }
        });
    }

    public PacketDeduplicator() {
        this(500);
    }

    /**
     * Checks if a packet has already been seen without adding it.
     */
    public synchronized boolean isDuplicate(String node, String id, String type) {
        if (node == null || id == null || type == null) return false;
        String key = buildKey(node, id, type);
        return seenKeys.containsKey(key);
    }

    /**
     * Checks and records the packet key.
     * Returns true if the packet is NEW (not seen before).
     * Returns false if it is a DUPLICATE.
     */
    public synchronized boolean trackIfNew(String node, String id, String type) {
        if (node == null || id == null || type == null) return true;
        String key = buildKey(node, id, type);
        if (seenKeys.containsKey(key)) {
            return false;
        }
        seenKeys.put(key, Boolean.TRUE);
        return true;
    }

    /**
     * Helper to track from a LoraPacket object.
     * Returns true if NEW, false if DUPLICATE.
     */
    public synchronized boolean trackIfNew(LoraPacket packet) {
        if (packet == null) return true;
        return trackIfNew(packet.getNode(), packet.getId(), packet.getType() != null ? packet.getType().name() : "");
    }

    public synchronized void clear() {
        seenKeys.clear();
    }

    public synchronized int size() {
        return seenKeys.size();
    }

    private String buildKey(String node, String id, String type) {
        return node + "|" + id + "|" + type;
    }
}
