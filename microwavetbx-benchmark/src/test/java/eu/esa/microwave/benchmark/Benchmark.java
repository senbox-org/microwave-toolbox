/*
 * Copyright (C) 2021 SkyWatch Space Applications Inc. https://www.skywatch.com
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */
package eu.esa.microwave.benchmark;

import eu.esa.sar.cloud.json.JSON;
import eu.esa.snap.core.dataio.cache.CacheManager;
import org.esa.snap.core.util.StopWatch;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.core.util.io.FileUtils;
import org.json.simple.JSONObject;

import javax.media.jai.JAI;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;

/**
 * Base class for microwave-toolbox operator benchmarks.
 *
 * <p>Each {@link #run()} performs one cold-start pass (JIT cold, caches empty) followed by
 * {@code iterations} timed passes. Per-pass wall-clock is collected as raw milliseconds and
 * reduced to min / max / avg / median / stddev. Every completed test appends one self-contained
 * record to an append-only history store ({@code benchmark_history.jsonl}) and updates a
 * merged latest-view ({@code benchmark_latest.json}).
 *
 * <p>The history store is the source of truth for trend analysis and consolidated reporting
 * (see {@link BenchmarkReport}). Because each record carries its own environment + provenance
 * (machine, git commit, heap, JAI cache, tile size, JVM), records produced on different days or
 * in different sessions remain comparable and can be aggregated into a single report regardless
 * of when each test ran.
 */
@SuppressWarnings("unchecked")
public abstract class Benchmark {

    private final static boolean DISABLE_BENCHMARKS = true;
    private final static String REFERENCE_NAME = "";
    private final static int DEFAULT_ITERATIONS = 5;
    private final static boolean deleteTempOutputFiles = true;

    /** Results directory. Override with -Dbenchmark.results.dir=... */
    private static final File RESULTS_DIR =
            new File(System.getProperty("benchmark.results.dir", "E:\\benchmark"));
    private static final File HISTORY_FILE = new File(RESULTS_DIR, "benchmark_history.jsonl");
    private static final File LATEST_FILE = new File(RESULTS_DIR, "benchmark_latest.json");

    private final String groupName;
    private final String testName;
    private final int iterations;
    protected File outputFolder;

    /** Optional: pixels processed per pass, set by execute() via {@link #recordThroughput(long)}. */
    private long pixelsProcessed = 0L;

    // Captured once per JVM — git commit and toolbox version don't change mid-run.
    private static volatile String cachedGitCommit;
    private static volatile String cachedToolboxVersion;

    public Benchmark(final String groupName, final String testName) {
        this(groupName, testName, DEFAULT_ITERATIONS);
    }

    public Benchmark(final String groupName, final String testName, final int iterations) {
        this.groupName = groupName;
        this.testName = FileUtils.createValidFilename(testName);
        this.iterations = iterations;
        SystemUtils.LOG.info("Benchmark history file: " + HISTORY_FILE);
    }

    /**
     * Lets a subclass report how many pixels a single pass processed so that a size-normalised
     * throughput (megapixels/s) can be recorded alongside wall-clock. Optional; calling it more
     * than once (e.g. once per iteration) is fine — the value is expected to be constant.
     */
    protected void recordThroughput(final long pixels) {
        this.pixelsProcessed = pixels;
    }

    public void run() throws Exception {
        if (DISABLE_BENCHMARKS) {
            System.out.println("Benchmark " + groupName + " disabled");
            return;
        }

        try {
            SystemUtils.LOG.info("Initial cold start run");
            StopWatch coldStartTimer = new StopWatch();
            outputFolder = createTempFolder(testName);
            this.execute();
            coldStartTimer.stop();
            final long coldStartMillis = coldStartTimer.getTimeDiff();

            SystemUtils.LOG.info("Cold start time " + StopWatch.getTimeString(coldStartMillis));
            resetCaches();
            if (deleteTempOutputFiles)
                FileUtils.deleteTree(outputFolder);

            final long[] runtimes = new long[iterations];
            for (int i = 1; i <= iterations; ++i) {
                SystemUtils.LOG.info("Run " + i + " of " + iterations + " started");
                StopWatch timer = new StopWatch();
                outputFolder = createTempFolder(testName + i);
                this.execute();
                timer.stop();

                runtimes[i - 1] = timer.getTimeDiff();
                SystemUtils.LOG.info("Run " + i + " of " + iterations + " end time " + timer.getTimeDiffString());

                resetCaches();
                FileUtils.deleteTree(outputFolder);
            }

            writeResults(coldStartMillis, runtimes);
        } catch (Exception e) {
            SystemUtils.LOG.severe("Benchmark " + testName + " failed: " + e.getMessage());
            writeFailedResult(e.getMessage());
            throw e;
        }
    }

    // ---- statistics ---------------------------------------------------------

    private static long min(final long[] a) {
        long m = Long.MAX_VALUE;
        for (long v : a) m = Math.min(m, v);
        return m;
    }

