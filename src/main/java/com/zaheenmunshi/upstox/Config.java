package com.zaheenmunshi.upstox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads Upstox credentials from {@code .env} and anchors all file paths to the
 * project root, so the working directory does not matter (mirrors the Python
 * scripts, which anchored via {@code __file__}).
 */
public final class Config {

    /** Upstox OAuth/token API version (mirrors API_VERSION = "2.0" in the Python scripts). */
    public static final String API_VERSION = "2.0";

    private final Path projectRoot;
    private final Map<String, String> env;

    private Config(Path projectRoot, Map<String, String> env) {
        this.projectRoot = projectRoot;
        this.env = env;
    }

    /** Discover the project root and load {@code .env}. */
    public static Config load() {
        Path root = findProjectRoot();
        Map<String, String> env = parseEnv(root.resolve(".env"));
        return new Config(root, env);
    }

    /** Walk up from the working directory until a dir contains pom.xml or .env. */
    private static Path findProjectRoot() {
        Path start = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path p = start; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("pom.xml")) || Files.exists(p.resolve(".env"))) {
                return p;
            }
        }
        return start;
    }

    /** Minimal .env parser: KEY=VALUE lines, ignoring blanks, comments and quotes. */
    private static Map<String, String> parseEnv(Path envFile) {
        Map<String, String> map = new HashMap<>();
        if (!Files.exists(envFile)) {
            return map;
        }
        try {
            for (String raw : Files.readAllLines(envFile)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).strip();
                String val = line.substring(eq + 1).strip();
                if (val.length() >= 2
                        && ((val.startsWith("\"") && val.endsWith("\""))
                         || (val.startsWith("'") && val.endsWith("'")))) {
                    val = val.substring(1, val.length() - 1);
                }
                map.put(key, val);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + envFile + ": " + e.getMessage(), e);
        }
        return map;
    }

    public Path projectRoot()  { return projectRoot; }
    public Path tokenFile()    { return projectRoot.resolve(".access_token"); }
    public Path dataDir()      { return projectRoot.resolve("data"); }
    public Path positionsFile() { return projectRoot.resolve("config").resolve("positions.json"); }
    public Path journalFile()  { return projectRoot.resolve("trade_journal.csv"); }

    /**
     * Read the daily access token saved by {@code auth}/{@code get-token}.
     * Prints guidance and exits (mirrors the Python {@code load_token()} helpers)
     * if the file is missing or empty.
     */
    public String loadToken() {
        Path tf = tokenFile();
        if (!Files.exists(tf)) {
            System.out.println("ERROR: " + tf + " not found. Run 'auth' or 'get-token' first.");
            System.exit(1);
        }
        String token = "";
        try {
            token = Files.readString(tf).strip();
        } catch (IOException e) {
            System.out.println("ERROR: could not read " + tf + ": " + e.getMessage());
            System.exit(1);
        }
        if (token.isEmpty()) {
            System.out.println("ERROR: " + tf + " is empty. Re-authenticate.");
            System.exit(1);
        }
        return token;
    }

    /** Persist a freshly-exchanged access token to {@code .access_token}. */
    public void saveToken(String token) {
        try {
            Files.writeString(tokenFile(), token);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write token: " + e.getMessage(), e);
        }
    }

    public String apiKey()      { return env.get("UPSTOX_API_KEY"); }
    public String apiSecret()   { return env.get("UPSTOX_API_SECRET"); }
    public String redirectUri() { return env.get("UPSTOX_REDIRECT_URI"); }

    /** Names of required credentials that are missing or still placeholders. */
    public List<String> missingCredentials() {
        List<String> missing = new ArrayList<>();
        check("UPSTOX_API_KEY", apiKey(), missing);
        check("UPSTOX_API_SECRET", apiSecret(), missing);
        check("UPSTOX_REDIRECT_URI", redirectUri(), missing);
        return missing;
    }

    private static void check(String name, String val, List<String> missing) {
        if (val == null || val.isBlank() || val.startsWith("paste_") || val.startsWith("your_")) {
            missing.add(name);
        }
    }
}
