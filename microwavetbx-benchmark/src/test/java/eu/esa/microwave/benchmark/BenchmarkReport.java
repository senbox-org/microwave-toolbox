/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
import org.json.simple.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Consolidated benchmark report generator.
 *
 * <p>Reads the append-only {@code benchmark_history.jsonl} written by {@link Benchmark} and emits a
 * single consolidated report (Markdown + HTML) regardless of when each test was run. Because the
 * history store persists across sessions and every record is keyed by (machine, group, test), this
 * report aggregates results that may have been produced on different days or in separate runs.
 *
 * <p>For each (machine, group, test) it shows the latest median, the baseline (earliest record on
 * that machine), the best-ever median, the delta vs baseline, and a coarse trend sparkline over the
 * full history.
 *
 * <p>Run via the {@link #generateReport()} test, or {@code main} for {@code mvn exec:java}.
 */
public class BenchmarkReport {

    private static final File RESULTS_DIR =
            new File(System.getProperty("benchmark.results.dir", "E:\\benchmark"));
    private static final File HISTORY_FILE = new File(RESULTS_DIR, "benchmark_history.jsonl");
    private static final File MD_FILE = new File(RESULTS_DIR, "benchmark_report.md");
    private static final File HTML_FILE = new File(RESULTS_DIR, "benchmark_report.html");

    /** One history record reduced to the fields the report needs. */
    private static final class Entry {
        String timestamp = "";
        String machine = "";
        String group = "";
        String test = "";
        String gitCommit = "";
        long medianMillis = -1;
        String error;
    }

    @Test
    public void generateReport() throws Exception {
        if (!HISTORY_FILE.exists()) {
            System.out.println("No benchmark history at " + HISTORY_FILE + " — nothing to report.");
            return;
        }
        writeReports(load());
        System.out.println("Benchmark report written to:\n  " + MD_FILE + "\n  " + HTML_FILE);
    }

    public static void main(String[] args) throws Exception {
        new BenchmarkReport().generateReport();
    }

    private List<Entry> load() throws Exception {
        final List<Entry> entries = new ArrayList<>();
        for (String line : Files.readAllLines(HISTORY_FILE.toPath(), StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            final JSONObject rec = (JSONObject) JSON.parse(line);
            final Entry e = new Entry();
            e.timestamp = String.valueOf(rec.getOrDefault("timestamp", ""));
            e.group = String.valueOf(rec.getOrDefault("group", "?"));
            e.test = String.valueOf(rec.getOrDefault("test", "?"));
            e.gitCommit = String.valueOf(rec.getOrDefault("gitCommit", ""));
            final Object env = rec.get("environment");
            e.machine = (env instanceof JSONObject)
                    ? String.valueOf(((JSONObject) env).getOrDefault("hostname", "?")) : "?";
            if (rec.get("error") != null) {
                e.error = String.valueOf(rec.get("error"));
            } else if (rec.get("medianMillis") != null) {
                e.medianMillis = JSON.getLong(rec.get("medianMillis"));
            }
            entries.add(e);
        }
        return entries;
    }

    private void writeReports(final List<Entry> entries) throws Exception {
        // key: machine  group  test  → chronologically sorted history for that test
        final Map<String, List<Entry>> byTest = new TreeMap<>();
        for (Entry e : entries) {
            byTest.computeIfAbsent(e.machine + "" + e.group + "" + e.test,
                    k -> new ArrayList<>()).add(e);
        }
        for (List<Entry> list : byTest.values()) {
            list.sort(Comparator.comparing(a -> a.timestamp));
        }

        final StringBuilder md = new StringBuilder();
        final StringBuilder html = new StringBuilder();
        md.append("# Microwave-Toolbox Benchmark Report\n\n");
        md.append("_Generated ").append(java.time.Instant.now()).append("_\n\n");
        html.append("<html><head><meta charset='utf-8'><style>")
            .append("body{font-family:sans-serif;margin:20px}")
            .append("table{border-collapse:collapse;margin-bottom:24px}")
            .append("th,td{border:1px solid #ccc;padding:4px 8px;text-align:right}")
            .append("th:first-child,td:first-child{text-align:left}")
            .append(".up{color:#b00}.down{color:#070}.flat{color:#777}")
            .append("</style></head><body>")
            .append("<h1>Microwave-Toolbox Benchmark Report</h1>")
            .append("<p><em>Generated ").append(java.time.Instant.now()).append("</em></p>");

        // group by machine then group
        final Map<String, Map<String, List<List<Entry>>>> byMachineGroup = new TreeMap<>();
        for (List<Entry> hist : byTest.values()) {
            final Entry any = hist.get(hist.size() - 1);
            byMachineGroup
                    .computeIfAbsent(any.machine, k -> new TreeMap<>())
                    .computeIfAbsent(any.group, k -> new ArrayList<>())
                    .add(hist);
        }

        for (Map.Entry<String, Map<String, List<List<Entry>>>> me : byMachineGroup.entrySet()) {
            md.append("## Machine: ").append(me.getKey()).append("\n\n");
            html.append("<h2>Machine: ").append(esc(me.getKey())).append("</h2>");

            for (Map.Entry<String, List<List<Entry>>> ge : me.getValue().entrySet()) {
                md.append("### ").append(ge.getKey()).append("\n\n");
                md.append("| Test | Latest | Baseline | Best | Δ vs baseline | Runs | Trend | Commit |\n");
                md.append("|---|--:|--:|--:|--:|--:|---|---|\n");

                html.append("<h3>").append(esc(ge.getKey())).append("</h3>");
                html.append("<table><tr><th>Test</th><th>Latest</th><th>Baseline</th><th>Best</th>")
                    .append("<th>Δ vs baseline</th><th>Runs</th><th>Trend</th><th>Commit</th></tr>");

                final List<List<Entry>> tests = ge.getValue();
                tests.sort(Comparator.comparing(h -> h.get(0).test));

                for (List<Entry> hist : tests) {
                    final Entry latest = hist.get(hist.size() - 1);
                    if (latest.error != null) {
                        md.append("| ").append(latest.test).append(" | ERROR | | | | ")
                          .append(hist.size()).append(" | | ").append(shortCommit(latest)).append(" |\n");
                        html.append("<tr><td>").append(esc(latest.test))
                            .append("</td><td colspan='6' class='up'>ERROR: ").append(esc(latest.error))
                            .append("</td><td>").append(esc(shortCommit(latest))).append("</td></tr>");
                        continue;
                    }

                    Entry baseline = null;
                    long best = Long.MAX_VALUE;
                    final List<Long> series = new ArrayList<>();
                    for (Entry e : hist) {
                        if (e.medianMillis < 0) continue;
                        if (baseline == null) baseline = e;
                        best = Math.min(best, e.medianMillis);
                        series.add(e.medianMillis);
                    }
                    if (baseline == null) continue;

                    final long latestMs = latest.medianMillis;
                    final long baseMs = baseline.medianMillis;
                    final double deltaPct = baseMs > 0 ? (latestMs - baseMs) * 100.0 / baseMs : 0;
                    final String cls = deltaPct < -1 ? "down" : deltaPct > 1 ? "up" : "flat";
                    final String deltaStr = String.format("%+.1f%%", deltaPct);
                    final String spark = sparkline(series);

                    md.append("| ").append(latest.test)
                      .append(" | ").append(fmt(latestMs))
                      .append(" | ").append(fmt(baseMs))
                      .append(" | ").append(fmt(best))
                      .append(" | ").append(deltaStr)
                      .append(" | ").append(series.size())
                      .append(" | `").append(spark).append('`')
                      .append(" | ").append(shortCommit(latest)).append(" |\n");

                    html.append("<tr><td>").append(esc(latest.test))
                        .append("</td><td>").append(fmt(latestMs))
                        .append("</td><td>").append(fmt(baseMs))
                        .append("</td><td>").append(fmt(best))
                        .append("</td><td class='").append(cls).append("'>").append(deltaStr)
                        .append("</td><td>").append(series.size())
                        .append("</td><td>").append(spark)
                        .append("</td><td>").append(esc(shortCommit(latest))).append("</td></tr>");
                }
                md.append('\n');
                html.append("</table>");
            }
        }
        html.append("</body></html>");

        RESULTS_DIR.mkdirs();
        Files.write(MD_FILE.toPath(), md.toString().getBytes(StandardCharsets.UTF_8));
        Files.write(HTML_FILE.toPath(), html.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String fmt(final long millis) {
        if (millis >= 1000) {
            return String.format("%.2f s", millis / 1000.0);
        }
        return millis + " ms";
    }

    private static String shortCommit(final Entry e) {
        return e.gitCommit == null ? "" : e.gitCommit;
    }

    /** Unicode block sparkline over the median series. */
    private static String sparkline(final List<Long> series) {
        if (series.isEmpty()) return "";
        final char[] bars = {'▁', '▂', '▃', '▄', '▅', '▆', '▇', '█'};
        long lo = Long.MAX_VALUE, hi = Long.MIN_VALUE;
        for (long v : series) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
        final long range = Math.max(1, hi - lo);
        final StringBuilder sb = new StringBuilder();
        for (long v : series) {
            final int idx = (int) ((v - lo) * (bars.length - 1) / range);
            sb.append(bars[idx]);
        }
        return sb.toString();
    }

    private static String esc(final String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
