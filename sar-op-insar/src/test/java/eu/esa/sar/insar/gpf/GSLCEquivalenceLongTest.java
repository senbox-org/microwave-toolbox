/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc.
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
package eu.esa.sar.insar.gpf;

import com.bc.ceres.test.LongTestRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Shells out to {@code validation/gslc_equivalence.py} (Task 2.1) and checks that the
 * consolidated GSLC-vs-traditional numeric-equivalence harness actually runs against the
 * ERS pair produced by the GSLC InSAR-parity investigation.
 * <p>
 * <b>Long test.</b> Gated off by default; enable explicitly:
 * <pre>
 *   mvn test -pl sar-op-insar -Dtest=GSLCEquivalenceLongTest -Denable.long.tests=true
 * </pre>
 * On top of the {@code -Denable.long.tests} gate, this test is skipped (via
 * {@link org.junit.Assume}) unless:
 * <ul>
 *     <li>both fixture products exist on disk ({@link #GSLC_IFG} and {@link #TRAD_IFG}), and</li>
 *     <li>a {@code python} executable is reachable on PATH.</li>
 * </ul>
 * Neither fixture lives in the shared {@code TestData.inputSAR} tree — they are ad-hoc
 * outputs from the local ERS GSLC-parity investigation (see
 * {@code E:\ESA\snap_tmp\ers_final_diff.py} and sibling scripts) — so on any machine other
 * than the one that produced them this test cleanly no-ops.
 * <p>
 * <b>Verdict.</b> By default the exit code must agree with the printed gates
 * (exit 1 iff a {@code GATE ... FAIL} line was printed) and at least 4 GATE lines must
 * appear; with {@code -Dgslc.equivalence.expectPass=true} exit 0 (full parity) is required.
 * See {@link #assertVerdict}. Fixture paths: {@code -Dgslc.equivalence.gslcIfg} and
 * {@code -Dgslc.equivalence.tradIfg}.
 */
@RunWith(LongTestRunner.class)
public class GSLCEquivalenceLongTest {

    /**
     * Fixture paths are configurable because the ERS tree these once pointed at
     * (E:/Output/ers) was deleted during cleanup, leaving this test permanently skipped
     * while still appearing to guard the parity gates.
     */
    private static final File GSLC_IFG = new File(
            System.getProperty("gslc.equivalence.gslcIfg", "E:/Output/parity/ven_gslc_ifg.dim"));
    private static final File TRAD_IFG = new File(
            System.getProperty("gslc.equivalence.tradIfg", "E:/Output/parity/ven_trad_ifg.dim"));

    private static final String EXPECT_PASS_PROPERTY = "gslc.equivalence.expectPass";
    static final int MIN_EXPECTED_GATE_LINES = 4;

    /**
     * Verdict logic, extracted so it can be tested without any fixture (see
     * GSLCEquivalenceVerdictTest).
     *
     * Before 2026-09-18 the non-expectPass branch asserted only the GATE line count, so
     * this test passed whether every gate FAILed or every gate PASSed - it guarded
     * nothing while appearing to guard the parity gates.
     *
     * @param sawFail whether any {@code GATE ... FAIL} line was printed
     */
    static void assertVerdict(final int gateLineCount, final boolean sawFail,
                              final int exitCode, final boolean expectPass) {
        assertTrue("Expected at least " + MIN_EXPECTED_GATE_LINES
                        + " GATE lines, got " + gateLineCount,
                gateLineCount >= MIN_EXPECTED_GATE_LINES);
        if (expectPass) {
            assertEquals("Expected full parity (exit 0) with -D" + EXPECT_PASS_PROPERTY + "=true",
                    0, exitCode);
        } else {
            assertEquals("Exit code must agree with the printed gates (sawFail=" + sawFail + ")",
                    sawFail ? 1 : 0, exitCode);
        }
    }

    @Test
    public void testHarnessRunsOnErsPair() throws Exception {
        assumeTrue(GSLC_IFG + " not found (parity fixture, not in shared TestData tree)",
                GSLC_IFG.isFile());
        assumeTrue(TRAD_IFG + " not found (parity fixture, not in shared TestData tree)",
                TRAD_IFG.isFile());
        assumeTrue("python executable not found on PATH", isPythonOnPath());

        final File repoRoot = findRepoRoot();
        assumeTrue("could not locate repo root (validation/gslc_equivalence.py not found "
                + "walking up from " + System.getProperty("user.dir") + ")", repoRoot != null);

        final File script = new File(repoRoot, "validation/gslc_equivalence.py");
        assumeTrue(script + " not found", script.isFile());

        final List<String> command = new ArrayList<>();
        command.add("python");
        command.add("validation/gslc_equivalence.py");
        command.add(GSLC_IFG.getAbsolutePath());
        command.add(TRAD_IFG.getAbsolutePath());

        System.out.println("Running: " + String.join(" ", command) + "  (cwd=" + repoRoot + ")");

        final ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(repoRoot);
        pb.redirectErrorStream(true);
        final Process process = pb.start();

        int gateLineCount = 0;
        boolean sawFail = false;
        try (final BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
                if (line.startsWith("GATE ")) {
                    gateLineCount++;
                    final String[] tokens = line.split("\\s+");
                    if (tokens.length >= 3 && "FAIL".equals(tokens[2])) {
                        sawFail = true;
                    }
                }
            }
        }
        final int exitCode = process.waitFor();
        System.out.println("gslc_equivalence.py exit code: " + exitCode
                + "  (" + gateLineCount + " GATE lines, sawFail=" + sawFail + ")");

        assertVerdict(gateLineCount, sawFail, exitCode,
                Boolean.parseBoolean(System.getProperty(EXPECT_PASS_PROPERTY, "false")));
    }

    /**
     * Walk up from the current working directory (the module directory when Maven runs a
     * single-module {@code test}, or the repo root when run from the reactor) looking for
     * {@code validation/gslc_equivalence.py} as the repo-root marker.
     */
    private static File findRepoRoot() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (new File(dir, "validation/gslc_equivalence.py").isFile()) {
                return dir;
            }
            dir = dir.getParentFile();
        }
        return null;
    }

    private static boolean isPythonOnPath() {
        try {
            final Process p = new ProcessBuilder("python", "--version")
                    .redirectErrorStream(true)
                    .start();
            return p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
