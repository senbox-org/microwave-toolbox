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
package eu.esa.sar.calibration.gpf.support;

import eu.esa.sar.commons.Sentinel1Utils;
import org.esa.snap.core.util.SystemUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Builds Sentinel-1 2-D thermal noise LUTs (IPF 2.9 and later, TOPS) from parsed noise annotation
 * (ESA MPC-0392 section 6.3): per azimuth block, range vectors interpolated in range and in azimuth time,
 * multiplied by the interpolated azimuth vector. Immutable after construction; safe for concurrent tiles.
 * A value of 0 in the result means the noise is unknown.
 */
public final class S1NoiseLutBuilder {

    private final Sentinel1Utils.NoiseVector[] rangeVectors;
    private final Sentinel1Utils.NoiseAzimuthVector[] azimuthVectors;
    private final double firstLineTimeMJD;
    private final double lineTimeIntervalDays;
    private final Map<String, double[]> swathStartEndTimesMJD;
    private final Map<String, Double> swathFactors;
    private final AtomicBoolean warnedDuplicateIndex = new AtomicBoolean();
    private final AtomicBoolean warnedNoRangeVector = new AtomicBoolean();
    private final AtomicBoolean warnedShortRangeVector = new AtomicBoolean();

    /**
     * @param rangeVectors          noise range vectors of one polarisation (GRD) or one swath+polarisation (SLC)
     * @param azimuthVectors        noise azimuth vectors (one per swath block)
     * @param firstLineTimeMJD      time of image line 0
     * @param lineTimeIntervalDays  azimuth time interval in days
     * @param swathStartEndTimesMJD per swath, {start, end} time of the swath in the merged GRD image
     */
    public S1NoiseLutBuilder(final Sentinel1Utils.NoiseVector[] rangeVectors,
                             final Sentinel1Utils.NoiseAzimuthVector[] azimuthVectors,
                             final double firstLineTimeMJD, final double lineTimeIntervalDays,
                             final Map<String, double[]> swathStartEndTimesMJD) {
        this.rangeVectors = rangeVectors;
        this.azimuthVectors = azimuthVectors;
        this.firstLineTimeMJD = firstLineTimeMJD;
        this.lineTimeIntervalDays = lineTimeIntervalDays;
        this.swathStartEndTimesMJD = swathStartEndTimesMJD;
        this.swathFactors = Collections.emptyMap();
    }

    /**
     * As above, with a linear factor per swath applied to the noise of that swath's blocks
     * (e.g. the noise retro-calibration of {@link S1NoiseRecalibration}).
     */
    public S1NoiseLutBuilder(final Sentinel1Utils.NoiseVector[] rangeVectors,
                             final Sentinel1Utils.NoiseAzimuthVector[] azimuthVectors,
                             final double firstLineTimeMJD, final double lineTimeIntervalDays,
                             final Map<String, double[]> swathStartEndTimesMJD,
                             final Map<String, Double> swathFactors) {
        this.rangeVectors = rangeVectors;
        this.azimuthVectors = azimuthVectors;
        this.firstLineTimeMJD = firstLineTimeMJD;
        this.lineTimeIntervalDays = lineTimeIntervalDays;
        this.swathStartEndTimesMJD = swathStartEndTimesMJD;
        this.swathFactors = swathFactors;
    }

    /** Key of the factor used for azimuth blocks that carry no swath annotation. */
    public static final String ANY_SWATH = "*";

    private double swathFactor(final String swath) {
        return swathFactors.getOrDefault(swath == null ? ANY_SWATH : swath, swathFactors.getOrDefault(ANY_SWATH, 1.0));
    }

    /** GRD: noise matrix [h][w] for full-image tile origin (x0, y0); 0 = unknown. */
    public double[][] buildGRD(final int x0, final int y0, final int w, final int h) {
        final int xMax = x0 + w - 1;
        final int yMax = y0 + h - 1;
        final double[][] noiseMatrix = new double[h][w];
        for (Sentinel1Utils.NoiseAzimuthVector noiseAzimuthVector : azimuthVectors) {
            final int nx0 = Math.max(x0, noiseAzimuthVector.firstRangeSample);
            final int nxMax = Math.min(xMax, noiseAzimuthVector.lastRangeSample);
            final int ny0 = Math.max(y0, noiseAzimuthVector.firstAzimuthLine);
            final int nyMax = Math.min(yMax, noiseAzimuthVector.lastAzimuthLine);

            if (nx0 > nxMax || ny0 > nyMax) {     // block bounds are inclusive
                continue;
            }

            populateNoiseMatrixForTOPSGRD(noiseAzimuthVector, x0, y0, nx0, nxMax, ny0, nyMax, noiseMatrix);
        }
        return noiseMatrix;
    }

