package com.zaheenmunshi.upstox;

import com.upstox.ApiClient;
import com.upstox.ApiException;
import com.upstox.api.GetHistoricalCandleResponse;
import io.swagger.client.api.HistoryV3Api;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Port of {@code backtest.py}: validate a directional setup on historical Upstox candles.
 *
 * HONEST SCOPE: this backtests the UNDERLYING directional signal, NOT option P&L.
 * Treat results as evidence the *signal* has (or lacks) an edge; apply option
 * structure + costs separately. PAST PERFORMANCE != FUTURE RESULTS.
 */
public final class Backtest {

    private record Candle(String ts, double o, double h, double l, double c, double v) {}
    private record Trade(String side, double entry, double exit, double pnl) {}

    private Backtest() {}

    public static void main(String[] args) {
        // Defaults mirror the Python argparse.
        String instrument = null, unit = "minutes", strategy = "ema_crossover";
        int interval = 5, fast = 9, slow = 20, lookbackDays = 30;
        double stopAtr = 1.5, targetAtr = 3.0, costPct = 0.05;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--instrument"    -> instrument = args[++i];
                case "--unit"          -> unit = args[++i];
                case "--interval"      -> interval = Integer.parseInt(args[++i]);
                case "--strategy"      -> strategy = args[++i];
                case "--fast"          -> fast = Integer.parseInt(args[++i]);
                case "--slow"          -> slow = Integer.parseInt(args[++i]);
                case "--stop-atr"      -> stopAtr = Double.parseDouble(args[++i]);
                case "--target-atr"    -> targetAtr = Double.parseDouble(args[++i]);
                case "--lookback-days" -> lookbackDays = Integer.parseInt(args[++i]);
                case "--cost-pct"      -> costPct = Double.parseDouble(args[++i]);
                default -> { System.out.println("Unknown argument: " + args[i]); System.exit(2); }
            }
        }
        if (instrument == null) {
            System.out.println("ERROR: --instrument is required, e.g. --instrument \"NSE_INDEX|Nifty 50\"");
            System.exit(2);
        }
        if (!strategy.equals("ema_crossover")) {
            System.out.println("ERROR: unsupported --strategy " + strategy + " (only ema_crossover).");
            System.exit(2);
        }

        Config cfg = Config.load();
        ApiClient client = UpstoxClients.authenticated(cfg.loadToken());

        List<Candle> candles;
        try {
            candles = fetchCandles(client, instrument, unit, interval, lookbackDays);
        } catch (ApiException e) {
            System.out.println("ERROR fetching candles: HTTP " + e.getCode() + ": " + e.getResponseBody());
            System.exit(1);
            return;
        }

        if (candles.size() < Math.max(slow + 5, 20)) {
            System.out.println("Only " + candles.size() + " candles fetched - not enough to backtest. Increase --lookback-days.");
            System.exit(1);
        }

        List<Trade> trades = backtestEmaCrossover(candles, fast, slow, stopAtr, targetAtr, costPct);
        String label = String.format("%s %d%s EMA%d/%d SL%sxATR TP%sxATR (%d candles)",
                instrument, interval, unit, fast, slow, stopAtr, targetAtr, candles.size());
        report(trades, label);
    }

    /** Return candles oldest-first. Upstox candle = [ts, open, high, low, close, volume, oi]. */
    private static List<Candle> fetchCandles(ApiClient client, String instrument, String unit,
                                             int interval, int lookbackDays) throws ApiException {
        String toDate = LocalDate.now().toString();
        String fromDate = LocalDate.now().minusDays(lookbackDays).toString();
        GetHistoricalCandleResponse resp =
                new HistoryV3Api(client).getHistoricalCandleData1(instrument, unit, interval, toDate, fromDate);

        List<Candle> out = new ArrayList<>();
        if (resp.getData() == null || resp.getData().getCandles() == null) return out;
        for (List<Object> c : resp.getData().getCandles()) {
            try {
                out.add(new Candle(
                        String.valueOf(c.get(0)),
                        num(c.get(1)), num(c.get(2)), num(c.get(3)), num(c.get(4)),
                        c.size() > 5 ? num(c.get(5)) : 0.0));
            } catch (RuntimeException ignored) {
                // skip malformed candle (matches Python's try/except continue)
            }
        }
        out.sort((a, b) -> a.ts.compareTo(b.ts)); // oldest first
        return out;
    }

    private static double num(Object o) { return Double.parseDouble(String.valueOf(o)); }

    private static double[] emaSeries(double[] values, int period) {
        if (values.length == 0) return new double[0];
        double k = 2.0 / (period + 1);
        double[] out = new double[values.length];
        out[0] = values[0];
        for (int i = 1; i < values.length; i++) out[i] = values[i] * k + out[i - 1] * (1 - k);
        return out;
    }

    /** Wilder-ish ATR (simple rolling mean of TR), aligned to candles. */
    private static double[] atrSeries(List<Candle> candles, int period) {
        int n = candles.size();
        double[] trs = new double[n];
        double prevClose = candles.get(0).c;
        for (int i = 0; i < n; i++) {
            Candle c = candles.get(i);
            trs[i] = Math.max(c.h - c.l, Math.max(Math.abs(c.h - prevClose), Math.abs(c.l - prevClose)));
            prevClose = c.c;
        }
        double[] atr = new double[n];
        for (int i = 0; i < n; i++) {
            int start = Math.max(0, i - period + 1);
            double sum = 0; int cnt = 0;
            for (int j = start; j <= i; j++) { sum += trs[j]; cnt++; }
            atr[i] = sum / cnt;
        }
        return atr;
    }

    private static List<Trade> backtestEmaCrossover(List<Candle> candles, int fast, int slow,
                                                    double stopAtr, double targetAtr, double costPct) {
        int n = candles.size();
        double[] closes = new double[n];
        for (int i = 0; i < n; i++) closes[i] = candles.get(i).c;
        double[] ef = emaSeries(closes, fast);
        double[] es = emaSeries(closes, slow);
        double[] atr = atrSeries(candles, 14);

        List<Trade> trades = new ArrayList<>();
        String side = null; double entry = 0, stop = 0, target = 0;

        for (int i = 1; i < n; i++) {
            Candle c = candles.get(i);
            boolean crossedUp = ef[i] > es[i] && ef[i - 1] <= es[i - 1];
            boolean crossedDn = ef[i] < es[i] && ef[i - 1] >= es[i - 1];

            if (side == null) {
                if (crossedUp || crossedDn) {
                    side = crossedUp ? "LONG" : "SHORT";
                    entry = c.c;
                    double a = atr[i] != 0 ? atr[i] : entry * 0.001;
                    if (side.equals("LONG")) { stop = entry - stopAtr * a; target = entry + targetAtr * a; }
                    else { stop = entry + stopAtr * a; target = entry - targetAtr * a; }
                }
                continue;
            }

            Double exitPrice = null;
            if (side.equals("LONG")) {
                if (c.l <= stop) exitPrice = stop;
                else if (c.h >= target) exitPrice = target;
                else if (crossedDn) exitPrice = c.c;
            } else {
                if (c.h >= stop) exitPrice = stop;
                else if (c.l <= target) exitPrice = target;
                else if (crossedUp) exitPrice = c.c;
            }

            if (exitPrice != null) {
                double gross = side.equals("LONG") ? (exitPrice - entry) : (entry - exitPrice);
                double cost = (entry + exitPrice) * (costPct / 100.0);
                trades.add(new Trade(side, entry, exitPrice, gross - cost));
                side = null;
            }
        }
        return trades;
    }

    private static void report(List<Trade> trades, String label) {
        System.out.println("\n" + "=".repeat(56));
        System.out.println(" BACKTEST RESULT - " + label);
        System.out.println("=".repeat(56));
        int n = trades.size();
        if (n == 0) {
            System.out.println(" No trades generated. Try a longer lookback or different params.");
            return;
        }
        List<Trade> wins = new ArrayList<>(), losses = new ArrayList<>();
        double total = 0, grossWin = 0, grossLoss = 0;
        for (Trade t : trades) {
            total += t.pnl;
            if (t.pnl > 0) { wins.add(t); grossWin += t.pnl; }
            else { losses.add(t); grossLoss -= t.pnl; }
        }
        double winRate = 100.0 * wins.size() / n;
        double avgWin = wins.isEmpty() ? 0 : grossWin / wins.size();
        double avgLoss = losses.isEmpty() ? 0 : grossLoss / losses.size();
        double expectancy = total / n;
        String profitFactor = grossLoss > 0 ? String.format("%.2f", grossWin / grossLoss) : "inf";

        double equity = 0, peak = 0, maxDd = 0;
        for (Trade t : trades) {
            equity += t.pnl;
            peak = Math.max(peak, equity);
            maxDd = Math.max(maxDd, peak - equity);
        }

        System.out.printf(" Trades:        %d   (wins %d, losses %d)%n", n, wins.size(), losses.size());
        System.out.printf(" Win rate:      %.1f%%%n", winRate);
        System.out.printf(" Avg win:       %.2f pts   Avg loss: %.2f pts%n", avgWin, avgLoss);
        System.out.printf(" Expectancy:    %.2f pts/trade   (>0 = positive edge, net of costs)%n", expectancy);
        System.out.printf(" Profit factor: %s   (>1 = profitable)%n", profitFactor);
        System.out.printf(" Total:         %.2f pts   Max drawdown: %.2f pts%n", total, maxDd);
        System.out.println("=".repeat(56));
        System.out.println(" NOTE: underlying-signal backtest only - not option P&L. Past != future.");
    }
}
