/*
 * Copyright (C) 2015 by Array Systems Computing Inc. http://www.array.ca
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
package eu.esa.sar.commons.polsar;

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.StackUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper functions for handling polarimetric bands
 */
public class PolBandUtils {

    public enum MATRIX {DUAL_HH_HV, DUAL_VH_VV, DUAL_HH_VV, C2, LCHCP, RCHCP, C3, T3, C4, T4, FULL, UNKNOWN}

    public static class PolSourceBand {
        public final String productName;
        public final Band[] srcBands;
        public final String suffix;
        public Band[] targetBands;

        public double spanMin = 1e+30;
        public double spanMax = -1e+30;
        public boolean spanMinMaxSet = false;

        public PolSourceBand(final String productName, final Band[] bands, final String suffix) {
            this.productName = productName;
            this.srcBands = bands;
            this.suffix = suffix;
        }

        public void addTargetBands(final Band[] targetBands) {
            this.targetBands = targetBands;
        }
    }

    /**
     * Check input product format, get source product type.
     *
     * @param sourceProduct The source product
     * @return The product type
     */
    public static MATRIX getSourceProductType(final Product sourceProduct) {

        final String[] bandNames = sourceProduct.getBandNames();
        boolean isC3 = false, isT3 = false, isC2 = false, isLCHS2 = false, isRCHS2 = false;
        boolean isHH = false, isHV = false, isVV = false, isVH = false;
        for (String name : bandNames) {
            if (name.contains("C44")) {
                return MATRIX.C4;
            } else if (name.contains("T44")) {
                return MATRIX.T4;
            } else if (name.contains("C33")) {
                isC3 = true;
            } else if (name.contains("T33")) {
                isT3 = true;
            } else if (name.contains("C22")) {
                isC2 = true;
            } else if (name.contains("LH") || name.contains("LCH") || name.contains("LCV")) {
                isLCHS2 = true;
            } else if (name.contains("RH") || name.contains("RCH") || name.contains("RCV")) {
                isRCHS2 = true;
            } else if (name.contains("_HH")) {
                isHH = true;
            } else if (name.contains("_HV")) {
                isHV = true;
            } else if (name.contains("_VV")) {
                isVV = true;
            } else if (name.contains("_VH")) {
                isVH = true;
            }
        }

        if (isC3)
            return MATRIX.C3;
        else if (isT3)
            return MATRIX.T3;
        else if (isC2)
            return MATRIX.C2;
        else if (isLCHS2)
            return MATRIX.LCHCP;
        else if (isRCHS2)
            return MATRIX.RCHCP;
        else if (isHH && isHV && !isVH && !isVV)
            return MATRIX.DUAL_HH_HV;
        else if (!isHH && !isHV && isVH && isVV)
            return MATRIX.DUAL_VH_VV;
        else if (isHH && !isHV && !isVH && isVV)
            return MATRIX.DUAL_HH_VV;
        else if (isHH && isHV && isVH && isVV)
            return MATRIX.FULL;

        return MATRIX.UNKNOWN;
    }

    /**
     * Check input product format, get source bands and set corresponding flag.
     *
     * @param srcProduct        the input product
     * @param sourceProductType The source product type
     * @return QuadSourceBand[]
     * @throws Exception if sourceProduct is not quad-pol
     */
    public static PolSourceBand[] getSourceBands(final Product srcProduct,
                                                  final MATRIX sourceProductType) throws Exception {

        final boolean isCoregistered = StackUtils.isCoregisteredStack(srcProduct);
        final List<PolSourceBand> quadSrcBandList = new ArrayList<>(10);

        if (isCoregistered) {
            final String[] refBandNames = StackUtils.getReferenceBandNames(srcProduct);
            checkAcquisitionBandNames(refBandNames, "reference", srcProduct.getName());
            final Band[] refBands = getBands(srcProduct, sourceProductType, refBandNames);
            quadSrcBandList.add(new PolSourceBand(srcProduct.getName(), refBands, getBandSuffix(refBandNames[0])));

            final String[] secProductNames = StackUtils.getSecondaryProductNames(srcProduct);
            for (String secProd : secProductNames) {
                final String[] secBandNames = StackUtils.getSecondaryBandNames(srcProduct, secProd);
                checkAcquisitionBandNames(secBandNames, secProd, srcProduct.getName());
                final Band[] secBands = getBands(srcProduct, sourceProductType, secBandNames);
                quadSrcBandList.add(new PolSourceBand(secProd, secBands, getBandSuffix(secBandNames[0])));
            }
        } else {
            final String[] bandNames = srcProduct.getBandNames();
            final Band[] refBands = getBands(srcProduct, sourceProductType, bandNames);
            quadSrcBandList.add(new PolSourceBand(srcProduct.getName(), refBands, ""));
        }
        return quadSrcBandList.toArray(new PolSourceBand[0]);
    }

