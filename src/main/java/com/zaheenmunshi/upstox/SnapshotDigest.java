package com.zaheenmunshi.upstox;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes a compact, deterministic {@code digest} from the raw snapshot
 * {@code sections}. The point is token economy + stability: agents read this
 * small pre-computed summary instead of re-deriving indicators / option-chain
 * stats from the (large) raw JSON. The exact arithmetic lives here, in Java,
 * so it never drifts run-to-run and never costs LLM tokens.
 *
 * Raw {@code sections} are left untouched — anything the digest omits is still
 * available in full for a deep dive.
 *
 * Indicator conventions match {@link Backtest}: EMA k = 2/(period+1); ATR is a
 * simple rolling mean of True Range; RSI is Wilder-smoothed. All candles are
 * sorted oldest-first before computing.
 */
final class SnapshotDigest {

    /** One OHLC bar (volume/OI dropped — indices carry none). */
    private record Bar(String ts, double o, double h, double l, double c) {}

    /** One side (CE or PE) of a strike: the fields the digest reports. */
    private record Leg(double oi, double prevOi, double ltp, double iv, double delta) {}

    private SnapshotDigest() {}

    /** Build {@code {<underlying>: {…digest…}}}; underlyings with no usable data are skipped. */
    static Map<String, Object> build(Map<String, String> underlyings, Map<String, Object> sections) {
        Map<String, Object> out = new LinkedHashMap<>();
        String today = LocalDate.now().toString();
        for (String name : underlyings.keySet()) {
            Map<String, Object> d = new LinkedHashMap<>();
            List<Bar> intraday = bars(sections.get("intraday_" + name));
            List<Bar> daily = bars(sections.get("historical_" + name));
            Map<String, Object> options = optionDigest(sections.get("option_chain_" + name),
                    sections.get("nearest_expiry_" + name));

            if (intraday.isEmpty() && daily.isEmpty() && options.isEmpty()) continue;

            Double spot = options.containsKey("spot") ? (Double) options.get("spot")
                    : (!intraday.isEmpty() ? intraday.get(intraday.size() - 1).c : null);
            if (spot != null) d.put("spot", spot);

            if (!intraday.isEmpty()) {
                Bar last = intraday.get(intraday.size() - 1);
                double hi = Double.MIN_VALUE, lo = Double.MAX_VALUE;
                for (Bar b : intraday) { hi = Math.max(hi, b.h); lo = Math.min(lo, b.l); }
                Map<String, Object> in = new LinkedHashMap<>();
                in.put("as_of", last.ts);
                in.put("open", r(intraday.get(0).o, 2));
                in.put("high", r(hi, 2));
                in.put("low", r(lo, 2));
                in.put("last", r(last.c, 2));
                putIndicators(in, intraday);
                in.put("candle_count", intraday.size());
                d.put("intraday", in);
            }

            if (!daily.isEmpty()) {
                Map<String, Object> dl = new LinkedHashMap<>();
                Double prevClose = prevSessionClose(daily, today);
                if (prevClose != null) dl.put("prev_close", r(prevClose, 2));
                putIndicators(dl, daily);
                List<Object> last5 = new ArrayList<>();
                for (int i = Math.max(0, daily.size() - 5); i < daily.size(); i++) last5.add(r(daily.get(i).c, 2));
                dl.put("last5_closes", last5);
                d.put("daily", dl);
                d.put("trend_hint", trendHint(daily) + " (heuristic — agent makes the final regime call)");
            }

            if (!options.isEmpty()) d.put("options", options);
            out.put(name, d);
        }
        return out;
    }

    // ---- candles -----------------------------------------------------------

    private static List<Bar> bars(Object section) {
        List<Bar> out = new ArrayList<>();
        Map<String, Object> m = asMap(section);
        if (m == null) return out;
        Map<String, Object> data = asMap(m.get("data"));
        if (data == null) return out;
        List<Object> rows = asList(data.get("candles"));
        if (rows == null) return out;
        for (Object row : rows) {
            List<Object> c = asList(row);
            if (c == null || c.size() < 5) continue;
            Double o = dbl(c.get(1)), h = dbl(c.get(2)), l = dbl(c.get(3)), cl = dbl(c.get(4));
            if (o == null || h == null || l == null || cl == null) continue;
            out.add(new Bar(String.valueOf(c.get(0)), o, h, l, cl));
        }
        out.sort((a, b) -> a.ts.compareTo(b.ts)); // oldest first
        return out;
    }