    /**
     * TOPS SLC: noise matrix [h][w] for full-image tile origin (x0, y0), selecting each burst's range vector by
     * its annotated line (used when no burst times are available).
     */
    public double[][] buildTopsSLC(final int x0, final int y0, final int w, final int h, final int linesPerBurst) {
        return buildTopsSLC(x0, y0, w, h, linesPerBurst, null, 0);
    }

    /**
     * TOPS SLC: noise matrix [h][w] for full-image tile origin (x0, y0). The range vector of each burst is the one
     * annotated nearest in azimuth time to the burst's azimuthTime (MPC-0392 section 6.3: before IPF 3.71 the
     * annotated line may be shifted, the time is authoritative). The azimuth vector is evaluated at the image line.
     *
     * @param burstTimesMJD  azimuthTime of the bursts in the product's burst list (null: select by annotated line)
     * @param firstBurstLine image line of the first burst in that list (the subset offset of a TOPSAR-Split product
     *                       whose burst list was trimmed to the selected bursts, otherwise 0)
     */
    public double[][] buildTopsSLC(final int x0, final int y0, final int w, final int h, final int linesPerBurst,
                                   final double[] burstTimesMJD, final int firstBurstLine) {
        if (azimuthVectors.length == 0) {
            throw new IllegalStateException("No noise azimuth vector annotated for the TOPS SLC swath");
        }
        final double[][] noiseMatrix = new double[h][w];
        final Sentinel1Utils.NoiseAzimuthVector noiseAzimuthVector = azimuthVectors[0];
        final double factor = swathFactor(noiseAzimuthVector.swath);

        final int xMax = x0 + w - 1;
        final int yMax = y0 + h - 1;

        final double[] interpolatedAzimuthVector = new double[h];
        interpolNoiseAzimuthVector(noiseAzimuthVector, y0, yMax, interpolatedAzimuthVector);
        for (int i = 0; i < h; i++) {
            interpolatedAzimuthVector[i] *= factor;
        }

        int currentVectorIdx = -1;
        final double[] interpolatedRangeVector = new double[w];

        for (int y = y0; y <= yMax; ++y) {
            final int yy = y - y0;
            final int vectorIdx;
            if (burstTimesMJD != null && burstTimesMJD.length > 0) {
                final int burstIdx = Math.floorDiv(y - firstBurstLine, linesPerBurst);
                if (burstIdx < 0 || burstIdx >= burstTimesMJD.length) {
                    throw new IllegalStateException("Line " + y + " lies outside the " + burstTimesMJD.length +
                            " bursts starting at line " + firstBurstLine);
                }
                vectorIdx = nearestRangeVectorTime(burstTimesMJD[burstIdx]);
            } else {
                vectorIdx = nearestRangeVectorLine((y / linesPerBurst) * linesPerBurst);
            }
            if (vectorIdx != currentVectorIdx) {
                currentVectorIdx = vectorIdx;
                interpolNoiseRangeVector(rangeVectors[vectorIdx], x0, xMax, interpolatedRangeVector);
            }

            for (int x = x0; x <= xMax; ++x) {
                final int xx = x - x0;
                noiseMatrix[yy][xx] = interpolatedAzimuthVector[yy] * interpolatedRangeVector[xx];
            }
        }
        return noiseMatrix;
    }

    private int nearestRangeVectorTime(final double timeMJD) {
        int closest = 0;
        for (int j = 1; j < rangeVectors.length; j++) {
            if (Math.abs(timeMJD - rangeVectors[j].timeMJD) < Math.abs(timeMJD - rangeVectors[closest].timeMJD)) {
                closest = j;
            }
        }
        return closest;
    }

    private int nearestRangeVectorLine(final int line) {
        int closest = 0;
        for (int j = 1; j < rangeVectors.length; j++) {
            if (Math.abs(line - rangeVectors[j].line) < Math.abs(line - rangeVectors[closest].line)) {
                closest = j;
            }
        }
        return closest;
    }