    /**
     * A product flagged as a coregistered stack must list the bands of each acquisition in its
     * Secondary_Metadata. Without that list there is nothing to group by, so say which acquisition
     * of which product is missing rather than indexing an empty array.
     */
    private static void checkAcquisitionBandNames(final String[] bandNames, final String acquisition,
                                                  final String productName) throws Exception {
        if (bandNames == null || bandNames.length == 0) {
            throw new Exception("No " + acquisition + " bands found in coregistered stack " + productName +
                    ". The Secondary_Metadata band list is missing or empty.");
        }
    }

    private static String getBandSuffix(final String bandName) {
        final int idx = bandName.lastIndexOf('_');
        return idx < 0 ? "" : bandName.substring(idx);
    }

    /**
     * Check input product format, get source bands and set corresponding flag.
     *
     * @param srcProduct        The source product
     * @param sourceProductType The source product type
     * @param bandNames         the src band names
     * @return QuadSourceBand[]
     */
    private static Band[] getBands(final Product srcProduct, final MATRIX sourceProductType, final String[] bandNames) throws Exception {

        if (sourceProductType == MATRIX.DUAL_HH_HV) { // dual pol HH HV
            return getDualPolSrcBands(srcProduct, bandNames, getComplexBandNames(), sourceProductType);
        } else if (sourceProductType == MATRIX.DUAL_VH_VV) { // dual VH VV
            return getDualPolSrcBands(srcProduct, bandNames, getComplexBandNames(), sourceProductType);
        } else if (sourceProductType == MATRIX.DUAL_HH_VV) { // dual HH VV
            return getDualPolSrcBands(srcProduct, bandNames, getComplexBandNames(), sourceProductType);
        }else if (sourceProductType == MATRIX.FULL) { // full pol
            return getQuadPolSrcBands(srcProduct, bandNames);
        } else if (sourceProductType == MATRIX.C3) { // C3
            return getProductBands(srcProduct, bandNames, getC3BandNames());
        } else if (sourceProductType == MATRIX.T3) { // T3
            return getProductBands(srcProduct, bandNames, getT3BandNames());
        } else if (sourceProductType == MATRIX.C4) {
            return getProductBands(srcProduct, bandNames, getC4BandNames());
        } else if (sourceProductType == MATRIX.T4) {
            return getProductBands(srcProduct, bandNames, getT4BandNames());
        } else if (sourceProductType == MATRIX.C2) { // compact pol C2
            return getProductBands(srcProduct, bandNames, getC2BandNames());
        } else if (sourceProductType == MATRIX.LCHCP) { // LCH compact pol S2
            return getProductBands(srcProduct, bandNames, getLCHModeS2BandNames());
        } else if (sourceProductType == MATRIX.RCHCP) { // RCH compact pol S2
            return getProductBands(srcProduct, bandNames, getRCHModeS2BandNames());
        }
        return null;
    }