    private static void putIndicators(Map<String, Object> target, List<Bar> bars) {
        double[] closes = new double[bars.size()];
        for (int i = 0; i < bars.size(); i++) closes[i] = bars.get(i).c;
        Double e9 = lastEma(closes, 9), e20 = lastEma(closes, 20), e50 = lastEma(closes, 50);
        if (e9 != null) target.put("ema9", r(e9, 2));
        if (e20 != null) target.put("ema20", r(e20, 2));
        if (e50 != null) target.put("ema50", r(e50, 2));
        Double rsi = lastRsi(closes, 14);
        if (rsi != null) target.put("rsi14", r(rsi, 1));
        Double atr = lastAtr(bars, 14);
        if (atr != null) {
            target.put("atr14", r(atr, 2));
            double last = closes[closes.length - 1];
            if (last != 0) target.put("atr_pct", r(100.0 * atr / last, 2));
        }
    }

    private static Double lastEma(double[] v, int period) {
        if (v.length < period) return null;
        double k = 2.0 / (period + 1), ema = v[0];
        for (int i = 1; i < v.length; i++) ema = v[i] * k + ema * (1 - k);
        return ema;
    }

    private static Double lastAtr(List<Bar> bars, int period) {
        int n = bars.size();
        if (n < 2) return null;
        double prevClose = bars.get(0).c, sum = 0;
        int cnt = 0, start = Math.max(0, n - period);
        for (int i = 0; i < n; i++) {
            Bar b = bars.get(i);
            double tr = Math.max(b.h - b.l, Math.max(Math.abs(b.h - prevClose), Math.abs(b.l - prevClose)));
            prevClose = b.c;
            if (i >= start) { sum += tr; cnt++; }
        }
        return cnt == 0 ? null : sum / cnt;
    }

    /** Wilder RSI, last value. */
    private static Double lastRsi(double[] v, int period) {
        if (v.length < period + 1) return null;
        double gain = 0, loss = 0;
        for (int i = 1; i <= period; i++) {
            double ch = v[i] - v[i - 1];
            if (ch >= 0) gain += ch; else loss -= ch;
        }
        double avgG = gain / period, avgL = loss / period;
        for (int i = period + 1; i < v.length; i++) {
            double ch = v[i] - v[i - 1];
            avgG = (avgG * (period - 1) + Math.max(ch, 0)) / period;
            avgL = (avgL * (period - 1) + Math.max(-ch, 0)) / period;
        }
        if (avgL == 0) return 100.0;
        double rs = avgG / avgL;
        return 100.0 - 100.0 / (1 + rs);
    }

    private static Double prevSessionClose(List<Bar> daily, String today) {
        for (int i = daily.size() - 1; i >= 0; i--) {
            String ts = daily.get(i).ts;
            String date = ts.length() >= 10 ? ts.substring(0, 10) : ts;
            if (date.compareTo(today) < 0) return daily.get(i).c;
        }
        return daily.isEmpty() ? null : daily.get(daily.size() - 1).c;
    }

    private static String trendHint(List<Bar> daily) {
        double[] c = new double[daily.size()];
        for (int i = 0; i < c.length; i++) c[i] = daily.get(i).c;
        Double e9 = lastEma(c, 9), e20 = lastEma(c, 20), e50 = lastEma(c, 50);
        if (e9 == null || e20 == null || e50 == null) return "mixed/range";
        double last = c[c.length - 1];
        if (e9 > e20 && e20 > e50 && last > e9) return "up";
        if (e9 < e20 && e20 < e50 && last < e9) return "down";
        return "mixed/range";
    }

    // ---- option chain ------------------------------------------------------

    private static Map<String, Object> optionDigest(Object section, Object nearestExpiry) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> m = asMap(section);
        if (m == null) return out;
        List<Object> rows = asList(m.get("data"));
        if (rows == null || rows.isEmpty()) return out;

        // Parse each strike row into a compact record.
        record Row(double strike, Leg ce, Leg pe) {}
        List<Row> chain = new ArrayList<>();
        Double spot = null;
        for (Object o : rows) {
            Map<String, Object> rm = asMap(o);
            if (rm == null) continue;
            Double strike = dbl(rm.get("strikePrice"));
            if (strike == null) continue;
            if (spot == null) spot = dbl(rm.get("underlyingSpotPrice"));
            chain.add(new Row(strike, leg(rm.get("callOptions")), leg(rm.get("putOptions"))));
        }
        if (chain.isEmpty()) return out;
        chain.sort((a, b) -> Double.compare(a.strike, b.strike));
        if (spot != null) out.put("spot", r(spot, 2));
        if (nearestExpiry != null) out.put("nearest_expiry", String.valueOf(nearestExpiry));