    private static long max(final long[] a) {
        long m = Long.MIN_VALUE;
        for (long v : a) m = Math.max(m, v);
        return m;
    }

    private static long avg(final long[] a) {
        long sum = 0;
        for (long v : a) sum += v;
        return sum / a.length;
    }

    private static long median(final long[] a) {
        final long[] s = a.clone();
        Arrays.sort(s);
        final int n = s.length;
        return (n % 2 == 1) ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2;
    }

    private static long stdDev(final long[] a, final long mean) {
        double sumSq = 0;
        for (long v : a) {
            final double d = v - mean;
            sumSq += d * d;
        }
        return Math.round(Math.sqrt(sumSq / a.length));
    }

    // ---- result records -----------------------------------------------------

    private void writeResults(final long coldStartMillis, final long[] runtimes) throws Exception {
        final long minMillis = min(runtimes);
        final long maxMillis = max(runtimes);
        final long avgMillis = avg(runtimes);
        final long medianMillis = median(runtimes);
        final long stdDevMillis = stdDev(runtimes, avgMillis);

        SystemUtils.LOG.warning(testName + " median " + StopWatch.getTimeString(medianMillis)
                + " (avg " + StopWatch.getTimeString(avgMillis) + ", ±" + stdDevMillis + " ms)");

        final JSONObject record = new JSONObject();
        record.put("timestamp", Instant.now().toString());
        record.put("date", LocalDate.now().toString());
        record.put("group", groupName);
        record.put("test", testName);
        if (!REFERENCE_NAME.isEmpty()) {
            record.put("reference", REFERENCE_NAME);
        }
        record.put("gitCommit", gitCommit());
        record.put("toolboxVersion", toolboxVersion());
        record.put("iterations", iterations);

        record.put("coldStartMillis", coldStartMillis);
        record.put("minMillis", minMillis);
        record.put("maxMillis", maxMillis);
        record.put("avgMillis", avgMillis);
        record.put("medianMillis", medianMillis);
        record.put("stdDevMillis", stdDevMillis);

        // human-readable mirror so the raw file is still eyeball-able
        record.put("coldStart", StopWatch.getTimeString(coldStartMillis));
        record.put("minTime", StopWatch.getTimeString(minMillis));
        record.put("maxTime", StopWatch.getTimeString(maxMillis));
        record.put("avgTime", StopWatch.getTimeString(avgMillis));
        record.put("medianTime", StopWatch.getTimeString(medianMillis));

        if (pixelsProcessed > 0 && medianMillis > 0) {
            final double mpps = (pixelsProcessed / 1_000_000.0) / (medianMillis / 1000.0);
            record.put("pixels", pixelsProcessed);
            record.put("megapixelsPerSec", Math.round(mpps * 100.0) / 100.0);
        }

        record.put("environment", environment());

        // Trend vs the most recent prior record for the same machine+group+test.
        final Long prevMedian = previousMedianMillis();
        if (prevMedian != null && prevMedian > 0) {
            final double deltaPct = (medianMillis - prevMedian) * 100.0 / prevMedian;
            record.put("prevMedianMillis", prevMedian);
            record.put("deltaVsPrevPct", Math.round(deltaPct * 10.0) / 10.0);
            final String arrow = deltaPct < -1 ? "▼ faster" : deltaPct > 1 ? "▲ SLOWER" : "= flat";
            SystemUtils.LOG.warning(String.format("%s %s %.1f%% vs previous (%s → %s)",
                    testName, arrow, deltaPct,
                    StopWatch.getTimeString(prevMedian), StopWatch.getTimeString(medianMillis)));
        }

        appendHistory(record);
        mergeLatest(record);
    }

    private void writeFailedResult(final String errorMsg) throws Exception {
        final JSONObject record = new JSONObject();
        record.put("timestamp", Instant.now().toString());
        record.put("date", LocalDate.now().toString());
        record.put("group", groupName);
        record.put("test", testName);
        record.put("gitCommit", gitCommit());
        record.put("toolboxVersion", toolboxVersion());
        record.put("error", errorMsg);
        record.put("environment", environment());

        appendHistory(record);
        mergeLatest(record);
    }