    /**
     * Collect the i/q band pair of each polarisation of one acquisition.
     *
     * @param srcProduct  the source product
     * @param bandNames   the band names this acquisition owns. On a coregistered stack this is the
     *                    per-date list from {@code Secondary_Metadata}; scanning the whole product
     *                    instead would hand every date the same bands, so each date's covariance
     *                    matrix would be built from dataBuffers[0..3] = date 1 and date 2 of the
     *                    same polarisation.
     * @param prefixes    the complex band name prefixes to accept (i_, q_)
     * @param sourceProductType which polarisation pair to return
     */
    private static Band[] getDualPolSrcBands(final Product srcProduct, final String[] bandNames,
                                             final String[] prefixes, final MATRIX sourceProductType)
            throws Exception {

        final List<Band> hhBandList = new ArrayList<>();
        final List<Band> hvBandList = new ArrayList<>();
        final List<Band> vvBandList = new ArrayList<>();
        final List<Band> vhBandList = new ArrayList<>();
        for (final String bandName : bandNames) {
            final Band srcBand = srcProduct.getBand(bandName);
            if (srcBand == null) {
                throw new Exception("Band " + bandName + " not found in " + srcProduct.getName());
            }
            for (String s : prefixes) {
                if(bandName.startsWith(s)) {
                    if (bandName.toLowerCase().contains("hh")) {
                        hhBandList.add(srcBand);
                    } else if (bandName.toLowerCase().contains("hv")) {
                        hvBandList.add(srcBand);
                    } else if (bandName.toLowerCase().contains("vv")) {
                        vvBandList.add(srcBand);
                    } else if (bandName.toLowerCase().contains("vh")) {
                        vhBandList.add(srcBand);
                    }
                    break;
                }
            }
        }

        final List<Band> scatterVector = new ArrayList<>(4);
        if (sourceProductType == MATRIX.DUAL_HH_HV) {
            addComplexPair(scatterVector, hhBandList, "HH", srcProduct);
            addComplexPair(scatterVector, hvBandList, "HV", srcProduct);
        } else if (sourceProductType == MATRIX.DUAL_VH_VV) {
            addComplexPair(scatterVector, vvBandList, "VV", srcProduct);
            addComplexPair(scatterVector, vhBandList, "VH", srcProduct);
        } else if (sourceProductType == MATRIX.DUAL_HH_VV) {
            addComplexPair(scatterVector, hhBandList, "HH", srcProduct);
            addComplexPair(scatterVector, vvBandList, "VV", srcProduct);
        } else {
            return null;
        }

        return scatterVector.toArray(new Band[0]);
    }

    /**
     * Append one polarisation's i/q pair in the order DualPolProcessor.getScatterVector reads it:
     * the real part first, the imaginary part second. Taking the two in encounter order silently
     * conjugates C12 for any product that lists q before i, and anything other than exactly one
     * pair per polarisation (a detected GRD resolves to none, an unsplit multi-swath product to
     * three) has to be reported here rather than surface as an out-of-bounds inside computeTile.
     */
    private static void addComplexPair(final List<Band> scatterVector, final List<Band> polBands,
                                       final String pol, final Product srcProduct) throws Exception {
        Band real = null, imaginary = null;
        for (final Band band : polBands) {
            final Unit.UnitType unitType = Unit.getUnitType(band);
            if (unitType == Unit.UnitType.REAL && real == null) {
                real = band;
            } else if (unitType == Unit.UnitType.IMAGINARY && imaginary == null) {
                imaginary = band;
            } else {
                throw new Exception("Unexpected band " + band.getName() + " for polarisation " + pol +
                        " in " + srcProduct.getName() +
                        "; exactly one real and one imaginary band are expected.");
            }
        }
        if (real == null || imaginary == null) {
            throw new Exception("A real and an imaginary band are expected for polarisation " + pol +
                    " in " + srcProduct.getName() + "; found " + polBands.size() + " complex band(s).");
        }
        scatterVector.add(real);
        scatterVector.add(imaginary);
    }

    private static Band[] getQuadPolSrcBands(final Product srcProduct, final String[] srcBandNames) throws Exception {

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(srcProduct);
        final boolean isComplex = absRoot.getAttributeString(AbstractMetadata.SAMPLE_TYPE).equals("COMPLEX");

        if(!isComplex) {
            final List<Band> bandList = new ArrayList<>();
            for (final String srcBandName : srcBandNames) {
                final Band band = srcProduct.getBand(srcBandName);
                if (band == null) {
                    throw new Exception("Band " + srcBandName + " not found in " + srcProduct.getName());
                }
                final String bandUnit = band.getUnit();
                if (bandUnit == null || !bandUnit.contains(Unit.INTENSITY))
                    continue;
                final String pol = OperatorUtils.getBandPolarization(band.getName(), absRoot);

                if (pol.contains("hh") || pol.contains("hv") || pol.contains("vh") || pol.contains("vv")) {
                    bandList.add(band);
                }
            }

            if(bandList.size() < 4) {
                throw new Exception("A full polarization product is expected as input.");
            }
            return bandList.toArray(new Band[0]);
        }

        int validBandCnt = 0;
        final Band[] sourceBands = new Band[8];
        for (final String srcBandName : srcBandNames) {

            final Band band = srcProduct.getBand(srcBandName);
            if (band == null) {
                throw new Exception("Band " + srcBandName + " not found in " + srcProduct.getName());
            }
            final Unit.UnitType bandUnit = Unit.getUnitType(band);
            if (!(bandUnit == Unit.UnitType.REAL || bandUnit == Unit.UnitType.IMAGINARY))
                continue;
            final String pol = OperatorUtils.getBandPolarization(band.getName(), absRoot);

            if (pol.contains("hh")) {
                if (bandUnit.equals(Unit.UnitType.REAL)) {
                    sourceBands[0] = band;
                    ++validBandCnt;
                } else if (bandUnit.equals(Unit.UnitType.IMAGINARY)) {
                    sourceBands[1] = band;
                    ++validBandCnt;
                }
            } else if (pol.contains("hv")) {
                if (bandUnit.equals(Unit.UnitType.REAL)) {
                    sourceBands[2] = band;
                    ++validBandCnt;
                } else if (bandUnit.equals(Unit.UnitType.IMAGINARY)) {
                    sourceBands[3] = band;
                    ++validBandCnt;
                }
            } else if (pol.contains("vh")) {
                if (bandUnit.equals(Unit.UnitType.REAL)) {
                    sourceBands[4] = band;
                    ++validBandCnt;
                } else if (bandUnit.equals(Unit.UnitType.IMAGINARY)) {
                    sourceBands[5] = band;
                    ++validBandCnt;
                }
            } else if (pol.contains("vv")) {
                if (bandUnit.equals(Unit.UnitType.REAL)) {
                    sourceBands[6] = band;
                    ++validBandCnt;
                } else if (bandUnit.equals(Unit.UnitType.IMAGINARY)) {
                    sourceBands[7] = band;
                    ++validBandCnt;
                }
            }
        }

        if (validBandCnt != 8) {
            throw new Exception("A full polarization product is expected as input.");
        }
        return sourceBands;
    }

