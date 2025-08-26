package com.reputul.backend.services.imports;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class UploadCache {

    private final Map<String, CachedUpload> cache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    private static final long TTL_MINUTES = 30; // 30 minutes TTL

    public static class CachedUpload {
        private final CsvParser.ParsedCsvData parsedData;
        private final long timestamp;
        private final String originalFilename;

        public CachedUpload(CsvParser.ParsedCsvData parsedData, String originalFilename) {
            this.parsedData = parsedData;
            this.originalFilename = originalFilename;
            this.timestamp = System.currentTimeMillis();
        }

        public CsvParser.ParsedCsvData getParsedData() { return parsedData; }
        public long getTimestamp() { return timestamp; }
        public String getOriginalFilename() { return originalFilename; }

        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > TimeUnit.MINUTES.toMillis(TTL_MINUTES);
        }
    }

    public UploadCache() {
        // Clean up expired entries every 15 minutes
        scheduler.scheduleAtFixedRate(this::cleanupExpired, 15, 15, TimeUnit.MINUTES);
    }

    /**
     * Store parsed CSV data and return upload ID
     */
    public String store(CsvParser.ParsedCsvData parsedData, String originalFilename) {
        String uploadId = UUID.randomUUID().toString();
        cache.put(uploadId, new CachedUpload(parsedData, originalFilename));
        return uploadId;
    }

    /**
     * Retrieve cached upload data
     */
    public CachedUpload get(String uploadId) {
        CachedUpload cached = cache.get(uploadId);
        if (cached != null && cached.isExpired()) {
            cache.remove(uploadId);
            return null;
        }
        return cached;
    }

    /**
     * Remove upload from cache
     */
    public void remove(String uploadId) {
        cache.remove(uploadId);
    }

    /**
     * Clean up expired entries
     */
    private void cleanupExpired() {
        cache.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    /**
     * Get cache size (for monitoring)
     */
    public int size() {
        return cache.size();
    }
}