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

import org.esa.snap.core.datamodel.MetadataElement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RCM thermal-noise model from noiseLevels_POL.xml (schema rcm_prod_noiseLevels.xsd).
 * <p>
 * The Beta Nought vectors are the reference (the schema defines the beta-nought vector as the noise
 * level used by the processor); sigma/gamma noise are derived by the caller via the incidence angle.
 * ScanSAR: per-beam range vectors are selected through grdBurstMap and multiplied by the per-beam
 * azimuth scaling, centred on the burst middle line (in burst overlaps, the burst with the nearest centre).
 * Spotlight: the azimuth scaling starts at lineFirstNoiseScalingValue; value k applies to image line
 * lineFirstNoiseScalingValue + k * stepSize. The schema gives the sign of stepSize from lineTimeOrdering, the same
 * convention as the range vectors (range sample = pixelFirstNoiseValue + k * stepSize), so a negative step walks
 * up the image from lineFirstNoiseScalingValue. No Spotlight product with a negative step has been validated.
 */
public final class RCMNoiseModel {

    /** Sampled vector with linear interpolation; the step may be negative (decreasing pixel time order). */
    public static final class Vector {
        private final int first;
        private final int step;
        private final double[] values;

        public Vector(final int first, final int step, final double[] values) {
            if (step == 0 || values.length == 0) {
                throw new IllegalArgumentException("invalid noise vector");
            }
            this.first = first;
            this.step = step;
            this.values = values;
        }

        /** Linear interpolation at a pixel/line position, clamped to the vector ends. */
        public double valueAt(final double pos) {
            if (values.length == 1) {
                return values[0];
            }
            final double t = (pos - first) / step;
            if (t <= 0) {
                return values[0];
            }
            if (t >= values.length - 1) {
                return values[values.length - 1];
            }
            final int i = (int) t;
            final double mu = t - i;
            return values[i] + mu * (values[i + 1] - values[i]);
        }
    }

    private static final class Burst {
        final String beam;
        final int top, left, bottom, right;

        Burst(final String beam, final int top, final int left, final int bottom, final int right) {
            this.beam = beam;
            this.top = top;
            this.left = left;
            this.bottom = bottom;
            this.right = right;
        }

        boolean contains(final int x, final int y) {
            return x >= left && x <= right && y >= top && y <= bottom;
        }
    }

    private static final class AzimuthScaling {
        final Vector linear;    // indexed from 0, centre of the vector = burst centre line
        final double centreIndex;
        final int step;
        final Integer lineFirst;   // Spotlight: image line of the first value (no bursts)

        AzimuthScaling(final double[] linear, final int step, final Integer lineFirst) {
            if (step == 0) {
                throw new IllegalArgumentException("invalid azimuth noise scaling step 0");
            }
            this.linear = new Vector(0, 1, linear);
            this.centreIndex = (linear.length - 1) / 2.0;
            this.step = step;
            this.lineFirst = lineFirst;
        }

        double at(final int lineFromBurstCentre) {
            return linear.valueAt(centreIndex + (double) lineFromBurstCentre / step);
        }

        /** Spotlight: value k at image line lineFirst + k * step (step signed by lineTimeOrdering). */
        double atImageLine(final int line) {
            return linear.valueAt((double) (line - lineFirst) / step);
        }
    }

    private final Vector reference;
    private final Map<String, Vector> perBeam;
    private final Map<String, AzimuthScaling> azimuthScaling;
    private final List<Burst> bursts;
    private final Burst[][] burstsByLine;    // bursts covering each image line, for fast lookup
    private final AzimuthScaling spotlight;   // product without bursts: the scaling with a first line, or null
    private final Vector gain;
    private final boolean isSLC;

    private RCMNoiseModel(final Vector reference, final Map<String, Vector> perBeam,
                          final Map<String, AzimuthScaling> azimuthScaling, final List<Burst> bursts,
                          final Vector gain, final boolean isSLC) {
        this.reference = reference;
        this.perBeam = perBeam;
        this.azimuthScaling = azimuthScaling;
        this.bursts = bursts;
        this.burstsByLine = indexByLine(bursts);
        this.spotlight = bursts.isEmpty() ? spotlightScaling(azimuthScaling) : null;
        this.gain = gain;
        this.isSLC = isSLC;
    }