    private void populateNoiseMatrixForTOPSGRD(final Sentinel1Utils.NoiseAzimuthVector noiseAzimuthVector,
                                               final int x0, final int y0,
                                               final int nx0, final int nxMax, final int ny0, final int nyMax,
                                               final double[][] noiseMatrix) {

        final double startAzimTime = firstLineTimeMJD + noiseAzimuthVector.firstAzimuthLine * lineTimeIntervalDays;
        final double endAzimTime = firstLineTimeMJD + noiseAzimuthVector.lastAzimuthLine * lineTimeIntervalDays;

        final int[] noiseRangeVecIndices = getNoiseRangeVectorIndices(noiseAzimuthVector.swath, startAzimTime, endAzimTime);

        if (noiseRangeVecIndices != null && noiseRangeVecIndices.length > 0) {

            final double[][] interpolatedRangeVectors = new double[noiseRangeVecIndices.length][nxMax - nx0 + 1];
            final double[] noiseRangeVectorAzTime = new double[noiseRangeVecIndices.length];
            for (int j = 0; j < noiseRangeVecIndices.length; j++) {
                noiseRangeVectorAzTime[j] = rangeVectors[noiseRangeVecIndices[j]].timeMJD;
                interpolNoiseRangeVector(rangeVectors[noiseRangeVecIndices[j]], nx0, nxMax, interpolatedRangeVectors[j]);
            }

            final double[] interpolatedAzimuthVector = new double[nyMax - ny0 + 1];
            interpolNoiseAzimuthVector(noiseAzimuthVector, ny0, nyMax, interpolatedAzimuthVector);
            final double factor = swathFactor(noiseAzimuthVector.swath);
            for (int i = 0; i < interpolatedAzimuthVector.length; i++) {
                interpolatedAzimuthVector[i] *= factor;
            }

            computeNoiseMatrix(x0, y0, nx0, nxMax, ny0, nyMax, noiseRangeVectorAzTime, interpolatedRangeVectors,
                    interpolatedAzimuthVector, noiseMatrix);

        } else {
            for (int y = ny0; y <= nyMax; y++) {
                for (int x = nx0; x <= nxMax; x++) {
                    noiseMatrix[y - y0][x - x0] = 0.0;
                }
            }
        }
    }

    private void interpolNoiseRangeVector(final Sentinel1Utils.NoiseVector noiseRangeVector,
                                                 final int firstRangeSample, final int lastRangeSample,
                                                 final double[] result) {
        if (noiseRangeVector.pixels.length < 2) {  // should never happen
            warnOnce(warnedShortRangeVector, "noise range vector has length 1");
            for (int sample = 0; sample < result.length; sample++) {
                result[sample] = noiseRangeVector.noiseLUT[0];
            }
        } else {
            int i = 0;
            int sampleIdx = getSampleIndex(firstRangeSample, noiseRangeVector);
            for (int sample = firstRangeSample; sample <= lastRangeSample; sample++) {
                if (sample > noiseRangeVector.pixels[sampleIdx + 1]
                        && sampleIdx < noiseRangeVector.pixels.length - 2) {
                    sampleIdx++;
                }

                result[i++] = interpol(noiseRangeVector.pixels[sampleIdx],
                        noiseRangeVector.pixels[sampleIdx + 1],
                        noiseRangeVector.noiseLUT[sampleIdx],
                        noiseRangeVector.noiseLUT[sampleIdx + 1], sample);
            }
        }
    }

    private void interpolNoiseAzimuthVector(final Sentinel1Utils.NoiseAzimuthVector noiseAzimuthVector,
                                                   final int firstAzimuthLine, final int lastAzimuthLine,
                                                   final double[] interpNoiseAzimVec) {
        if (noiseAzimuthVector.lines.length < 2) { // This is possible
            for (int line = firstAzimuthLine; line <= lastAzimuthLine; line++) {
                interpNoiseAzimVec[line - firstAzimuthLine] = noiseAzimuthVector.noiseAzimuthLUT[0];
            }
        } else {
            int lineIdx = getLineIndex(firstAzimuthLine, noiseAzimuthVector.lines);
            for (int line = firstAzimuthLine; line <= lastAzimuthLine; line++) {

                if (line > noiseAzimuthVector.lines[lineIdx + 1] && lineIdx < noiseAzimuthVector.lines.length - 2) {
                    lineIdx++;
                }

                interpNoiseAzimVec[line - firstAzimuthLine] = interpol(
                        noiseAzimuthVector.lines[lineIdx],
                        noiseAzimuthVector.lines[lineIdx + 1],
                        noiseAzimuthVector.noiseAzimuthLUT[lineIdx],
                        noiseAzimuthVector.noiseAzimuthLUT[lineIdx + 1],
                        line);
            }
        }
    }

