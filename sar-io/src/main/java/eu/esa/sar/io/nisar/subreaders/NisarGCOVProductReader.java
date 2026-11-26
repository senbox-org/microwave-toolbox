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
package eu.esa.sar.io.nisar.subreaders;

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.VirtualBand;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import ucar.nc2.Group;
import ucar.nc2.Variable;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * NISAR L2 GCOV (geocoded polarimetric covariance) reader.
 * <p>
 * Each covariance term XYZW = &lt;S_XY S_ZW*&gt; becomes a band named after the term. Diagonal terms
 * (XY == ZW) are real backscatter power. Off-diagonal terms are complex and are split into
 * {@code <term>_real} and {@code <term>_imag}. Fill pixels are NaN. The per-pixel mask, number of
 * looks and RTC gamma0-to-sigma0 factor are exposed as extra bands.
 */
public class NisarGCOVProductReader extends NisarSubReader {

    /** Used when a product has no listOfCovarianceTerms: every term NISAR can produce. */
    private static final String[] ALL_TERMS = {
            "HHHH", "HVHV", "VHVH", "VVVV", "RHRH", "RVRV", "LHLH", "LVLV",
            "HHHV", "HHVH", "HHVV", "HVVH", "HVVV", "VHVV", "RHRV", "LHLV"};

    /** Co-pol and cross-pol channel of the dual-pol C2 cases. */
    private static final String[][] DUAL_POL_PAIRS = {{"HH", "HV"}, {"VV", "VH"}, {"HH", "VH"}, {"VV", "HV"}};

    /** compact_mode values, as in rstb's CompactPolProcessor; the transmit chirality is the first letter of the term. */
    private static final String RIGHT_CIRCULAR_HYBRID = "Right Circular Hybrid Mode";
    private static final String LEFT_CIRCULAR_HYBRID = "Left Circular Hybrid Mode";

    /** Single-polarisation layers some products carry instead of covariance terms. */
    private static final String[] SINGLE_POL_LAYERS = {"HH", "HV", "VH", "VV"};

    private static final String[][] AUX_LAYERS = {
            // HDF5 name, band name
            {"mask", "mask"},
            {"numberOfLooks", "numberOfLooks"},
            {"rtcGammaToSigmaFactor", "rtcGamma0ToSigma0Factor"},
            // name used by pre-launch sample products
            {"rtcAreaNormalizationFactorGamma0ToSigma0", "rtcGamma0ToSigma0Factor"},
            {"inputDataExceptionMask", "inputDataExceptionMask"},
    };

    /** Layers holding codes or bit flags rather than measurements; no-data handling is left to the user. */
    private static final Set<String> CODE_LAYERS = Set.of("mask", "inputDataExceptionMask");

    private static final String VALID_MASK = "gcov_valid";

    public NisarGCOVProductReader() {
        productType = "GCOV";
    }

    @Override
    protected Group getFrequencyAGroup(final Group groupLSAR) {
        return getGridGroup(groupLSAR, "frequencyA");
    }

    @Override
    protected Group getFrequencyBGroup(final Group groupLSAR) {
        return getGridGroup(groupLSAR, "frequencyB");
    }

    private Group getGridGroup(final Group groupSAR, final String frequency) {
        final Group groupProductType = groupSAR.findGroup(productType);
        final Group groupGrids = groupProductType == null ? null : groupProductType.findGroup("grids");
        return groupGrids == null ? null : groupGrids.findGroup(frequency);
    }

    @Override
    protected Variable[] getRasterVariables(final Group groupFrequency) {
        final List<Variable> rasterVariables = new ArrayList<>();
        for (String term : getLayerNames(groupFrequency)) {
            final Variable v = groupFrequency.findVariable(term);
            if (v != null) {
                rasterVariables.add(v);
            }
        }
        return rasterVariables.toArray(new Variable[0]);
    }

