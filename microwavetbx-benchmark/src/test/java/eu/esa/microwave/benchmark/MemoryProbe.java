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

import com.sun.management.GarbageCollectionNotificationInfo;

import javax.management.ListenerNotFoundException;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-process memory probe for a single measured pass (memory spec section 5.1).
 *
 * <p>Usage: {@code MemoryProbe p = MemoryProbe.start(); ...work...; Reading r = p.stop();}.
 * {@link #stop()} also unregisters the GC listeners; a probe that is never stopped keeps its
 * listeners, so always call it (use try/finally if the work can throw).
 *
 * <ul>
 * <li>{@code peakLiveMB}: the largest total heap occupancy (all heap pools) measured <em>right
 *     after a garbage collection</em> during the window, taken from
 *     {@code GarbageCollectionNotificationInfo} on every {@link GarbageCollectorMXBean}. This is
 *     the closest cheap measure of the live set. It only sees what the collector reports: if no
 *     GC ran during the window there is no post-GC sample and the value falls back to the
 *     old-generation peak usage since start ({@code peakFromGc == false}), which is an upper bound
 *     because it can include garbage. Concurrent collectors may report an after-GC state that
 *     still holds floating garbage.</li>
 * <li>{@code allocMB}: bytes allocated by <em>all</em> threads (including threads that have since
 *     ended) between start and stop, via {@code com.sun.management.ThreadMXBean
 *     #getTotalThreadAllocatedBytes} (JDK 21+). All threads are summed because tile computation runs
 *     on JAI worker threads, not the calling thread; the cost is that unrelated background JVM
 *     threads running at the same time are included.</li>
 * <li>{@code gcPauseMs}: increase in accumulated collection time over all
 *     {@link GarbageCollectorMXBean}s. For concurrent collectors this is the collector's reported
 *     time, which is only approximately stop-the-world pause time.</li>
 * <li>{@code gcShare}: {@code gcPauseMs} divided by the wall-clock milliseconds of the pass. A
 *     collection that began before the window can inflate it for very short windows.</li>
 * </ul>
 *
 * Because {@code -Xmx} cannot change within one JVM, the probe gives a before-and-after signal at a
 * fixed heap; it does not give the minimum heap.
 */
public final class MemoryProbe {

    private static final double MB = 1024.0 * 1024.0;
    private static final long NOTIFICATION_WAIT_MS = 2000L;

    /** Readings of one probed pass. */
    public static final class Reading {
        public final double peakLiveMB;
        /** True when peakLiveMB comes from post-GC samples, false for the old-gen peak fallback. */
        public final boolean peakFromGc;
        public final double allocMB;
        public final long gcPauseMs;
        public final double gcShare;

        Reading(final double peakLiveMB, final boolean peakFromGc, final double allocMB,
                final long gcPauseMs, final double gcShare) {
            this.peakLiveMB = peakLiveMB;
            this.peakFromGc = peakFromGc;
            this.allocMB = allocMB;
            this.gcPauseMs = gcPauseMs;
            this.gcShare = gcShare;
        }
    }

    private final List<MemoryPoolMXBean> oldPools;
    private final Set<String> heapPoolNames;
    private final List<NotificationEmitter> emitters = new ArrayList<>();
    private final NotificationListener listener = this::onNotification;
    private final Object lock = new Object();
    private long maxAfterGcBytes = -1;
    private long notificationsSeen = 0;
    private final long startGcCount;
    private final long startNanos;
    private final long startAllocBytes;
    private final long startGcMillis;

    private MemoryProbe(final List<MemoryPoolMXBean> oldPools, final Set<String> heapPoolNames) {
        this.oldPools = oldPools;
        this.heapPoolNames = heapPoolNames;
        for (MemoryPoolMXBean pool : oldPools) {
            pool.resetPeakUsage();
        }
        this.startGcCount = totalGcCount();
        this.startGcMillis = totalGcMillis();
        this.startAllocBytes = totalAllocatedBytes();
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc instanceof NotificationEmitter) {
                final NotificationEmitter emitter = (NotificationEmitter) gc;
                emitter.addNotificationListener(listener, null, null);
                emitters.add(emitter);
            }
        }
        this.startNanos = System.nanoTime();
    }

    public static MemoryProbe start() {
        final Set<String> heapNames = new HashSet<>();
        final List<MemoryPoolMXBean> old = new ArrayList<>();
        final List<MemoryPoolMXBean> heap = new ArrayList<>();
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.HEAP || !pool.isValid()) {
                continue;
            }
            heap.add(pool);
            heapNames.add(pool.getName());
            final String name = pool.getName().toLowerCase();
            if (name.contains("old") || name.contains("tenured")) {
                old.add(pool);
            }
        }
        return new MemoryProbe(old.isEmpty() ? heap : old, heapNames);
    }

    private void onNotification(final Notification notification, final Object handback) {
        if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())) {
            return;
        }
        final GarbageCollectionNotificationInfo info =
                GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
        long used = 0;
        for (Map.Entry<String, MemoryUsage> e : info.getGcInfo().getMemoryUsageAfterGc().entrySet()) {
            if (heapPoolNames.contains(e.getKey())) {
                used += e.getValue().getUsed();
            }
        }
        synchronized (lock) {
            maxAfterGcBytes = Math.max(maxAfterGcBytes, used);
            notificationsSeen++;
            lock.notifyAll();
        }
    }

    /** Ends the window, removes the GC listeners and returns the readings. */
    public Reading stop() {
        final long wallMillis = Math.max(1L, (System.nanoTime() - startNanos) / 1_000_000L);
        final long allocBytes = totalAllocatedBytes() - startAllocBytes;
        final long gcMillis = totalGcMillis() - startGcMillis;
        final long expected = totalGcCount() - startGcCount;

        // Notifications are delivered asynchronously: give those of GCs inside the window time to arrive.
        final long deadline = System.nanoTime() + NOTIFICATION_WAIT_MS * 1_000_000L;
        synchronized (lock) {
            while (notificationsSeen < expected) {
                final long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
                if (remainingMs <= 0) {
                    break;
                }
                try {
                    lock.wait(Math.min(remainingMs, 50L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        for (NotificationEmitter emitter : emitters) {
            try {
                emitter.removeNotificationListener(listener);
            } catch (ListenerNotFoundException ignored) {
                // already removed
            }
        }
        emitters.clear();

        final long afterGc;
        synchronized (lock) {
            afterGc = maxAfterGcBytes;
        }
        final boolean fromGc = afterGc >= 0;
        long peakBytes = afterGc;
        if (!fromGc) {
            peakBytes = 0;
            for (MemoryPoolMXBean pool : oldPools) {
                peakBytes += pool.getPeakUsage().getUsed();
            }
        }
        return new Reading(peakBytes / MB, fromGc, allocBytes / MB, gcMillis, (double) gcMillis / wallMillis);
    }

    private static long totalGcCount() {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            final long c = gc.getCollectionCount();
            if (c > 0) {
                total += c;
            }
        }
        return total;
    }

    private static long totalGcMillis() {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            final long t = gc.getCollectionTime();
            if (t > 0) {
                total += t;
            }
        }
        return total;
    }

    private static long totalAllocatedBytes() {
        final java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (bean instanceof com.sun.management.ThreadMXBean) {
            return ((com.sun.management.ThreadMXBean) bean).getTotalThreadAllocatedBytes();
        }
        return 0L;
    }
}
