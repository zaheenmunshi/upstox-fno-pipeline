package com.zaheenmunshi.upstox;

import com.upstox.ApiClient;
import com.upstox.ApiException;
import com.upstox.api.GetOptionContractResponse;
import com.upstox.api.InstrumentData;
import io.swagger.client.api.HistoryV3Api;
import io.swagger.client.api.MarketHolidaysAndTimingsApi;
import io.swagger.client.api.OptionsApi;
import org.threeten.bp.OffsetDateTime;

import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Port of {@code market_snapshot.py}: fetch a comprehensive market snapshot from
 * the Upstox REST APIs into {@code data/snapshot_*.json}.
 *
 * Each section is independent and best-effort — a failure is captured in
 * {@code errors} rather than aborting the whole snapshot.
 */
public final class MarketSnapshot {

    // ---- CONFIG: edit underlyings / intervals here ------------------------
    private static final Map<String, String> UNDERLYINGS = new LinkedHashMap<>();
    static {
        UNDERLYINGS.put("NIFTY", "NSE_INDEX|Nifty 50");
        UNDERLYINGS.put("BANKNIFTY", "NSE_INDEX|Nifty Bank");
        UNDERLYINGS.put("SENSEX", "BSE_INDEX|SENSEX");
    }
    private static final String INTRADAY_UNIT = "minutes";
    private static final int INTRADAY_INTERVAL = 5;
    private static final String HIST_UNIT = "days";
    private static final int HIST_INTERVAL = 1;
    private static final int HIST_LOOKBACK_DAYS = 40;
    // -----------------------------------------------------------------------

    /** A fetch that may throw the SDK's checked {@link ApiException}. */
    @FunctionalInterface
    private interface Fetch { Object get() throws Exception; }

    private final Map<String, Object> sections = new LinkedHashMap<>();
    private final Map<String, Object> errors = new LinkedHashMap<>();

    private MarketSnapshot() {}

    public static void main(String[] args) throws Exception {
        new MarketSnapshot().run();
    }

    void run() throws Exception {
        Config cfg = Config.load();
        ApiClient client = UpstoxClients.authenticated(cfg.loadToken());

        String generatedAt = Instant.now().toString();

        System.out.println("== Market status ==");
        MarketHolidaysAndTimingsApi timings = new MarketHolidaysAndTimingsApi(client);
        grab("market_status_NSE", () -> timings.getMarketStatus("NSE"));

        String today = LocalDate.now().toString();
        String fromDate = LocalDate.now().minusDays(HIST_LOOKBACK_DAYS).toString();
        HistoryV3Api hist = new HistoryV3Api(client);

        System.out.println("== Candles ==");
        for (Map.Entry<String, String> e : UNDERLYINGS.entrySet()) {
            String name = e.getKey(), key = e.getValue();
            grab("intraday_" + name, () -> hist.getIntraDayCandleData(key, INTRADAY_UNIT, INTRADAY_INTERVAL));
            grab("historical_" + name, () -> hist.getHistoricalCandleData1(key, HIST_UNIT, HIST_INTERVAL, today, fromDate));
        }

        System.out.println("== Option chains ==");
        OptionsApi opt = new OptionsApi(client);
        for (Map.Entry<String, String> e : UNDERLYINGS.entrySet()) {
            String name = e.getKey(), key = e.getValue();
            try {
                GetOptionContractResponse contracts = opt.getOptionContracts(key, null);
                sections.put("option_contracts_" + name, toSerializable(contracts));

                List<String> expiries = new ArrayList<>(new TreeSet<>(extractExpiries(contracts)));
                sections.put("expiries_" + name, expiries);
                String exp = nearestExpiry(expiries, today);
                sections.put("nearest_expiry_" + name, exp);
                if (exp != null) {
                    grab("option_chain_" + name, () -> opt.getPutCallOptionChain(key, exp));
                } else {
                    errors.put("option_chain_" + name, "no expiry found in contracts");
                }
                System.out.println("  [ok]  option_contracts_" + name);
            } catch (Exception ex) {
                errors.put("option_contracts_" + name, ex.getClass().getSimpleName() + ": " + ex.getMessage());
                System.out.println("  [ERR] option_contracts_" + name + ": " + ex.getMessage());
            }
        }

        // News is sourced by the news-scanner agent (web search); the Upstox news
        // API is instrument-scoped, so it is intentionally omitted here.

        // Compact, Java-computed summary so agents read this instead of re-deriving
        // indicators / option-chain stats from the raw sections (token + stability win).
        // Emitted near the top of the file; raw sections are left untouched below it.
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("_generated_at", generatedAt);
        out.put("underlyings", UNDERLYINGS);
        out.put("digest", SnapshotDigest.build(UNDERLYINGS, sections));
        out.put("sections", sections);
        out.put("errors", errors);

        Files.createDirectories(cfg.dataDir());
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        var path = cfg.dataDir().resolve("snapshot_" + ts + ".json");
        Json.MAPPER.writeValue(path.toFile(), out);

        System.out.println("\nSaved snapshot -> " + path);
        System.out.println("Sections OK:    " + sections.keySet());
        if (!errors.isEmpty()) {
            System.out.println("Sections w/err: " + errors.keySet());
        }
    }

    /** Run a fetch, store its (serialized) result or capture the error — never abort. */
    private void grab(String name, Fetch fn) {
        try {
            sections.put(name, toSerializable(fn.get()));
            System.out.println("  [ok]  " + name);
        } catch (ApiException api) {
            errors.put(name, "ApiException " + api.getCode() + ": " + api.getResponseBody());
            System.out.println("  [ERR] " + name + ": HTTP " + api.getCode());
        } catch (Exception ex) {
            errors.put(name, ex.getClass().getSimpleName() + ": " + ex.getMessage());
            System.out.println("  [ERR] " + name + ": " + ex.getMessage());
        }
    }

    /** Convert an SDK response into a plain JSON-friendly tree; fall back to toString. */
    private static Object toSerializable(Object obj) {
        if (obj == null) return null;
        try {
            return Json.MAPPER.convertValue(obj, Object.class);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }

    private static List<String> extractExpiries(GetOptionContractResponse contracts) {
        List<String> out = new ArrayList<>();
        List<InstrumentData> data = contracts.getData();
        if (data == null) return out;
        for (InstrumentData inst : data) {
            OffsetDateTime exp = inst.getExpiry();
            if (exp != null) out.add(exp.toLocalDate().toString());
        }
        return out;
    }

    /**
     * Nearest tradeable expiry as bare YYYY-MM-DD. Prefers the nearest expiry
     * strictly after today; falls back to today's, then the earliest available.
     */
    static String nearestExpiry(List<String> sortedDates, String today) {
        List<String> dates = new ArrayList<>(new LinkedHashSet<>(sortedDates));
        for (String d : dates) if (d.compareTo(today) > 0) return d;   // first future
        for (String d : dates) if (d.compareTo(today) >= 0) return d;  // first same-or-future
        return dates.isEmpty() ? null : dates.get(0);                  // earliest
    }
}
