package com.zaheenmunshi.upstox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Port of {@code cleanup_data.py}: safely remove generated market-data files so
 * storage does not bloat. ONLY ever touches the project's {@code data/} output
 * (and optionally {@code __pycache__}). NEVER deletes source, {@code .env},
 * {@code .access_token}, agents, the venv, or memory.
 *
 * <pre>
 *   cleanup                  delete ALL generated *.json files in data/
 *   cleanup --keep-latest 5  keep the 5 newest, delete the rest
 *   cleanup --older-than 3   delete files older than 3 days
 *   cleanup --pycache        also clear __pycache__ folders (excludes .venv)
 *   cleanup --dry-run        preview only - delete nothing
 * </pre>
 */
public final class CleanupData {

    private CleanupData() {}

    public static void main(String[] args) {
        Integer keepLatest = null;
        Integer olderThan = null;
        boolean pycache = false;
        boolean dryRun = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--keep-latest" -> keepLatest = Integer.parseInt(args[++i]);
                case "--older-than"  -> olderThan = Integer.parseInt(args[++i]);
                case "--pycache"     -> pycache = true;
                case "--dry-run"     -> dryRun = true;
                default -> {
                    System.out.println("Unknown argument: " + args[i]);
                    System.exit(2);
                }
            }
        }

        Config cfg = Config.load();
        Path projectDir = cfg.projectRoot();
        Path dataDir = cfg.dataDir();

        if (!Files.isDirectory(dataDir)) {
            System.out.println("No data/ directory at " + dataDir + "; nothing to clean.");
            return;
        }

        // Newest first. Only *.json files directly inside data/ are ever eligible.
        List<Path> files = collectDataFiles(dataDir);
        files.sort(Comparator.comparingLong(CleanupData::mtime).reversed());

        List<Path> toDelete = new ArrayList<>(files);
        if (keepLatest != null) {
            toDelete = new ArrayList<>(files.subList(Math.min(keepLatest, files.size()), files.size()));
        }
        if (olderThan != null) {
            long cutoff = System.currentTimeMillis() - (long) olderThan * 86_400_000L;
            toDelete.removeIf(f -> mtime(f) >= cutoff);
        }

        List<Path> pycacheDirs = pycache ? collectPycacheDirs(projectDir) : List.of();

        long totalBytes = toDelete.stream().mapToLong(CleanupData::size).sum();
        System.out.printf("data/ : %d file(s) found, %d to delete (%s).%n",
                files.size(), toDelete.size(), human(totalBytes));
        if (!pycacheDirs.isEmpty()) {
            System.out.printf("__pycache__: %d folder(s) to delete.%n", pycacheDirs.size());
        }

        if (dryRun) {
            for (Path f : toDelete) System.out.println("  [dry-run] would delete " + projectDir.relativize(f));
            for (Path d : pycacheDirs) System.out.println("  [dry-run] would delete " + projectDir.relativize(d));
            System.out.println("Dry run - nothing was deleted.");
            return;
        }

        int deleted = 0;
        for (Path f : toDelete) {
            if (within(f, dataDir)) {  // final guard before each delete
                try {
                    Files.delete(f);
                    deleted++;
                } catch (IOException e) {
                    System.out.println("  could not delete " + f + " " + e.getMessage());
                }
            }
        }
        for (Path d : pycacheDirs) {
            if (within(d, projectDir)) {
                deleteRecursively(d);
            }
        }

        System.out.printf("Done. Deleted %d data file(s), freed ~%s.%n", deleted, human(totalBytes));
        if (!pycacheDirs.isEmpty()) {
            System.out.printf("Removed %d __pycache__ folder(s).%n", pycacheDirs.size());
        }
    }

    /** All *.json files directly inside data/ (covers market_data_* and snapshot_*). */
    private static List<Path> collectDataFiles(Path dataDir) {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dataDir)) {
            s.filter(Files::isRegularFile)
             .filter(p -> p.getFileName().toString().endsWith(".json"))
             .filter(p -> within(p, dataDir))
             .forEach(out::add);
        } catch (IOException e) {
            System.out.println("  could not list " + dataDir + ": " + e.getMessage());
        }
        return out;
    }

    /** __pycache__ directories anywhere under the project, excluding .venv. */
    private static List<Path> collectPycacheDirs(Path projectDir) {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(projectDir)) {
            s.filter(Files::isDirectory)
             .filter(p -> p.getFileName() != null && p.getFileName().toString().equals("__pycache__"))
             .filter(p -> {
                 for (Path part : projectDir.relativize(p)) {
                     if (part.toString().equals(".venv")) return false;
                 }
                 return true;
             })
             .forEach(out::add);
        } catch (IOException e) {
            System.out.println("  could not scan for __pycache__: " + e.getMessage());
        }
        return out;
    }

    /** True only if {@code path} resolves to somewhere inside {@code base} (safety guard). */
    private static boolean within(Path path, Path base) {
        Path p = path.toAbsolutePath().normalize();
        Path b = base.toAbsolutePath().normalize();
        return p.startsWith(b);
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) {}
            });
        } catch (IOException e) {
            System.out.println("  could not delete " + dir + " " + e.getMessage());
        }
    }

    private static long mtime(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0L; }
    }

    private static long size(Path p) {
        try { return Files.size(p); } catch (IOException e) { return 0L; }
    }

    private static String human(long n) {
        double v = n;
        for (String unit : new String[]{"B", "KB", "MB", "GB"}) {
            if (v < 1024) return String.format("%.1f%s", v, unit);
            v /= 1024;
        }
        return String.format("%.1fTB", v);
    }
}
