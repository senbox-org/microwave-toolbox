/*
 * Copyright (C) 2023 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.sentinel1.gpf.etadcorrectors;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataAttribute;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;

import java.io.IOException;
import java.text.DateFormat;
import java.util.HashMap;
import java.util.Map;

public final class ETADUtils {

    private final Product etadProduct;
    private MetadataElement absRoot = null;
    private MetadataElement origProdRoot = null;
    private double azimuthTimeMin = 0.0;
    private double azimuthTimeMax = 0.0;
    private double rangeTimeMin = 0.0;
    private double rangeTimeMax = 0.0;
    private int numInputProducts = 0;
    private int numSubSwaths = 0;
    InputProduct[] inputProducts = null;
    private InstrumentTimingCalibration[] instrumentTimingCalibrationList = null;

    public ETADUtils(final Product ETADProduct) throws Exception {

        etadProduct = ETADProduct;

        getMetadataRoot();

        getReferenceTimes();

        getInstrumentTimingCalibrationList();

        getInputProductMetadata();
    }

    public void dispose() {
        if(etadProduct != null) {
            etadProduct.dispose();
        }
    }

    private void getMetadataRoot() throws IOException {

        final MetadataElement root = etadProduct.getMetadataRoot();
        if (root == null) {
            throw new IOException("Root Metadata not found");
        }

        absRoot = AbstractMetadata.getAbstractedMetadata(etadProduct);
        if (absRoot == root) {
            throw new IOException(AbstractMetadata.ABSTRACT_METADATA_ROOT + " not found.");
        }

        origProdRoot = AbstractMetadata.getOriginalProductMetadata(etadProduct);
        if (origProdRoot == root) {
            throw new IOException("Original_Product_Metadata not found.");
        }

        final String mission = absRoot.getAttributeString(AbstractMetadata.MISSION);
        if (!mission.startsWith("SENTINEL-1")) {
            throw new IOException(mission + " is not a valid mission for Sentinel1 product.");
        }
    }

    private void getReferenceTimes() {

        final MetadataElement annotationElem = origProdRoot.getElement("annotation");
        azimuthTimeMin = getTime(annotationElem, "azimuthTimeMin").getMJD()*Constants.secondsInDay;
        azimuthTimeMax = getTime(annotationElem, "azimuthTimeMax").getMJD()*Constants.secondsInDay;
        rangeTimeMin = annotationElem.getAttributeDouble("rangeTimeMin");
        rangeTimeMax = annotationElem.getAttributeDouble("rangeTimeMax");
    }

    private void getInstrumentTimingCalibrationList() {

        final MetadataElement annotationElem = origProdRoot.getElement("annotation");
        final MetadataElement etadProductElem = annotationElem.getElement("etadProduct");
        final MetadataElement processingInformationElem = etadProductElem.getElement("processingInformation");
        final MetadataElement auxInputDataElem = processingInformationElem.getElement("auxInputData");
        final MetadataElement auxSetapElem = auxInputDataElem.getElement("auxSetap");
        final MetadataElement instrumentTimingCalibrationListElem = auxSetapElem.getElement("instrumentTimingCalibrationList");
        if (instrumentTimingCalibrationListElem != null) {
            final MetadataElement[] instrumentTimingCalibrationArray = instrumentTimingCalibrationListElem.getElements();
            final int count = instrumentTimingCalibrationListElem.getAttributeInt("count");

            instrumentTimingCalibrationList = new InstrumentTimingCalibration[count];
            for (int s = 0; s < count; ++s) {
                final MetadataElement rangeCalibrationElem =
                    instrumentTimingCalibrationArray[s].getElement("rangeCalibration");
                final MetadataElement azimuthCalibrationElem =
                    instrumentTimingCalibrationArray[s].getElement("azimuthCalibration");

                instrumentTimingCalibrationList[s] = new InstrumentTimingCalibration();
                instrumentTimingCalibrationList[s].swathID =
                    instrumentTimingCalibrationArray[s].getAttributeString("swath");
                instrumentTimingCalibrationList[s].polarization =
                    instrumentTimingCalibrationArray[s].getAttributeString("polarisation");
                instrumentTimingCalibrationList[s].rangeCalibration =
                    Double.parseDouble(rangeCalibrationElem.getAttributeString("rangeCalibration"));
                instrumentTimingCalibrationList[s].azimuthCalibration =
                    Double.parseDouble(azimuthCalibrationElem.getAttributeString("azimuthCalibration"));
            }
        } else {
            final MetadataElement instTimingCalRefElem = auxSetapElem.getElement("instrumentTimingCalibrationReference");
            final MetadataElement rangeCalibrationElem = instTimingCalRefElem.getElement("rangeCalibration");
            final MetadataElement azimuthCalibrationElem = instTimingCalRefElem.getElement("azimuthCalibration");
            final double rangeCalibration = Double.parseDouble(rangeCalibrationElem.getAttributeString("rangeCalibration"));
            final double azimuthCalibration = Double.parseDouble(azimuthCalibrationElem.getAttributeString("azimuthCalibration"));

            final MetadataElement instTimingCalOffsetListElem = auxSetapElem.getElement("instrumentTimingCalibrationOffsetList");
            final MetadataElement[] instTimingCalOffsetArray = instTimingCalOffsetListElem.getElements();
            final int count = instTimingCalOffsetListElem.getAttributeInt("count");
            instrumentTimingCalibrationList = new InstrumentTimingCalibration[count];
            for (int s = 0; s < count; ++s) {
                instrumentTimingCalibrationList[s] = new InstrumentTimingCalibration();
                instrumentTimingCalibrationList[s].swathID =
                    instTimingCalOffsetArray[s].getAttributeString("swath");
                instrumentTimingCalibrationList[s].polarization =
                    instTimingCalOffsetArray[s].getAttributeString("polarisation");
                instrumentTimingCalibrationList[s].rangeCalibration = rangeCalibration;
                instrumentTimingCalibrationList[s].azimuthCalibration = azimuthCalibration;
            }
        }
    }

    private void getInputProductMetadata() {

        final MetadataElement annotationElem = origProdRoot.getElement("annotation");
        final MetadataElement etadProductElem = annotationElem.getElement("etadProduct");
        final MetadataElement productComponentsElem = etadProductElem.getElement("productComponents");
        final MetadataElement inputProductListElem = productComponentsElem.getElement("inputProductList");

        numInputProducts = Integer.parseInt(productComponentsElem.getAttributeString("numberOfInputProducts"));
        numSubSwaths = Integer.parseInt(productComponentsElem.getAttributeString("numberOfSwaths"));
        inputProducts = new InputProduct[numInputProducts];

        final MetadataElement[] inputProductElemArray = inputProductListElem.getElements();
        for (int p = 0; p < numInputProducts; ++p) {
            inputProducts[p] = new InputProduct();
            inputProducts[p].productID = inputProductElemArray[p].getAttributeString("productID");
            inputProducts[p].startTime = getTime(inputProductElemArray[p], "startTime").getMJD()*Constants.secondsInDay;
            inputProducts[p].stopTime = getTime(inputProductElemArray[p], "stopTime").getMJD()*Constants.secondsInDay;
            inputProducts[p].pIndex = Integer.parseInt(inputProductElemArray[p].getAttributeString("pIndex"));
            final MetadataElement swathListElem = inputProductElemArray[p].getElement("swathList");
            final int numSwaths = Integer.parseInt(swathListElem.getAttributeString("count"));
            final MetadataElement[] swaths = swathListElem.getElements();
            inputProducts[p].swathArray = new SubSwath[numSwaths];

            for (int s = 0; s < numSwaths; ++s) {
                inputProducts[p].swathArray[s] = new SubSwath();
                inputProducts[p].swathArray[s].swathID = swaths[s].getAttributeString("swathID");
                inputProducts[p].swathArray[s].sIndex = Integer.parseInt(swaths[s].getAttributeString("sIndex"));
                final MetadataElement bIndexListElem = swaths[s].getElement("bIndexList");
                final int numBursts = Integer.parseInt(bIndexListElem.getAttributeString("count"));
                inputProducts[p].swathArray[s].bIndexArray = new int[numBursts];
                inputProducts[p].swathArray[s].burstMap = new HashMap<>(numBursts);

                int bIndex = -1;
                for (int b = 0; b < numBursts; ++b) {
                    final MetadataAttribute bIndexStr = bIndexListElem.getAttributeAt(b);
                    if (bIndexStr.getName().equals("bIndex")) {
                        bIndex = Integer.parseInt(bIndexStr.getData().toString());
                        inputProducts[p].swathArray[s].bIndexArray[b] = bIndex;
                    }
                    if (bIndex != -1) {
                        inputProducts[p].swathArray[s].burstMap.put(bIndex,
                                createBurst(inputProducts[p].swathArray[s].sIndex,
                                        inputProducts[p].swathArray[s].bIndexArray[b]));
                    }
                }
            }
        }
    }

    public static ProductData.UTC getTime(final MetadataElement elem, final String tag) {

        DateFormat sentinelDateFormat = ProductData.UTC.createDateFormat("yyyy-MM-dd HH:mm:ss");
        String start = elem.getAttributeString(tag, AbstractMetadata.NO_METADATA_STRING);
        start = start.replace("T", " ");
        return AbstractMetadata.parseUTC(start, sentinelDateFormat);
    }

    private Burst createBurst(final int sIndex, final int bIndex) {

        final MetadataElement annotationElem = origProdRoot.getElement("annotation");
        final MetadataElement etadProductElem = annotationElem.getElement("etadProduct");
        final MetadataElement etadBurstListElem = etadProductElem.getElement("etadBurstList");
        final MetadataElement[] elements = etadBurstListElem.getElements();

        final Burst burst = new Burst();
        for (MetadataElement elem : elements) {
            // ID information
            final MetadataElement burstDataElem = elem.getElement("burstData");
            final int sIdx = Integer.parseInt(burstDataElem.getAttributeString("sIndex"));
            if (sIdx != sIndex) {
                continue;
            }
            final int bIdx = Integer.parseInt(burstDataElem.getAttributeString("bIndex"));
            if (bIdx != bIndex) {
                continue;
            }
            burst.bIndex = bIdx;
            burst.sIndex = sIdx;
            burst.pIndex = Integer.parseInt(burstDataElem.getAttributeString("pIndex"));
            burst.swathID = burstDataElem.getAttributeString("swathID");

            // coverage information
            final MetadataElement burstCoverageElem = elem.getElement("burstCoverage");
            final MetadataElement temporalCoverageElem = burstCoverageElem.getElement("temporalCoverage");
            final MetadataElement rangeTimeMinElem = temporalCoverageElem.getElement("rangeTimeMin");
            final MetadataElement rangeTimeMaxElem = temporalCoverageElem.getElement("rangeTimeMax");
            burst.rangeTimeMin = Double.parseDouble(rangeTimeMinElem.getAttributeString("rangeTimeMin"));
            burst.rangeTimeMax = Double.parseDouble(rangeTimeMaxElem.getAttributeString("rangeTimeMax"));
            burst.azimuthTimeMin = getTime(temporalCoverageElem, "azimuthTimeMin").getMJD()*Constants.secondsInDay;
            burst.azimuthTimeMax = getTime(temporalCoverageElem, "azimuthTimeMax").getMJD()*Constants.secondsInDay;

            // grid information
            final MetadataElement gridInformationElem = elem.getElement("gridInformation");
            final MetadataElement gridStartAzimuthTimeElem = gridInformationElem.getElement("gridStartAzimuthTime");
            final MetadataElement gridStartRangeTimeElem = gridInformationElem.getElement("gridStartRangeTime");
            final MetadataElement gridDimensionsElem = gridInformationElem.getElement("gridDimensions");
            final MetadataElement gridSamplingElem = gridInformationElem.getElement("gridSampling");
            final MetadataElement azimuth = gridSamplingElem.getElement("azimuth");
            final MetadataElement rangeElem = gridSamplingElem.getElement("range");
            burst.gridStartAzimuthTime = Double.parseDouble(gridStartAzimuthTimeElem.getAttributeString("gridStartAzimuthTime"));
            burst.gridStartRangeTime = Double.parseDouble(gridStartRangeTimeElem.getAttributeString("gridStartRangeTime"));
            burst.gridSamplingAzimuth = Double.parseDouble(azimuth.getAttributeString("azimuth"));
            burst.gridSamplingRange = Double.parseDouble(rangeElem.getAttributeString("range"));
            burst.azimuthExtent = Integer.parseInt(gridDimensionsElem.getAttributeString("azimuthExtent"));
            burst.rangeExtent = Integer.parseInt(gridDimensionsElem.getAttributeString("rangeExtent"));
        }
        return burst;
    }

    public String createBandName(final String swathID, final int bIndex, final String tag) {

        if (bIndex < 10) {
            return swathID + "_" + "Burst000" + bIndex + "_" + tag;
        } else if (bIndex < 100) {
            return swathID + "_" + "Burst00" + bIndex + "_" + tag;
        } else {
            return swathID + "_" + "Burst0" + bIndex + "_" + tag;
        }
    }

    public int getProductIndex(final double azimuthTime) {

        for (InputProduct prod : inputProducts) {
            if (prod.startTime <= azimuthTime && azimuthTime <= prod.stopTime) {
                return prod.pIndex;
            }
        }
        return -1;
    }

    public int getProductIndex(final String ProductName) {

        final String productSensingStartStopTime = ProductName.substring(17, 55).toLowerCase();
        for (InputProduct prod : inputProducts) {
            if (prod.productID.toLowerCase().contains(productSensingStartStopTime)) {
                return prod.pIndex;
            }
        }
        return -1;
    }

    public int getSwathIndex(final int pIndex, final double slantRangeTime) {

        final SubSwath[] swathArray = inputProducts[pIndex - 1].swathArray;
        for (SubSwath swath : swathArray) {
            final Burst firstBurst = swath.burstMap.get(swath.bIndexArray[0]);
            if (slantRangeTime > firstBurst.rangeTimeMin && slantRangeTime < firstBurst.rangeTimeMax) {
                return swath.sIndex;
            }
        }
        return -1;
    }

    public int getBurstIndex(final int pIndex, final int sIndex, final double azimuthTime) {

        final int[] bIndexArray = inputProducts[pIndex - 1].swathArray[sIndex - 1].bIndexArray;
        for (int bIndex : bIndexArray) {
            final Burst burst = inputProducts[pIndex - 1].swathArray[sIndex - 1].burstMap.get(bIndex);
            if (azimuthTime > burst.azimuthTimeMin && azimuthTime < burst.azimuthTimeMax) {
                return bIndex;
            }
        }
        return -1;
    }

    public int[] getBurstIndexArray(final int pIndex, final int sIndex) {
        return inputProducts[pIndex - 1].swathArray[sIndex - 1].bIndexArray;
    }

    public Burst getBurst(final double azimuthTime, final double slantRangeTime) {

        final int pIndex = getProductIndex(azimuthTime);
        if (pIndex == -1) {
            return null;
        }

        final int sIndex = getSwathIndex(pIndex, slantRangeTime);
        if (sIndex == -1) {
            return null;
        }

        int bIndex = getBurstIndex(pIndex, sIndex, azimuthTime);
        if (bIndex == -1) {
            if (pIndex + 1 <= numInputProducts) {
                bIndex = getBurstIndex(pIndex + 1, sIndex, azimuthTime);
                if (bIndex != -1) {
                    return inputProducts[pIndex].swathArray[sIndex - 1].burstMap.get(bIndex);
                }
            }

            if (pIndex - 1 >= 1) {
                bIndex = getBurstIndex(pIndex - 1, sIndex, azimuthTime);
                if (bIndex != -1) {
                    return inputProducts[pIndex - 2].swathArray[sIndex - 1].burstMap.get(bIndex);
                }
            }

            return null;
        }

        return inputProducts[pIndex - 1].swathArray[sIndex - 1].burstMap.get(bIndex);
    }

    public Burst getBurst(final int pIndex, final int sIndex, final int bIndex) {

        return inputProducts[pIndex - 1].swathArray[sIndex - 1].burstMap.get(bIndex);
    }

    public double[][] getLayerCorrectionForCurrentBurst(final Burst burst, final String bandName) {

        try {
            final Band layerBand = etadProduct.getBand(bandName);
            layerBand.readRasterDataFully(ProgressMonitor.NULL);
            final ProductData layerData = layerBand.getData();
            final double[][] correction = new double[burst.azimuthExtent][burst.rangeExtent];
            for (int a = 0; a < burst.azimuthExtent; ++a) {
                for (int r = 0; r < burst.rangeExtent; ++r) {
                    correction[a][r] = layerData.getElemDoubleAt(a * burst.rangeExtent + r);
                }
            }
            return correction;

        } catch (Exception e) {
            OperatorUtils.catchOperatorException("getLayerCorrectionForCurrentBurst", e);
        }

        return null;
    }

    public double getRangeCalibration(final String swathID) {
        for (InstrumentTimingCalibration elem : instrumentTimingCalibrationList) {
            if (elem.swathID.equals(swathID)) {
                return elem.rangeCalibration;
            }
        }
        return 0.0;
    }

    public double getAzimuthCalibration(final String swathID) {
        for (InstrumentTimingCalibration elem : instrumentTimingCalibrationList) {
            if (elem.swathID.equals(swathID)) {
                return elem.azimuthCalibration;
            }
        }
        return 0.0;
    }

    public final static class InstrumentTimingCalibration {
        public double rangeCalibration;
        public double azimuthCalibration;
        public String swathID;
        public String polarization;
    }

    public final static class InputProduct {
        public double startTime;
        public double stopTime;
        public int pIndex;
        public String productID;
        public SubSwath[] swathArray;
    }

    public final static class SubSwath {
        public String swathID;
        public int sIndex;
        public int[] bIndexArray;
        public Map<Integer, Burst> burstMap;
    }

    public final static class Burst {
        public String swathID;
        public int bIndex;
        public int sIndex;
        public int pIndex;

        public double rangeTimeMin;
        public double rangeTimeMax;
        public double azimuthTimeMin;
        public double azimuthTimeMax;

        public double gridStartAzimuthTime;
        public double gridStartRangeTime;
        public double gridSamplingAzimuth;
        public double gridSamplingRange;
        public int azimuthExtent;
        public int rangeExtent;
    }
}