    private static Band[] getProductBands(final Product srcProduct, final String[] srcBandNames,
                                          final String[] validBandNames) throws Exception {

        final Band[] sourceBands = new Band[validBandNames.length];

        // Slot each band by the element it matches. Consumers read this array positionally
        // (dataBuffers[0] is C11, [1] is C12_real, ...), so filling it in the order the caller
        // happened to list the names would silently build the matrix from the wrong elements
        // while still satisfying the count check below.
        int validBandCnt = 0;
        for (final String bandName : srcBandNames) {
            final Band band = srcProduct.getBand(bandName);
            if (band == null) {
                throw new Exception("Band " + bandName + " not found");
            }

            for (int i = 0; i < validBandNames.length; ++i) {
                if (isMatrixElementBand(bandName, validBandNames[i])) {
                    if (sourceBands[i] != null) {
                        // Two bands claiming the same element means more than one acquisition
                        // reached this method - typically a stack whose coregistered_stack flag was
                        // lost. Keeping the first would return one date and label it as the product.
                        throw new Exception("Two bands match matrix element " + validBandNames[i] +
                                " in " + srcProduct.getName() + ": " + sourceBands[i].getName() +
                                " and " + bandName + ". A single acquisition is expected.");
                    }
                    sourceBands[i] = band;
                    ++validBandCnt;
                    break;
                }
            }
        }

        if (validBandCnt != validBandNames.length) {
            throw new Exception("Input is not a valid polarimetric matrix: found " + validBandCnt +
                    " of " + validBandNames.length + " elements in " + srcProduct.getName());
        }
        return sourceBands;
    }

    public static void saveNewBandNames(final Product targetProduct, final PolSourceBand[] srcBandList) throws Exception {
        if (StackUtils.isCoregisteredStack(targetProduct)) {
            boolean referenceProduct = true;
            for (final PolSourceBand bandList : srcBandList) {
                if (referenceProduct) {
                    final String[] bandNames = StackUtils.bandsToStringArray(bandList.targetBands);
                    StackUtils.saveReferenceProductBandNames(targetProduct, bandNames);
                    referenceProduct = false;
                } else {
                    final String[] bandNames = StackUtils.bandsToStringArray(bandList.targetBands);
                    StackUtils.saveSecondaryProductBandNames(targetProduct, bandList.productName, bandNames);
                }
            }
        }
    }

    public static boolean isDualPol(final MATRIX m) {
        return m == MATRIX.DUAL_HH_HV || m == MATRIX.DUAL_VH_VV || m == MATRIX.DUAL_HH_VV ||
                m == MATRIX.C2 || m == MATRIX.LCHCP || m == MATRIX.RCHCP;
    }

    public static boolean isQuadPol(final MATRIX m) {
        return m == MATRIX.C3 || m == MATRIX.T3 || m == MATRIX.C4 || m == MATRIX.T4;
    }

    public static boolean isFullPol(final MATRIX m) {
        return m == MATRIX.FULL;
    }

    public static String[] getComplexBandNames() {
        return new String[]{
                "i_",
                "q_"
        };
    }

