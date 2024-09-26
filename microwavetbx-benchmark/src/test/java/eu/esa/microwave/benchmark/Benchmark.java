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
import org.esa.snap.core.util.StopWatch;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.core.util.io.FileUtils;
import org.json.simple.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@SuppressWarnings("unchecked")
public abstract class Benchmark {

    private final static boolean DISABLE_BENCHMARKS = true;
    private final static String REFERENCE_NAME = "";
    private final static int iterations = 2;
    private final static boolean deleteTempOutputFiles = true;

    private final String groupName;
    private final String testName;
    private final File resultsFile = getResultsFile();
    protected File outputFolder;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String DATE_SUFFIX = LocalDate.now().format(DATE_FORMATTER);

    public Benchmark(final String groupName, final String testName) {
        this.groupName = groupName;
        this.testName = FileUtils.createValidFilename(testName);

        SystemUtils.LOG.info("Benchmark results file: " + resultsFile);
    }

    public static File getResultsFile() {
        String reference = REFERENCE_NAME.isEmpty() ? "" : "_" + REFERENCE_NAME;
        return new File("/tmp/benchmark_results_"+DATE_SUFFIX+reference+".json");
    }

    public void run() throws Exception {
        if(DISABLE_BENCHMARKS) {
            System.out.println("Benchmark " + groupName + " disabled");
            return;
        }

        try {
            SystemUtils.LOG.info("Initial cold start run");
            StopWatch coldStartTimer = new StopWatch();
            outputFolder = createTempFolder(testName);
            this.execute();
            coldStartTimer.stop();

            SystemUtils.LOG.info("Cold start time " + coldStartTimer.getTimeDiffString());
            SystemUtils.freeAllMemory();
            if (deleteTempOutputFiles)
                FileUtils.deleteTree(outputFolder);
            long totalTime = 0L;

            long minTime = Long.MAX_VALUE;
            long maxTime = Long.MIN_VALUE;
            for (int i = 1; i <= iterations; ++i) {
                SystemUtils.LOG.info("Run " + i + " of " + iterations + " started");
                StopWatch timer = new StopWatch();
                outputFolder = createTempFolder(testName + i);
                this.execute();
                timer.stop();

                long currentRunTime = timer.getTimeDiff(); // Store the current run time
                totalTime += currentRunTime;
                minTime = Math.min(minTime, currentRunTime);
                maxTime = Math.max(maxTime, currentRunTime);

                SystemUtils.LOG.info("Run " + i + " of " + iterations + " end time " + timer.getTimeDiffString());

                SystemUtils.freeAllMemory();
                FileUtils.deleteTree(outputFolder);
            }

            String coldStartTime = StopWatch.getTimeString(coldStartTimer.getTimeDiff());
            String avgTime = StopWatch.getTimeString(totalTime / (long) iterations);
            SystemUtils.LOG.warning(testName + " average time " + avgTime);

            writeJSONResults(minTime, maxTime, avgTime, coldStartTime);
        } catch (Exception e) {
            SystemUtils.LOG.severe("Benchmark " + testName + " failed: " + e.getMessage());
            writeJSONFailedResults(e.getMessage());
            throw e;
        }
    }

    private void writeJSONFailedResults(final String errorMsg) throws Exception {
        final JSONObject json = readJSON(resultsFile);
        final JSONObject group = (JSONObject) json.getOrDefault(groupName, new JSONObject());
        json.put(groupName, group);

        final JSONObject results = new JSONObject();
        results.put("error", errorMsg);
        group.put(testName, results);

        JSON.write(json, resultsFile);
    }

    private void writeJSONResults(final long minTime, long maxTime, String avgTime, String coldStartTime) throws Exception {
        final JSONObject json = readJSON(resultsFile);
        final JSONObject group = (JSONObject) json.getOrDefault(groupName, new JSONObject());
        json.put(groupName, group);

        final JSONObject results = (JSONObject) group.getOrDefault(testName, new JSONObject());
        if(results.containsKey("error")) {
            results.remove("error");
        }
        if(results.containsKey("avgTime")) {
            results.put("prevAvgTime", results.get("avgTime"));
        }

        results.put("date", LocalDate.now().toString());
        results.put("iterations", iterations);
        results.put("coldStart", coldStartTime);
        results.put("minTime", StopWatch.getTimeString(minTime));
        results.put("maxTime", StopWatch.getTimeString(maxTime));
        results.put("avgTime", avgTime);
        group.put(testName, results);

        JSON.write(json, resultsFile);
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

    private JSONObject readJSON(final File file) throws Exception {
        if(file.exists()) {
            return (JSONObject) JSON.loadJSON(file);
        }
        return new JSONObject();
    }

    protected abstract void execute() throws Exception;
}