    /** Most recent median for this machine+group+test from history, or null if none yet. */
    private Long previousMedianMillis() {
        if (!HISTORY_FILE.exists()) {
            return null;
        }
        final String machine = hostname();
        Long latest = null;
        String latestTs = "";
        try {
            for (String line : Files.readAllLines(HISTORY_FILE.toPath(), StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                final JSONObject rec = (JSONObject) JSON.parse(line);
                if (!groupName.equals(rec.get("group")) || !testName.equals(rec.get("test"))) continue;
                if (rec.get("medianMillis") == null) continue;
                final Object env = rec.get("environment");
                final Object recMachine = (env instanceof JSONObject) ? ((JSONObject) env).get("hostname") : null;
                if (recMachine != null && !machine.equals(recMachine)) continue;
                final String ts = String.valueOf(rec.getOrDefault("timestamp", ""));
                if (ts.compareTo(latestTs) >= 0) {
                    latestTs = ts;
                    latest = JSON.getLong(rec.get("medianMillis"));
                }
            }
        } catch (Exception e) {
            SystemUtils.LOG.warning("Could not read benchmark history for trend: " + e.getMessage());
        }
        return latest;
    }

    private static void appendHistory(final JSONObject record) {
        try {
            RESULTS_DIR.mkdirs();
            // Cross-process safe append: each surefire fork takes an exclusive lock before writing.
            try (RandomAccessFile raf = new RandomAccessFile(HISTORY_FILE, "rw");
                 FileChannel channel = raf.getChannel();
                 FileLock ignored = channel.lock()) {
                channel.position(channel.size());
                final byte[] line = (record.toJSONString() + System.lineSeparator())
                        .getBytes(StandardCharsets.UTF_8);
                channel.write(java.nio.ByteBuffer.wrap(line));
            }
        } catch (IOException e) {
            SystemUtils.LOG.severe("Could not append benchmark history: " + e.getMessage());
        }
    }

    /** Merge this record into benchmark_latest.json under {group: {test: record}}. */
    private static synchronized void mergeLatest(final JSONObject record) {
        try {
            RESULTS_DIR.mkdirs();
            final JSONObject root = LATEST_FILE.exists()
                    ? (JSONObject) JSON.loadJSON(LATEST_FILE) : new JSONObject();
            final String group = String.valueOf(record.get("group"));
            final String test = String.valueOf(record.get("test"));
            final JSONObject groupObj = (JSONObject) root.getOrDefault(group, new JSONObject());
            groupObj.put(test, record);
            root.put(group, groupObj);
            JSON.write(root, LATEST_FILE);
        } catch (Exception e) {
            SystemUtils.LOG.severe("Could not update benchmark_latest.json: " + e.getMessage());
        }
    }

    // ---- environment / provenance ------------------------------------------

    static JSONObject environment() {
        final JSONObject env = new JSONObject();
        env.put("hostname", hostname());
        env.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        env.put("jvm", System.getProperty("java.version"));
        env.put("cores", Runtime.getRuntime().availableProcessors());
        env.put("maxHeapMB", Runtime.getRuntime().maxMemory() / 1024 / 1024);
        env.put("jaiCacheMB", JAI.getDefaultInstance().getTileCache().getMemoryCapacity() / 1024 / 1024);
        env.put("tileParallelism", JAI.getDefaultInstance().getTileScheduler().getParallelism());
        env.put("tile", (int) JAI.getDefaultTileSize().getWidth() + "x" + (int) JAI.getDefaultTileSize().getHeight());
        return env;
    }

    private static String hostname() {
        final String env = System.getenv("COMPUTERNAME"); // Windows
        if (env != null && !env.isBlank()) {
            return env;
        }
        final String unixHost = System.getenv("HOSTNAME");
        if (unixHost != null && !unixHost.isBlank()) {
            return unixHost;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-host";
        }
    }

    private static String gitCommit() {
        if (cachedGitCommit != null) {
            return cachedGitCommit;
        }
        String commit = "unknown";
        try {
            final Process p = new ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                    .directory(new File(System.getProperty("user.dir")))
                    .redirectErrorStream(true)
                    .start();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                final String firstLine = r.readLine();
                if (p.waitFor() == 0 && firstLine != null && !firstLine.isBlank() && !firstLine.contains(" ")) {
                    commit = firstLine.trim();
                }
            }
        } catch (Exception ignored) {
            // git not on PATH or not a repo — leave "unknown"
        }
        cachedGitCommit = commit;
        return commit;
    }

    private static String toolboxVersion() {
        if (cachedToolboxVersion != null) {
            return cachedToolboxVersion;
        }
        String version = System.getProperty("microwavetbx.version",
                System.getProperty("snap.version", "unknown"));
        final Package pkg = Benchmark.class.getPackage();
        if ("unknown".equals(version) && pkg != null && pkg.getImplementationVersion() != null) {
            version = pkg.getImplementationVersion();
        }
        cachedToolboxVersion = version;
        return version;
    }

    private File createTempFolder(final String name) throws IOException {
        String user = SystemUtils.getUserName();
        String tempDir = System.getProperty("java.io.tmpdir");
        Path subFolderPath = Paths.get(tempDir, "snap-" + user);
        Files.createDirectories(subFolderPath);
        File tmpFolder = Files.createTempDirectory(subFolderPath, name).toFile();
        System.out.println("Using temp folder: " + tmpFolder);
        return tmpFolder;
    }

    /**
     * Drop everything that could carry state across iterations: the JAI tile cache
     * (default 5+ GB in this module), the ProductCache's CacheManager, and a
     * best-effort GC. Without this, run N+1 sees partial tiles from run N and
     * timings become bi-modal.
     */
    private static void resetCaches() {
        JAI.getDefaultInstance().getTileCache().flush();
        CacheManager.dispose();
        SystemUtils.freeAllMemory();
    }

    protected abstract void execute() throws Exception;
}