    @Override
    protected void addBandsForFrequency(final Group groupFrequency, final String suffix) {
        for (String term : getLayerNames(groupFrequency)) {
            final Variable variable = groupFrequency.findVariable(term);
            if (variable == null) {
                continue;
            }
            if (isOffDiagonal(term)) {
                addBand(variable, term + "_real" + suffix, Unit.REAL,
                        "<S_" + term.substring(0, 2) + " S_" + term.substring(2) + "*>, real part");
                addBand(variable, term + "_imag" + suffix, Unit.IMAGINARY,
                        "<S_" + term.substring(0, 2) + " S_" + term.substring(2) + "*>, imaginary part");
            } else {
                addBand(variable, term + suffix, Unit.INTENSITY, "Backscatter power " + term);
            }
        }

        for (String[] aux : AUX_LAYERS) {
            final Variable variable = groupFrequency.findVariable(aux[0]);
            if (variable != null && variable.getRank() == 2 && product.getBand(aux[1] + suffix) == null) {
                final Band band = addBand(variable, aux[1] + suffix, null, variable.getDescription());
                if (CODE_LAYERS.contains(aux[0])) {
                    band.setNoDataValueUsed(false);
                }
            }
        }
        addValidMask(suffix);

        // Matrix bands for frequency A only: PolBandUtils finds them by name, so a second set would be ambiguous.
        if (suffix.isEmpty()) {
            final Set<String> terms = new HashSet<>();
            for (String term : getLayerNames(groupFrequency)) {
                if (groupFrequency.findVariable(term) != null) {
                    terms.add(term);
                }
            }
            addPolarimetricMatrixBands(terms);
        }
    }

    /**
     * A {@code gcov_valid} mask of the samples the product marks valid: mask 1..n is the subswath of a
     * valid sample, 0 a partially focused or invalid one, 255 outside the swath. Not set as the bands'
     * valid-pixel expression: mask-0 samples hold data, about a fifth of the footprint in current products.
     */
    private void addValidMask(final String suffix) {
        final String maskBand = "mask" + suffix;
        if (product.getBand(maskBand) != null && !product.getMaskGroup().contains(VALID_MASK + suffix)) {
            product.addMask(VALID_MASK + suffix, maskBand + " > 0 && " + maskBand + " < 255",
                    "Valid GCOV samples (mask 1..n)", Color.GREEN, 0.5);
        }
    }

    /**
     * Virtual C3 (quad-pol) or C2 (dual- and compact-pol) bands from the covariance terms, named as
     * PolBandUtils expects, and the matching abstracted metadata, so the polarimetric operators accept
     * the product. Products without off-diagonal terms get none.
     */
    private void addPolarimetricMatrixBands(final Set<String> terms) {
        if (addC3Bands(terms)) {
            setPolsarMetadata(null);
            return;
        }
        // A quad-pol product whose terms do not make a C3 gets no matrix: a C2 of two of its channels
        // would present it to the operators as dual-pol.
        if (terms.contains("HHHH") && terms.contains("VVVV") && !present(terms, "HVHV", "VHVH").isEmpty()) {
            SystemUtils.LOG.warning("NISAR GCOV: quad-pol terms " + terms + " do not make a C3 matrix; no matrix bands");
            return;
        }
        for (String[] pair : DUAL_POL_PAIRS) {
            if (addC2Bands(terms, pair[0], pair[1])) {
                setPolsarMetadata(null);
                return;
            }
        }
        for (String tx : new String[]{"R", "L"}) {
            if (addC2Bands(terms, tx + "H", tx + "V")) {
                setPolsarMetadata(tx.equals("R") ? RIGHT_CIRCULAR_HYBRID : LEFT_CIRCULAR_HYBRID);
                return;
            }
        }
    }

    /**
     * Quad-pol. SNAP's C3 is on k = [S_HH, sqrt2 S_X, S_VV]. NISAR products are not cross-pol
     * symmetrised, so S_X = (S_HV + S_VH) / 2 where both are present:
     * C12 = sqrt2 &lt;S_HH S_X*&gt;, C22 = 2 &lt;|S_X|^2&gt; = (HVHV + VHVH + 2 Re HVVH) / 2, C23 = sqrt2 &lt;S_X S_VV*&gt;.
     * A product with only one of HV, VH uses it for S_X.
     */
    private boolean addC3Bands(final Set<String> terms) {
        final List<String> c12 = present(terms, "HHHV", "HHVH");
        final List<String> c23 = present(terms, "HVVV", "VHVV");
        final List<String> crossPower = present(terms, "HVHV", "VHVH");
        if (!terms.containsAll(List.of("HHHH", "HHVV", "VVVV")) || c12.isEmpty() || c23.isEmpty()
                || crossPower.isEmpty() || (crossPower.size() == 2 && !terms.contains("HVVH"))) {
            return false;
        }
        final String c22 = crossPower.size() == 2 ? "(HVHV + VHVH + 2 * HVVH_real) / 2" : "2 * " + crossPower.get(0);

        addMatrixBand("C11", "HHHH", Unit.INTENSITY, "C3 element from GCOV HHHH");
        addMatrixBand("C12_real", sqrt2Mean(c12, "_real"), Unit.REAL, "C3 element from GCOV " + c12);
        addMatrixBand("C12_imag", sqrt2Mean(c12, "_imag"), Unit.IMAGINARY, "C3 element from GCOV " + c12);
        addMatrixBand("C13_real", "HHVV_real", Unit.REAL, "C3 element from GCOV HHVV");
        addMatrixBand("C13_imag", "HHVV_imag", Unit.IMAGINARY, "C3 element from GCOV HHVV");
        addMatrixBand("C22", c22, Unit.INTENSITY, "C3 element from GCOV " + c22);
        addMatrixBand("C23_real", sqrt2Mean(c23, "_real"), Unit.REAL, "C3 element from GCOV " + c23);
        addMatrixBand("C23_imag", sqrt2Mean(c23, "_imag"), Unit.IMAGINARY, "C3 element from GCOV " + c23);
        addMatrixBand("C33", "VVVV", Unit.INTENSITY, "C3 element from GCOV VVVV");
        return true;
    }