        double totalCe = 0, totalPe = 0;
        Row atm = chain.get(0), callWall = chain.get(0), putWall = chain.get(0);
        double atmDist = Double.MAX_VALUE;
        for (Row row : chain) {
            totalCe += row.ce.oi;
            totalPe += row.pe.oi;
            if (row.ce.oi > callWall.ce.oi) callWall = row;
            if (row.pe.oi > putWall.pe.oi) putWall = row;
            if (spot != null && Math.abs(row.strike - spot) < atmDist) { atmDist = Math.abs(row.strike - spot); atm = row; }
        }
        out.put("atm_strike", r(atm.strike, 0));
        out.put("total_ce_oi", Math.round(totalCe));
        out.put("total_pe_oi", Math.round(totalPe));
        out.put("pcr", totalCe > 0 ? r(totalPe / totalCe, 3) : null);
        out.put("max_pain", r(maxPain(chain.stream().map(x -> new double[]{x.strike, x.ce.oi, x.pe.oi}).toList()), 0));
        out.put("call_wall", r(callWall.strike, 0));
        out.put("put_wall", r(putWall.strike, 0));
        out.put("atm_ce_iv", r(atm.ce.iv, 2));
        out.put("atm_pe_iv", r(atm.pe.iv, 2));
        out.put("iv_skew_pe_minus_ce", r(atm.pe.iv - atm.ce.iv, 2));

        // ATM ± 7 strikes only — enough structure for the strategist, tiny in tokens.
        int atmIdx = chain.indexOf(atm);
        int lo = Math.max(0, atmIdx - 7), hi = Math.min(chain.size(), atmIdx + 8);
        List<Object> table = new ArrayList<>();
        for (int i = lo; i < hi; i++) {
            Row row = chain.get(i);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("strike", r(row.strike, 0));
            e.put("ce_oi", Math.round(row.ce.oi));
            e.put("ce_doi", Math.round(row.ce.oi - row.ce.prevOi));
            e.put("ce_ltp", r(row.ce.ltp, 2));
            e.put("ce_iv", r(row.ce.iv, 2));
            e.put("ce_delta", r(row.ce.delta, 3));
            e.put("pe_oi", Math.round(row.pe.oi));
            e.put("pe_doi", Math.round(row.pe.oi - row.pe.prevOi));
            e.put("pe_ltp", r(row.pe.ltp, 2));
            e.put("pe_iv", r(row.pe.iv, 2));
            e.put("pe_delta", r(row.pe.delta, 3));
            table.add(e);
        }
        out.put("chain_atm_pm7", table);
        return out;
    }

    /** {@code {strike, ce_oi, pe_oi}} rows -> the strike that minimises total writer payout. */
    private static double maxPain(List<double[]> chain) {
        double best = chain.get(0)[0], bestPain = Double.MAX_VALUE;
        for (double[] cand : chain) {
            double e = cand[0], pain = 0;
            for (double[] row : chain) {
                if (e > row[0]) pain += (e - row[0]) * row[1]; // CE in-the-money
                if (row[0] > e) pain += (row[0] - e) * row[2]; // PE in-the-money
            }
            if (pain < bestPain) { bestPain = pain; best = e; }
        }
        return best;
    }

    /** Pull a CE/PE leg's {@code marketData} + {@code optionGreeks} into a {@link Leg}. */
    private static Leg leg(Object node) {
        Map<String, Object> n = asMap(node);
        Map<String, Object> md = n == null ? null : asMap(n.get("marketData"));
        Map<String, Object> g = n == null ? null : asMap(n.get("optionGreeks"));
        return new Leg(
                md == null ? 0 : orZero(dbl(md.get("oi"))),
                md == null ? 0 : orZero(dbl(md.get("prevOi"))),
                md == null ? 0 : orZero(dbl(md.get("ltp"))),
                g == null ? 0 : orZero(dbl(g.get("iv"))),
                g == null ? 0 : orZero(dbl(g.get("delta"))));
    }

    private static double orZero(Double v) { return v == null ? 0.0 : v; }

    // ---- generic JSON navigation ------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) { return o instanceof Map ? (Map<String, Object>) o : null; }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) { return o instanceof List ? (List<Object>) o : null; }

    private static Double dbl(Object o) {
        if (o == null) return null;
        try { return Double.parseDouble(String.valueOf(o)); } catch (NumberFormatException e) { return null; }
    }

    private static double r(Double v, int places) {
        if (v == null || v.isNaN() || v.isInfinite()) return 0.0;
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }
}