    /**
     * Get band names for compact pol Stokes vector product.
     *
     * @return The source band names.
     */
    public static String[] getG4BandNames() {
        return new String[]{
                "g0",
                "g1",
                "g2",
                "g3",
        };
    }

    /**
     * Get band names for Right Circular Hybrid mode compact pol scattering vector product.
     *
     * @return The source band names.
     */
    public static String[] getLCHModeS2BandNames() {
        return new String[]{
                "i_LCH",
                "q_LCH",
                "i_LCV",
                "q_LCV",
        };
    }

    /**
     * Get band names for Left Circular Hybrid mode compact pol scattering vector product.
     *
     * @return The source band names.
     */
    public static String[] getRCHModeS2BandNames() {
        return new String[]{
                "i_RCH",
                "q_RCH",
                "i_RCV",
                "q_RCV",
        };
    }

    /**
     * Get compact pol covariance matrix product source band names.
     *
     * @return The source band names.
     */
    /**
     * True when the band name IS the given matrix element, optionally carrying a stack suffix.
     * Anchored at the start with a '_' separator: Sigma0_C11_db and coh_C11_win merely contain the
     * token, and matching them would let either displace the real C11 in a band list that every
     * consumer reads positionally.
     *
     * @param bandName the band name
     * @param element  the canonical element name, e.g. C11 or C12_real
     * @return true if the band is that element
     */
    public static boolean isMatrixElementBand(final String bandName, final String element) {
        return bandName.equals(element) || bandName.startsWith(element + '_');
    }

    /**
     * The canonical element names of a polarimetric matrix, in the order every consumer reads them
     * positionally (see DualPolProcessor.getCovarianceMatrixC2 / QuadPolProcessor).
     *
     * @param matrixType the matrix type
     * @return the element names, or null if the type is not a matrix
     */
    public static String[] getMatrixBandNames(final MATRIX matrixType) {
        switch (matrixType) {
            case C2:
                return getC2BandNames();
            case C3:
                return getC3BandNames();
            case C4:
                return getC4BandNames();
            case T3:
                return getT3BandNames();
            case T4:
                return getT4BandNames();
            default:
                return null;
        }
    }

    public static String[] getC2BandNames() {
        return new String[]{
                "C11",
                "C12_real",
                "C12_imag",
                "C22",
        };
    }

    public static String[] getC3BandNames() {
        return new String[]{
                "C11",
                "C12_real",
                "C12_imag",
                "C13_real",
                "C13_imag",
                "C22",
                "C23_real",
                "C23_imag",
                "C33"
        };
    }

    public static String[] getC4BandNames() {
        return new String[]{
                "C11",
                "C12_real",
                "C12_imag",
                "C13_real",
                "C13_imag",
                "C14_real",
                "C14_imag",
                "C22",
                "C23_real",
                "C23_imag",
                "C24_real",
                "C24_imag",
                "C33",
                "C34_real",
                "C34_imag",
                "C44"
        };
    }

    public static String[] getT3BandNames() {
        return new String[]{
                "T11",
                "T12_real",
                "T12_imag",
                "T13_real",
                "T13_imag",
                "T22",
                "T23_real",
                "T23_imag",
                "T33"
        };
    }

    public static String[] getT4BandNames() {
        return new String[]{
                "T11",
                "T12_real",
                "T12_imag",
                "T13_real",
                "T13_imag",
                "T14_real",
                "T14_imag",
                "T22",
                "T23_real",
                "T23_imag",
                "T24_real",
                "T24_imag",
                "T33",
                "T34_real",
                "T34_imag",
                "T44"
        };
    }

    public static String getPolarType(final Product product) {
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
        if (absRoot != null) {
            if (!AbstractMetadata.isNoData(absRoot, AbstractMetadata.mds1_tx_rx_polar) &&
                    !AbstractMetadata.isNoData(absRoot, AbstractMetadata.mds2_tx_rx_polar)) {
                if (!AbstractMetadata.isNoData(absRoot, AbstractMetadata.mds3_tx_rx_polar) &&
                        !AbstractMetadata.isNoData(absRoot, AbstractMetadata.mds4_tx_rx_polar)) {
                    return "full";
                }
                return "dual";
            }
        }
        return "single";
    }

    public static boolean isBandForMatrixElement(final String bandName, final String elemPrefix) {

        return bandName.length() > elemPrefix.length() &&
                bandName.substring(1, elemPrefix.length()+1).equals(elemPrefix);
    }
}