    /** sqrt2 times the mean of the given terms' real or imaginary parts. */
    private static String sqrt2Mean(final List<String> terms, final String part) {
        final StringBuilder sum = new StringBuilder();
        for (String t : terms) {
            sum.append(sum.length() == 0 ? "" : " + ").append(t).append(part);
        }
        return Math.sqrt(2) / terms.size() + " * (" + sum + ')';
    }

    /**
     * C2 on k = [S_a, S_b] without scaling, as DualPolProcessor and the compact-pol operators use.
     * C12 = &lt;S_a S_b*&gt; is the term ab, or the conjugate of ba.
     */
    private boolean addC2Bands(final Set<String> terms, final String a, final String b) {
        final String aa = a + a, bb = b + b, ab = a + b, ba = b + a;
        if (!terms.contains(aa) || !terms.contains(bb) || !(terms.contains(ab) || terms.contains(ba))) {
            return false;
        }
        final boolean direct = terms.contains(ab);
        addMatrixBand("C11", aa, Unit.INTENSITY, "C2 element from GCOV " + aa);
        addMatrixBand("C12_real", (direct ? ab : ba) + "_real", Unit.REAL,
                "C2 element from GCOV " + (direct ? ab : "conj(" + ba + ')'));
        addMatrixBand("C12_imag", direct ? ab + "_imag" : "-" + ba + "_imag", Unit.IMAGINARY,
                "C2 element from GCOV " + (direct ? ab : "conj(" + ba + ')'));
        addMatrixBand("C22", bb, Unit.INTENSITY, "C2 element from GCOV " + bb);
        return true;
    }

    private static List<String> present(final Set<String> terms, final String... candidates) {
        final List<String> list = new ArrayList<>();
        for (String c : candidates) {
            if (terms.contains(c)) {
                list.add(c);
            }
        }
        return list;
    }

    private void addMatrixBand(final String name, final String expression, final String unit, final String description) {
        final VirtualBand band = new VirtualBand(name, ProductData.TYPE_FLOAT32,
                product.getSceneRasterWidth(), product.getSceneRasterHeight(), expression);
        band.setUnit(unit);
        band.setDescription(description);
        band.setNoDataValue(Double.NaN);
        band.setNoDataValueUsed(true);
        product.addBand(band);
    }

    private void setPolsarMetadata(final String compactMode) {
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
        absRoot.setAttributeInt(AbstractMetadata.polsarData, 1);
        // A covariance-matrix product: the polarimetric operators require COMPLEX, as for RCM MLC.
        absRoot.setAttributeString(AbstractMetadata.SAMPLE_TYPE, "COMPLEX");
        if (compactMode != null) {
            absRoot.setAttributeString(AbstractMetadata.compact_mode, compactMode);
        }
    }

    /**
     * The covariance terms listed by the product, or every known term if it has no list.
     * Single-polarisation layers (HH, ...) follow, for products that carry them.
     */
    private static List<String> getLayerNames(final Group groupFrequency) {
        final List<String> names = new ArrayList<>(readStringList(groupFrequency, "listOfCovarianceTerms"));
        if (names.isEmpty()) {
            names.addAll(List.of(ALL_TERMS));
        }
        names.addAll(List.of(SINGLE_POL_LAYERS));
        return names;
    }

    private static boolean isOffDiagonal(final String term) {
        return term.length() == 4 && !term.substring(0, 2).equals(term.substring(2));
    }

    private Band addBand(final Variable variable, final String bandName, final String unit, final String description) {
        final int height = variable.getDimension(0).getLength();
        final int width = variable.getDimension(1).getLength();

        final Band band = createBand(bandName, width, height, unit, variable);
        band.setDescription(description);
        // GCOV layers use NaN as fill.
        band.setNoDataValue(Double.NaN);
        band.setNoDataValueUsed(true);
        return band;
    }
}