    private void computeNoiseMatrix(final int x0, final int y0, final int nx0, final int nxMax, final int ny0,
                                    final int nyMax, final double[] noiseRangeVectorAzTime,
                                    final double[][] interpolatedRangeVectors,
                                    final double[] interpolatedAzimuthVector, final double[][] noiseMatrix) {

        if (noiseRangeVectorAzTime.length == 1) {
            for (int x = nx0; x <= nxMax; x++) {
                final int xx = x - nx0;
                for (int y = ny0; y <= nyMax; y++) {
                    noiseMatrix[y - y0][x - x0] = interpolatedAzimuthVector[y - ny0] * interpolatedRangeVectors[0][xx];
                }
            }

        } else {

            final double time0 = firstLineTimeMJD + lineTimeIntervalDays * ny0;
            final int line0Idx = getLineIndexByTime(time0, noiseRangeVectorAzTime);

            for (int x = nx0; x <= nxMax; x++) {
                final int xx = x - nx0;
                int lineIdx = line0Idx;

                for (int y = ny0; y <= nyMax; y++) {
                    final double time = firstLineTimeMJD + lineTimeIntervalDays * y;
                    if (time > noiseRangeVectorAzTime[lineIdx + 1] && lineIdx < noiseRangeVectorAzTime.length - 2) {
                        lineIdx++;
                    }

                    noiseMatrix[y - y0][x - x0] = interpolatedAzimuthVector[y - ny0] *
                            interpolByTime(noiseRangeVectorAzTime[lineIdx], noiseRangeVectorAzTime[lineIdx + 1],
                                    interpolatedRangeVectors[lineIdx][xx], interpolatedRangeVectors[lineIdx + 1][xx], time);
                }
            }
        }
    }

    private double interpol(final int x1, final int x2, final double y1, final double y2, final int x) {
        if (x1 == x2) { // should never happen
            warnOnce(warnedDuplicateIndex, "noise vector duplicate indices: x1 == x2  = " + x1);
            return 0;
        }
        return y1 + ((double) (x - x1) / (double) (x2 - x1)) * (y2 - y1);
    }

    /** Linear in time between two range vectors; beyond them the nearest vector is used (no extrapolation). */
    private double interpolByTime(final double t1, final double t2, final double y1, final double y2,
                                         final double t) {
        if (t1 == t2) { // should never happen
            warnOnce(warnedDuplicateIndex, "noise vector duplicate indices: t1 == t2  = " + t1);
            return 0.0;
        }
        final double mu = Math.max(0.0, Math.min(1.0, (t - t1) / (t2 - t1)));
        return y1 + mu * (y2 - y1);
    }

    /** Log a warning once per builder: the builder is called for every tile, possibly from many threads. */
    private static void warnOnce(final AtomicBoolean warned, final String message) {
        if (warned.compareAndSet(false, true)) {
            SystemUtils.LOG.warning(message + " (further occurrences not logged)");
        }
    }

    /**
     * Range vectors whose azimuth time lies within [startAzimTime, endAzimTime]. If there is none, the one
     * within the swath start/end times that is closest to the block centre (MPC-0392 section 6.3).
     */
    private int[] getNoiseRangeVectorIndices(final String swath, final double startAzimTime, final double endAzimTime) {

        final List<Integer> list = new ArrayList<>();
        for (int i = 0; i < rangeVectors.length; i++) {
            final double azimTime = rangeVectors[i].timeMJD;
            if (azimTime >= startAzimTime && azimTime <= endAzimTime) {
                list.add(i);
            }
        }

        if (list.isEmpty()) {
            int idx = -1;
            final double[] startEndTimes = swathStartEndTimesMJD.getOrDefault(swath, new double[]{0, 0});
            final double blockCentreTime = (startAzimTime + endAzimTime) / 2.0;
            for (int i = 0; i < rangeVectors.length; i++) {
                final double azimTime = rangeVectors[i].timeMJD;
                if (azimTime >= startEndTimes[0] && azimTime <= startEndTimes[1]) {
                    if (idx < 0) {
                        idx = i;
                    } else if (Math.abs(blockCentreTime - rangeVectors[i].timeMJD) <
                            Math.abs(blockCentreTime - rangeVectors[idx].timeMJD)) {
                        idx = i;
                    }
                }
            }
            if (idx < 0) {
                warnOnce(warnedNoRangeVector, "No valid range vector found for startAzimTime = " + startAzimTime +
                        " endAzimTime = " + endAzimTime + " swath = " + swath);
                return null;
            }
            list.add(idx);
        }

        final int[] indices = new int[list.size()];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = list.get(i);
        }
        return indices;
    }

    private static int getLineIndex(final int line, final int[] lines) {
        //  lines.length is assumed to be >= 2
        for (int i = 0; i < lines.length; i++) {
            if (line < lines[i]) {
                return (i > 0) ? i - 1 : 0;
            }
        }
        return lines.length - 2;
    }

    private static int getLineIndexByTime(final double time, final double[] times) {
        //  times.length is assumed to be >= 2
        for (int i = 0; i < times.length; i++) {
            if (time < times[i]) {
                return (i > 0) ? i - 1 : 0;
            }
        }
        return times.length - 2;
    }

    private static int getSampleIndex(final int sample, final Sentinel1Utils.NoiseVector noiseRangeVector) {
        for (int i = 0; i < noiseRangeVector.pixels.length; i++) {
            if (sample < noiseRangeVector.pixels[i]) {
                return (i > 0) ? i - 1 : 0;
            }
        }
        return noiseRangeVector.pixels.length - 2;
    }
}
