package com.zaheenmunshi.upstox;

import java.util.Arrays;

/**
 * Single entry point / subcommand dispatcher for the Upstox F&amp;O pipeline.
 *
 * <pre>
 *   java -jar target/upstox-fno-pipeline.jar &lt;command&gt; [args...]
 *
 *   auth                       interactive daily Upstox OAuth2 login
 *   get-token  &lt;code|url&gt;       non-interactive token exchange
 *   snapshot                   REST market snapshot -> data/snapshot_*.json
 *   stream                     WebSocket V3 live tick stream -> data/market_data_*.json
 *   backtest   [--flags]       validate a directional setup on historical candles
 *   monitor                    watch open positions vs stop/target/time-stop (alert-only)
 *   journal    add|stats [..]  log trades / show performance stats
 *   cleanup    [--flags]       safely delete generated data in data/
 *   pipeline   [url]           token(optional) -> snapshot -> readiness report
 * </pre>
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { usage(); System.exit(2); }
        String cmd = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (cmd) {
            case "auth"      -> Auth.main(rest);
            case "get-token" -> GetToken.main(rest);
            case "snapshot"  -> MarketSnapshot.main(rest);
            case "stream"    -> Streamer.main(rest);
            case "backtest"  -> Backtest.main(rest);
            case "monitor"   -> MonitorPositions.main(rest);
            case "journal"   -> Journal.main(rest);
            case "cleanup"   -> CleanupData.main(rest);
            case "pipeline"  -> RunPipeline.main(rest);
            case "-h", "--help", "help" -> usage();
            default -> { System.out.println("Unknown command: " + cmd + "\n"); usage(); System.exit(2); }
        }
    }

    private static void usage() {
        System.out.println("""
            Upstox F&O Live-Data Pipeline (Java)

            Usage: java -jar target/upstox-fno-pipeline.jar <command> [args...]

              auth                       interactive daily Upstox OAuth2 login
              get-token  <code|url>      non-interactive token exchange
              snapshot                   REST market snapshot -> data/snapshot_*.json
              stream                     WebSocket V3 live tick stream -> data/market_data_*.json
              backtest   [--flags]       validate a directional setup on historical candles
              monitor                    watch open positions vs stop/target/time-stop (alert-only)
              journal    add|stats [..]  log trades / show performance stats
              cleanup    [--flags]       safely delete generated data in data/
              pipeline   [url]           token(optional) -> snapshot -> readiness report
            """);
    }
}
