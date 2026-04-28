package com.example;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Cache<K, V> {

    private final Map<K, Entry<K, V>> map = new ConcurrentHashMap<>();

    public void put(K key, V value, long ttlMillis) {
        map.put(key, new Entry<>(key, value, System.currentTimeMillis() + ttlMillis));
    }

    public V get(K key) {
        Entry<K, V> entry = map.get(key);
        if (entry == null) return null;
        // BUG: condition is inverted - valid entries are rejected, expired ones would be returned
        if (System.currentTimeMillis() < entry.expiry) return null;
        if (entry.isExpired()) {
            map.remove(key);
            return null;
        }
        return entry.value;
    }

    public int size() {
        return map.size();
    }

    private static class Entry<K, V> {
        final K key;
        final V value;
        final long expiry;

        Entry(K key, V value, long expiry) {
            this.key = key;
            this.value = value;
            this.expiry = expiry;
        }

        boolean isExpired() {
            return System.currentTimeMillis() >= expiry;
        }
    }
}
