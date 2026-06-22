package com.zaheenmunshi.upstox;

import com.upstox.ApiClient;
import com.upstox.feeder.MarketDataStreamerV3;
import com.upstox.feeder.constants.Mode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Port of {@code streamer.py}: Upstox WebSocket V3 live market-data streamer.
 * Connects to the V3 feed, subscribes to the configured instruments, and writes
 * incoming updates to timestamped JSON files in {@code data/} every few seconds.
 *
 * Requires a valid {@code .access_token}. Only returns data during Indian market
 * hours (9:00 AM - 3:30 PM IST, Mon-Fri). Press Ctrl+C to stop.
 */
public final class Streamer {

    // ---- CONFIGURATION ----------------------------------------------------
    private static final Set<String> INSTRUMENT_KEYS = Set.of(
            "NSE_INDEX|Nifty 50",
            "NSE_INDEX|Nifty Bank");
    private static final Mode MODE = Mode.FULL;
    private static final int SAVE_INTERVAL_SECONDS = 5;
    // -----------------------------------------------------------------------

    private final Object bufferLock = new Object();
    private final List<Object> buffer = new ArrayList<>();
    private int totalMessages = 0;
    private int filesSaved = 0;
    private Path lastSavedFile = null;
    private final Path dataDir;

    private Streamer(Path dataDir) { this.dataDir = dataDir; }

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println(" Upstox WebSocket V3 - Live Market Data Streamer");
        System.out.println("=".repeat(60));

        Config cfg = Config.load();
        ApiClient client = UpstoxClients.authenticated(cfg.loadToken());
        new Streamer(cfg.dataDir()).run(client);
    }

    private void run(ApiClient client) {
        MarketDataStreamerV3 streamer = new MarketDataStreamerV3(client, INSTRUMENT_KEYS, MODE);

        streamer.setOnMarketUpdateListener(update -> {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("_received_at", Instant.now().toString());
            record.put("data", toSerializable(update));
            synchronized (bufferLock) {
                buffer.add(record);
                totalMessages++;
            }
        });

        System.out.println("[OPEN] Subscribing to " + INSTRUMENT_KEYS.size()
                + " instrument(s) in '" + MODE + "' mode:");
        for (String key : INSTRUMENT_KEYS) System.out.println("        - " + key);
        System.out.println("\nReceiving live data... (press Ctrl+C to stop)\n");

        ScheduledExecutorService saver = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "periodic-saver");
            t.setDaemon(true);
            return t;
        });
        saver.scheduleAtFixedRate(() -> {
            saveBuffer();
            System.out.printf("[STATUS] Total messages: %d | Files saved: %d | Last file: %s%n",
                    totalMessages, filesSaved, lastSavedFile == null ? "(none yet)" : lastSavedFile);
        }, SAVE_INTERVAL_SECONDS, SAVE_INTERVAL_SECONDS, TimeUnit.SECONDS);

        // Final flush + summary on Ctrl+C / shutdown.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            saver.shutdownNow();
            saveBuffer();
            try { streamer.disconnect(); } catch (Exception ignored) {}
            printSummary();
        }));

        System.out.println("Connecting...\n");
        streamer.connect();
        // Keep the main thread alive; the shutdown hook handles Ctrl+C.
        try {
            while (true) Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Write any accumulated messages to a new timestamped JSON file (skips if empty). */
    private void saveBuffer() {
        List<Object> messages;
        synchronized (bufferLock) {
            if (buffer.isEmpty()) return;
            messages = new ArrayList<>(buffer);
            buffer.clear();
        }
        try {
            Files.createDirectories(dataDir);
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path file = dataDir.resolve("market_data_" + ts + ".json");
            int counter = 1;
            while (Files.exists(file)) {
                file = dataDir.resolve("market_data_" + ts + "_" + counter + ".json");
                counter++;
            }
            Json.MAPPER.writeValue(file.toFile(), messages);
            filesSaved++;
            lastSavedFile = file;
            System.out.println("[SAVE] Wrote " + messages.size() + " message(s) -> " + file);
        } catch (Exception e) {
            System.out.println("[ERROR] could not save buffer: " + e.getMessage());
        }
    }

    private void printSummary() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println(" SESSION SUMMARY");
        System.out.println("=".repeat(60));
        System.out.println(" Total messages received: " + totalMessages);
        System.out.println(" Files saved:             " + filesSaved);
        System.out.println(" Last file:               " + (lastSavedFile == null ? "(none)" : lastSavedFile));
        System.out.println(" Data folder:             " + dataDir.toAbsolutePath());
        System.out.println("=".repeat(60));
    }

    private static Object toSerializable(Object obj) {
        if (obj == null) return null;
        try {
            return Json.MAPPER.convertValue(obj, Object.class);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }
}
