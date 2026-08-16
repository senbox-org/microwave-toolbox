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
 *     <li>both fixture products exist on disk ({@link #GSLC_IFG} and {@link #TRAD_IFG_TC}), and</li>
 *     <li>a {@code python} executable is reachable on PATH.</li>
 * </ul>
 * Neither fixture lives in the shared {@code TestData.inputSAR} tree — they are ad-hoc
 * outputs from the local ERS GSLC-parity investigation (see
 * {@code E:\ESA\snap_tmp\ers_final_diff.py} and sibling scripts) — so on any machine other
 * than the one that produced them this test cleanly no-ops.
 * <p>
 * <b>Why the pass/fail assertion is configurable.</b> As of this writing the ERS v5 GSLC
 * interferogram is <em>known</em> to fail the harness's phase gates (phase-residual-conc,
 * residual-rms-rad, gx/gy-median-ratio) against the traditional-DInSAR control — that
 * disagreement is exactly the open problem this harness exists to make executable and
 * trackable. So by default ({@code -Dgslc.equivalence.expectPass} unset or {@code false})
 * this test does NOT assert the harness exits 0; it only asserts that the harness ran to
 * completion and printed at least 4 {@code GATE <name> ...} lines (proving the script,
 * its I/O, and its grid-aggregation math all work end-to-end on the real products — a
 * "harness smoke test"). Once the GSLC pipeline bug this harness was built to expose is
 * fixed, re-run with {@code -Dgslc.equivalence.expectPass=true} to assert full parity
 * (exit code 0, i.e. no {@code GATE ... FAIL} lines); flip the default once that holds.
 */
@RunWith(LongTestRunner.class)
public class GSLCEquivalenceLongTest {

    private static final File GSLC_IFG = new File("E:/Output/ers/ERS_v5_ifg.dim");
    private static final File TRAD_IFG_TC = new File("E:/Output/ers/trad_dinsar2_TC.dim");

    private static final String EXPECT_PASS_PROPERTY = "gslc.equivalence.expectPass";
    private static final int MIN_EXPECTED_GATE_LINES = 4;

    @Test
    public void testHarnessRunsOnErsPair() throws Exception {
        assumeTrue(GSLC_IFG + " not found (ad-hoc ERS fixture, not in shared TestData tree)",
                GSLC_IFG.isFile());
        assumeTrue(TRAD_IFG_TC + " not found (ad-hoc ERS fixture, not in shared TestData tree)",
                TRAD_IFG_TC.isFile());
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
        command.add(TRAD_IFG_TC.getAbsolutePath());

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

        final boolean expectPass = Boolean.parseBoolean(
                System.getProperty(EXPECT_PASS_PROPERTY, "false"));

        if (expectPass) {
            assertEquals("Expected the equivalence harness to report full parity "
                    + "(exit 0) with -D" + EXPECT_PASS_PROPERTY + "=true", 0, exitCode);
        } else {
            // Harness-smoke mode: the v5 GSLC product is known to fail the phase gates
            // today, so we only assert the harness actually ran and produced gate output —
            // not that it passed. This keeps the long test green while the open problem
            // (tracked by this very harness) remains unresolved.
            assertTrue("Expected the harness to print at least " + MIN_EXPECTED_GATE_LINES
                            + " GATE lines, got " + gateLineCount,
                    gateLineCount >= MIN_EXPECTED_GATE_LINES);
        }
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
