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

import org.junit.Assume;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class MemoryProbeTest {

    /** Keeps the allocated array reachable so the JIT cannot eliminate the allocation. */
    private static volatile byte[] sink;

    @Test
    public void testAllocationMeasured() throws Exception {
        final MemoryProbe probe = MemoryProbe.start();
        sink = new byte[64 * 1024 * 1024];
        sink[sink.length - 1] = 1;
        final MemoryProbe.Reading reading = probe.stop();
        assertNotNull(reading);

        assertTrue("allocMB=" + reading.allocMB, reading.allocMB >= 64);
        assertTrue("peakLiveMB=" + reading.peakLiveMB, reading.peakLiveMB >= 0);
        assertTrue("gcPauseMs=" + reading.gcPauseMs, reading.gcPauseMs >= 0);
        // No upper bound: a collection that began before the window can exceed a very short window.
        assertTrue("gcShare=" + reading.gcShare, reading.gcShare >= 0 && !Double.isNaN(reading.gcShare)
                && !Double.isInfinite(reading.gcShare));
        sink = null;
    }

    @Test
    public void testRetainedAllocationVisibleAfterGc() throws Exception {
        final MemoryProbe probe = MemoryProbe.start();
        sink = new byte[128 * 1024 * 1024];
        sink[sink.length - 1] = 1;
        System.gc();
        final MemoryProbe.Reading reading = probe.stop();
        final boolean reachable = sink != null && sink[sink.length - 1] == 1;
        sink = null;

        Assume.assumeTrue("JVM ignored System.gc() (no post-GC sample)", reading.peakFromGc);
        assertTrue(reachable);
        assertTrue("peakLiveMB=" + reading.peakLiveMB + " should include the retained 128 MB",
                reading.peakLiveMB >= 128);
    }

    @Test
    public void testDisabledByDefault() throws Exception {
        Assume.assumeFalse("benchmark.enable is set externally", Boolean.getBoolean("benchmark.enable"));

        final AtomicBoolean executed = new AtomicBoolean(false);
        final Benchmark benchmark = new Benchmark("MemoryProbeTest", "disabled", 1) {
            @Override
            protected void execute() {
                executed.set(true);
            }
        };
        benchmark.run();

        assertEquals("execute() must not run without -Dbenchmark.enable=true", false, executed.get());
    }
}
