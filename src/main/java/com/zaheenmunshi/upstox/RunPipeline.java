package com.zaheenmunshi.upstox;

import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Port of {@code run_pipeline.py}: one-command DATA stage of the daily pipeline:
 *   token (optional) -> fresh market snapshot -> readiness report.
 *
 * The AI stages (news-scanner, backtester, fno-strategist, risk-manager) are then
 * orchestrated by Claude at the top level — see docs/PIPELINE.md.
 *
 * <pre>
 *   pipeline                                  # use existing .access_token
 *   pipeline "https://127.0.0.1/?code=XXXX"   # exchange code first, then refresh
 * </pre>
 */
public final class RunPipeline {

    private RunPipeline() {}

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(60));
        System.out.println(" DAILY TRADE PIPELINE - DATA STAGE");
        System.out.println("=".repeat(60));

        Config cfg = Config.load();
        String url = args.length > 0 ? args[0] : null;

        // Stage 0: token
        if (url != null) {
            System.out.println("\n[1/3] Exchanging login code for an access token...");
            GetToken.main(new String[]{url}); // exits the JVM on failure
        } else {
            boolean hasToken = Files.exists(cfg.tokenFile()) && !readTrim(cfg.tokenFile()).isEmpty();
            if (!hasToken) {
                System.out.println("\nNo .access_token found. Pass your login redirect URL:");
                System.out.println("   pipeline \"https://127.0.0.1/?code=XXXX\"");
                System.exit(1);
            }
            System.out.println("\n[1/3] Using existing .access_token.");
        }

        // Stage 1: fresh snapshot
        System.out.println("\n[2/3] Fetching fresh market snapshot (status / candles / option chain)...");
        MarketSnapshot.main(new String[]{});

        // Stage 2: readiness report
        System.out.println("\n[3/3] Readiness check...");
        Path latest = latestSnapshot(cfg.dataDir());
        if (latest == null) {
            System.out.println(">> No snapshot file was produced. Pipeline stopped.");
            System.exit(1);
        }
        double ageMin = (System.currentTimeMillis() - Files.getLastModifiedTime(latest).toMillis()) / 60000.0;
        Map<String, Object> snap = Json.MAPPER.readValue(latest.toFile(), new TypeReference<Map<String, Object>>() {});
        Map<String, Object> sections = (Map<String, Object>) snap.getOrDefault("sections", Map.of());
        Map<String, Object> errors = (Map<String, Object>) snap.getOrDefault("errors", Map.of());

        List<String> needed = List.of("intraday_NIFTY", "historical_NIFTY", "option_chain_NIFTY");
        List<String> haveFno = needed.stream().filter(sections::containsKey).toList();
        List<String> missingFno = needed.stream().filter(s -> !sections.containsKey(s)).toList();

        System.out.printf("   Snapshot:        %s  (age %.1f min)%n", cfg.projectRoot().relativize(latest), ageMin);
        System.out.println("   Sections OK:     " + sections.keySet());
        if (!errors.isEmpty()) System.out.println("   Sections w/err:  " + errors.keySet());
        System.out.println("   F&O inputs ready: " + (haveFno.isEmpty() ? "NONE" : haveFno));
        if (!missingFno.isEmpty()) {
            System.out.println("   F&O inputs MISSING: " + missingFno + "  (analysis confidence will be lower)");
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println(" DATA READY -> hand off to Claude for the AI stages:");
        System.out.println("   Stage A (parallel): news-scanner  +  backtester");
        System.out.println("   Stage B: fno-strategist  ->  Stage C: risk-manager (APPROVE/VETO)");
        System.out.println("   After entry: position-monitor + trade-journal");
        System.out.println("=".repeat(60));
        System.out.println("In chat just say: \"run the pipeline\" or \"give me today's NIFTY trade\".");
    }

    private static String readTrim(Path p) {
        try { return Files.readString(p).strip(); } catch (IOException e) { return ""; }
    }

    private static Path latestSnapshot(Path dataDir) throws IOException {
        if (!Files.isDirectory(dataDir)) return null;
        try (Stream<Path> s = Files.list(dataDir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().matches("snapshot_.*\\.json"))
                    .max(Comparator.comparingLong(p -> {
                        try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0L; }
                    }))
                    .orElse(null);
        }
    }
}