    /**
     * @param noiseLevels  the noiseLevels_POL element
     * @param lutBeta      the lutBeta_POL element
     * @param grdBurstMap  product/grdBurstMap (ScanSAR GRD) or null
     * @param isSLC        selects the SLC calibration convention (beta = DN^2/A^2) instead of GRD (beta = DN^2/A)
     */
    public static RCMNoiseModel parse(final MetadataElement noiseLevels, final MetadataElement lutBeta,
                                      final MetadataElement grdBurstMap, final boolean isSLC) {
        Vector reference = null;
        final Map<String, Vector> perBeam = new HashMap<>();
        final Map<String, AzimuthScaling> azScaling = new HashMap<>();

        for (MetadataElement e : noiseLevels.getElements()) {
            final String name = e.getName();
            if (name.equals("referenceNoiseLevel") && isBeta(e)) {
                reference = readNoiseVector(e);
            } else if (name.equals("perBeamReferenceNoiseLevel") && isBeta(e)) {
                perBeam.put(e.getAttributeString("beam"), readNoiseVector(e));
            } else if (name.equals("azimuthNoiseLevelScaling")) {
                final Integer lineFirst = e.containsAttribute("lineFirstNoiseScalingValue") ?
                        intAttr(e, "lineFirstNoiseScalingValue") : null;
                azScaling.put(e.getAttributeString("beam"), new AzimuthScaling(
                        dBToLinear(parseValues(e, "noiseLevelScalingValues")), intAttr(e, "stepSize"), lineFirst));
            }
        }
        if (reference == null && perBeam.isEmpty()) {
            throw new IllegalArgumentException("RCM noise levels contain no Beta Nought vector");
        }

        final List<Burst> bursts = new ArrayList<>();
        if (grdBurstMap != null) {
            for (MetadataElement b : grdBurstMap.getElements()) {
                if (b.getName().equals("burstAttributes")) {
                    bursts.add(new Burst(b.getAttributeString("beam"),
                            intAttr(b, "topLeftLine"), intAttr(b, "topLeftPixel"),
                            intAttr(b, "bottomRightLine"), intAttr(b, "bottomRightPixel")));
                }
            }
        }

        final Vector gain = new Vector(intAttr(lutBeta, "pixelFirstLutValue"), intAttr(lutBeta, "stepSize"),
                parseNumbers(lutBeta.getAttributeString("gains")));

        return new RCMNoiseModel(reference, perBeam, azScaling, bursts, gain, isSLC);
    }

    /** Linear beta-nought noise at full-image pixel (x, y); 0 means unknown. */
    public double betaNoise(final int x, final int y) {
        // in burst overlaps, use the burst whose centre line is nearest
        Burst burst = null;
        if (y >= 0 && y < burstsByLine.length) {
            for (Burst b : burstsByLine[y]) {
                if (b.contains(x, y) && (burst == null ||
                        Math.abs(2 * y - b.top - b.bottom) < Math.abs(2 * y - burst.top - burst.bottom))) {
                    burst = b;
                }
            }
        }
        if (burst == null) {
            if (reference == null) {
                return 0.0;
            }
            final double n = reference.valueAt(x);
            return spotlight != null ? n * spotlight.atImageLine(y) : n;
        }
        final Vector range = perBeam.getOrDefault(burst.beam, reference);
        double n = range != null ? range.valueAt(x) : 0.0;
        final AzimuthScaling scale = azimuthScaling.get(burst.beam);
        if (scale != null && n != 0.0) {
            n *= scale.at(y - (burst.top + burst.bottom) / 2);
        }
        return n;
    }

