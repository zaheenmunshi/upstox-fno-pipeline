package com.zaheenmunshi.upstox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Port of {@code journal.py}: record trades and compute real win-rate /
 * expectancy / cost drag. Data is stored in {@code trade_journal.csv}.
 *
 * <pre>
 *   journal add --label "NIFTY 24500CE" --side LONG --entry 120 --exit 150 \
 *               --qty 75 --costs 60 --setup ema_crossover --notes "trend day"
 *   journal stats
 * </pre>
 */
public final class Journal {

    private static final String[] FIELDS =
            {"timestamp", "label", "side", "entry", "exit", "qty", "costs", "pnl", "setup", "notes"};

    private Journal() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage: journal add ... | journal stats");
            System.exit(2);
        }
        Config cfg = Config.load();
        switch (args[0]) {
            case "add"   -> add(cfg, args);
            case "stats" -> stats(cfg);
            default -> { System.out.println("Unknown subcommand: " + args[0] + " (use add|stats)"); System.exit(2); }
        }
    }

    private static void add(Config cfg, String[] args) throws IOException {
        Map<String, String> o = new HashMap<>();
        o.put("costs", "0"); o.put("setup", ""); o.put("notes", "");
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--label" -> o.put("label", args[++i]);
                case "--side"  -> o.put("side", args[++i]);
                case "--entry" -> o.put("entry", args[++i]);
                case "--exit"  -> o.put("exit", args[++i]);
                case "--qty"   -> o.put("qty", args[++i]);
                case "--costs" -> o.put("costs", args[++i]);
                case "--setup" -> o.put("setup", args[++i]);
                case "--notes" -> o.put("notes", args[++i]);
                default -> { System.out.println("Unknown argument: " + args[i]); System.exit(2); }
            }
        }
        for (String req : new String[]{"label", "side", "entry", "exit", "qty"}) {
            if (!o.containsKey(req)) { System.out.println("ERROR: --" + req + " is required."); System.exit(2); }
        }

        double entry = Double.parseDouble(o.get("entry"));
        double exit = Double.parseDouble(o.get("exit"));
        double qty = Double.parseDouble(o.get("qty"));
        double costs = Double.parseDouble(o.get("costs"));
        String side = o.get("side").toUpperCase();
        double gross = side.equals("LONG") ? (exit - entry) * qty : (entry - exit) * qty;
        double pnl = Math.round((gross - costs) * 100.0) / 100.0;

        Map<String, String> row = new HashMap<>();
        row.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")));
        row.put("label", o.get("label"));
        row.put("side", side);
        row.put("entry", trim(entry));
        row.put("exit", trim(exit));
        row.put("qty", trim(qty));
        row.put("costs", trim(costs));
        row.put("pnl", String.valueOf(pnl));
        row.put("setup", o.get("setup"));
        row.put("notes", o.get("notes"));

        Path file = cfg.journalFile();
        boolean exists = Files.exists(file);
        StringBuilder sb = new StringBuilder();
        if (!exists) sb.append(csvRow(FIELDS)).append("\n");
        String[] values = new String[FIELDS.length];
        for (int i = 0; i < FIELDS.length; i++) values[i] = row.getOrDefault(FIELDS[i], "");
        sb.append(csvRow(values)).append("\n");
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        System.out.printf("Logged: %s %s pnl=%s (after costs %s).%n", row.get("label"), side, pnl, trim(costs));
    }

    private static void stats(Config cfg) throws IOException {
        Path file = cfg.journalFile();
        if (!Files.exists(file)) {
            System.out.println("No " + file + " yet. Add trades first with: journal add ...");
            return;
        }
        List<Map<String, String>> rows = readCsv(file);
        if (rows.isEmpty()) { System.out.println("Journal is empty."); return; }

        int n = rows.size();
        double total = 0, grossWin = 0, grossLoss = 0, costs = 0;
        int winCount = 0, lossCount = 0;
        List<Double> pnls = new ArrayList<>();
        for (Map<String, String> r : rows) {
            double p = Double.parseDouble(r.get("pnl"));
            pnls.add(p);
            costs += Double.parseDouble(r.getOrDefault("costs", "0"));
            total += p;
            if (p > 0) { winCount++; grossWin += p; } else { lossCount++; grossLoss -= p; }
        }
        double winRate = 100.0 * winCount / n;
        double avgWin = winCount == 0 ? 0 : grossWin / winCount;
        double avgLoss = lossCount == 0 ? 0 : grossLoss / lossCount;
        double expectancy = total / n;
        String profitFactor = grossLoss > 0 ? String.format("%.2f", grossWin / grossLoss) : "inf";

        double equity = 0, peak = 0, maxDd = 0;
        for (double p : pnls) { equity += p; peak = Math.max(peak, equity); maxDd = Math.max(maxDd, peak - equity); }

        System.out.println("=".repeat(52));
        System.out.println(" TRADE JOURNAL STATS");
        System.out.println("=".repeat(52));
        System.out.printf(" Trades:        %d  (wins %d, losses %d)%n", n, winCount, lossCount);
        System.out.printf(" Win rate:      %.1f%%%n", winRate);
        System.out.printf(" Avg win:       Rs %.0f    Avg loss: Rs %.0f%n", avgWin, avgLoss);
        System.out.printf(" Expectancy:    Rs %.0f per trade  (>0 = edge after costs)%n", expectancy);
        System.out.printf(" Profit factor: %s  (>1 = profitable)%n", profitFactor);
        System.out.printf(" Net P&L:       Rs %.0f    Total costs paid: Rs %.0f%n", total, costs);
        System.out.printf(" Max drawdown:  Rs %.0f%n", maxDd);
        System.out.println("=".repeat(52));
        if (n < 30) System.out.println(" NOTE: <30 trades - low confidence. Keep logging before trusting this.");
        if (expectancy <= 0) System.out.println(" WARNING: expectancy <= 0 - this approach is NOT making money after costs.");
    }

    /** Drop a trailing ".0" so 75.0 prints as 75 (matches Python's int-ish display where applicable). */
    private static String trim(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) return String.valueOf((long) v);
        return String.valueOf(v);
    }

    // ---- minimal RFC-4180-ish CSV ----------------------------------------
    private static String csvRow(String[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(csvField(values[i] == null ? "" : values[i]));
        }
        return sb.toString();
    }

    private static String csvField(String v) {
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    private static List<Map<String, String>> readCsv(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<Map<String, String>> rows = new ArrayList<>();
        if (lines.isEmpty()) return rows;
        String[] header = parseCsvLine(lines.get(0));
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            String[] cells = parseCsvLine(lines.get(i));
            Map<String, String> row = new HashMap<>();
            for (int j = 0; j < header.length && j < cells.length; j++) row.put(header[j], cells[j]);
            rows.add(row);
        }
        return rows;
    }

    private static String[] parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else inQuotes = false;
                } else cur.append(c);
            } else {
                if (c == ',') { out.add(cur.toString()); cur.setLength(0); }
                else if (c == '"') inQuotes = true;
                else cur.append(c);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }
}
