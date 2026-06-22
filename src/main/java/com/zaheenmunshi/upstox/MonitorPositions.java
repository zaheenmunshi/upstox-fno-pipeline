package com.zaheenmunshi.upstox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.upstox.ApiClient;
import com.upstox.ApiException;
import com.upstox.api.GetMarketQuoteLastTradedPriceResponseV3;
import com.upstox.api.MarketQuoteSymbolLtpV3;
import io.swagger.client.api.MarketQuoteV3Api;

import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Port of {@code monitor_positions.py}: watch OPEN trades against stop / target /
 * time-stop using live LTP. MONITOR/ALERT ONLY — it never places or modifies
 * orders; you still execute manually in your broker.
 */
public final class MonitorPositions {

    private static final int POLL_SECONDS = 10;
    private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private MonitorPositions() {}

    public static void main(String[] args) throws Exception {
        Config cfg = Config.load();
        ApiClient client = UpstoxClients.authenticated(cfg.loadToken());
        MarketQuoteV3Api api = new MarketQuoteV3Api(client);

        List<Map<String, Object>> positions = loadPositions(cfg);
        List<String> keys = new ArrayList<>();
        for (Map<String, Object> p : positions) {
            Object k = p.get("instrument_key");
            if (k != null) keys.add(String.valueOf(k));
        }
        Set<Integer> done = new HashSet<>();

        System.out.println("Monitoring " + positions.size() + " position(s), polling every "
                + POLL_SECONDS + "s. Ctrl+C to stop.\n");

        try {
            while (done.size() < positions.size()) {
                OffsetDateTime now = OffsetDateTime.now(IST);
                Map<String, MarketQuoteSymbolLtpV3> raw;
                try {
                    GetMarketQuoteLastTradedPriceResponseV3 resp = api.getLtp(String.join(",", keys));
                    raw = resp.getData() != null ? resp.getData() : Map.of();
                } catch (ApiException e) {
                    System.out.println("[" + now.format(HMS) + "] LTP fetch error HTTP " + e.getCode() + "; retrying...");
                    Thread.sleep(POLL_SECONDS * 1000L);
                    continue;
                }

                for (int idx = 0; idx < positions.size(); idx++) {
                    if (done.contains(idx)) continue;
                    Map<String, Object> p = positions.get(idx);
                    String label = str(p.getOrDefault("label",
                            p.getOrDefault("instrument_key", "pos" + idx)));
                    String tok = p.get("instrument_key") == null ? null : str(p.get("instrument_key"));

                    Double ltp = null;
                    for (MarketQuoteSymbolLtpV3 v : raw.values()) {
                        if (tok != null && tok.equals(v.getInstrumentToken())) { ltp = v.getLastPrice(); break; }
                    }
                    if (ltp == null && tok != null && raw.containsKey(tok)) {
                        ltp = raw.get(tok).getLastPrice();
                    }

                    if (ltp == null) {
                        System.out.println("[" + now.format(HMS) + "] " + label + ": LTP unavailable (check instrument_key).");
                        continue;
                    }

                    if (timeStopPassed(p, now)) {
                        System.out.println("[" + now.format(HMS) + "] ** TIME-STOP ** " + label + ": exit now (LTP " + ltp + ").");
                        done.add(idx);
                        continue;
                    }

                    String alert = check(p, ltp);
                    if (alert != null) {
                        System.out.println("[" + now.format(HMS) + "] ** ALERT ** " + label + ": " + alert + " -> ACT NOW.");
                        done.add(idx);
                    } else {
                        System.out.println("[" + now.format(HMS) + "] " + label + ": LTP " + ltp
                                + " (stop " + p.get("stop") + ", target " + p.get("target") + ")");
                    }
                }

                if (done.size() < positions.size()) Thread.sleep(POLL_SECONDS * 1000L);
            }
            System.out.println("\nAll positions hit an exit condition. Monitor done.");
        } catch (InterruptedException e) {
            System.out.println("\nStopped by user.");
            Thread.currentThread().interrupt();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> loadPositions(Config cfg) throws Exception {
        var file = cfg.positionsFile();
        if (!Files.exists(file)) {
            System.out.println("ERROR: " + file + " not found.");
            System.out.println("Copy config/positions.example.json -> config/positions.json and fill in your trades.");
            System.exit(1);
        }
        List<Map<String, Object>> positions =
                Json.MAPPER.readValue(file.toFile(), new TypeReference<List<Map<String, Object>>>() {});
        if (positions == null || positions.isEmpty()) {
            System.out.println("ERROR: " + file + " must be a non-empty JSON list.");
            System.exit(1);
        }
        return positions;
    }

    /** Alert string if stop/target hit, else null. */
    private static String check(Map<String, Object> p, double ltp) {
        String side = str(p.getOrDefault("side", "LONG")).toUpperCase();
        Double stop = dbl(p.get("stop"));
        Double target = dbl(p.get("target"));
        if (side.equals("LONG")) {
            if (stop != null && ltp <= stop) return "STOP hit (LTP " + ltp + " <= stop " + stop + ")";
            if (target != null && ltp >= target) return "TARGET hit (LTP " + ltp + " >= target " + target + ")";
        } else {
            if (stop != null && ltp >= stop) return "STOP hit (LTP " + ltp + " >= stop " + stop + ")";
            if (target != null && ltp <= target) return "TARGET hit (LTP " + ltp + " <= target " + target + ")";
        }
        return null;
    }

    private static boolean timeStopPassed(Map<String, Object> p, OffsetDateTime nowIst) {
        Object ts = p.get("time_stop");
        if (ts == null || str(ts).isBlank()) return false;
        try {
            String[] parts = str(ts).split(":");
            int hh = Integer.parseInt(parts[0]), mm = Integer.parseInt(parts[1]);
            return nowIst.getHour() > hh || (nowIst.getHour() == hh && nowIst.getMinute() >= mm);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static Double dbl(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (NumberFormatException e) { return null; }
    }
}