    /**
     * The azimuth scaling of a product without bursts (Spotlight: lineFirstNoiseScalingValue given), or null.
     * Spotlight is single-beam; several such vectors cannot be assigned to image lines and are refused.
     */
    private static AzimuthScaling spotlightScaling(final Map<String, AzimuthScaling> azimuthScaling) {
        AzimuthScaling found = null;
        for (Map.Entry<String, AzimuthScaling> e : azimuthScaling.entrySet()) {
            if (e.getValue().lineFirst != null) {
                if (found != null) {
                    throw new IllegalArgumentException("RCM noise levels contain several azimuth noise scaling vectors " +
                            "with lineFirstNoiseScalingValue and no burst map; cannot choose one");
                }
                found = e.getValue();
            }
        }
        return found;
    }

    /**
     * Product types whose calibration is |DN|^2 / A^2 (RCM-SP-53-0419 section 7.5.1: "complex products including
     * SLC and GRC"; GCC is the geocoded complex type). Detected products (GRD, GCD) use (DN^2 + B) / A, MLC DN^2 / A.
     * Shared by the noise removal and RCMCalibrator so both use the same convention.
     */
    public static boolean isComplexProductType(final String productType) {
        if (productType == null) {
            return false;
        }
        final String t = productType.trim().toUpperCase();
        return t.contains("SLC") || t.equals("GRC") || t.equals("GCC");
    }

    /** For each line, the bursts covering it, in burst-map order (so the first match is unchanged). */
    private static Burst[][] indexByLine(final List<Burst> bursts) {
        int maxLine = -1;
        for (Burst b : bursts) {
            maxLine = Math.max(maxLine, b.bottom);
        }
        final List<List<Burst>> lists = new ArrayList<>(maxLine + 1);
        for (int i = 0; i <= maxLine; i++) {
            lists.add(new ArrayList<>(4));
        }
        for (Burst b : bursts) {
            for (int y = Math.max(0, b.top); y <= b.bottom; y++) {
                lists.get(y).add(b);
            }
        }
        final Burst[][] index = new Burst[maxLine + 1][];
        for (int i = 0; i <= maxLine; i++) {
            index[i] = lists.get(i).toArray(new Burst[0]);
        }
        return index;
    }

    /** Noise in |DN|^2 units, matching RCMCalibrator (GRD: beta = DN^2/A, SLC: beta = DN^2/A^2). */
    public double noiseDN2(final int x, final int y) {
        final double a = gain.valueAt(x);
        return betaNoise(x, y) * (isSLC ? a * a : a);
    }

    private static boolean isBeta(final MetadataElement e) {
        return "Beta Nought".equalsIgnoreCase(e.getAttributeString("sarCalibrationType", ""));
    }

    private static Vector readNoiseVector(final MetadataElement e) {
        return new Vector(intAttr(e, "pixelFirstNoiseValue"), intAttr(e, "stepSize"),
                dBToLinear(parseValues(e, "noiseLevelValues")));
    }

    private static int intAttr(final MetadataElement e, final String name) {
        return Integer.parseInt(e.getAttributeString(name).trim());
    }

    private static double[] dBToLinear(final double[] dB) {
        final double[] lin = new double[dB.length];
        for (int i = 0; i < dB.length; i++) {
            lin[i] = Math.pow(10.0, dB[i] / 10.0);
        }
        return lin;
    }

    /** Values are either a child element (leaf with a units attribute) or a plain attribute. */
    private static double[] parseValues(final MetadataElement e, final String name) {
        final MetadataElement child = e.getElement(name);
        final String s = child != null ? child.getAttributeString(name) : e.getAttributeString(name);
        return parseNumbers(s);
    }

    private static double[] parseNumbers(final String s) {
        final String[] tok = s.trim().split("\\s+");
        final double[] v = new double[tok.length];
        for (int i = 0; i < tok.length; i++) {
            v[i] = Double.parseDouble(tok[i]);
            if (!Double.isFinite(v[i])) {
                throw new IllegalArgumentException("non-finite value '" + tok[i] + "' in RCM noise or gain list");
            }
        }
        return v;
    }
}
