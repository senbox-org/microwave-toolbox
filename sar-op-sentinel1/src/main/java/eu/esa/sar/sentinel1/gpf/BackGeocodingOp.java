/*
 * Copyright (C) 2014 by Array Systems Computing Inc. http://www.array.ca
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
package eu.esa.sar.sentinel1.gpf;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.sentinel1.gpf.etadcorrectors.ETADUtils;
import org.apache.commons.math3.util.FastMath;
import eu.esa.sar.insar.gpf.coregistration.CreateStackOp;
import eu.esa.sar.insar.gpf.coregistration.DEMAssistedCoregistrationOp;
import eu.esa.sar.commons.SARGeocoding;
import eu.esa.sar.commons.Sentinel1Utils;
import org.esa.snap.core.dataio.persistence.Attribute;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.dataop.dem.ElevationModel;
import org.esa.snap.core.dataop.resamp.Resampling;
import org.esa.snap.core.dataop.resamp.ResamplingFactory;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProducts;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.core.util.StringUtils;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.dem.dataio.DEMFactory;
import org.esa.snap.dem.dataio.EarthGravitationalModel96;
import org.esa.snap.dem.dataio.FileElevationModel;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.PosVector;
import org.esa.snap.engine_utilities.datamodel.ProductInformation;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.eo.GeoUtils;
import org.esa.snap.engine_utilities.gpf.*;
import org.jlinda.core.delaunay.TriangleInterpolator;

import java.awt.*;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.DateFormat;
import java.util.*;
import java.util.List;

/**
 * "Backgeocoding" + "Coregistration" processing blocks in The Sentinel-1 TOPS InSAR processing chain.
 * Burst co-registration is performed using orbits and DEM.
 */
@OperatorMetadata(alias = "Back-Geocoding",
        category = "Radar/Coregistration/S-1 TOPS Coregistration",
        authors = "Jun Lu, Luis Veci",
        version = "1.0",
        copyright = "Copyright (C) 2014 by Array Systems Computing Inc.",
        description = "Bursts co-registration using orbit and DEM")
public final class BackGeocodingOp extends Operator {

    @SourceProducts
    private Product[] sourceProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(description = "The digital elevation model.",
            defaultValue = "SRTM 3Sec", label = "Digital Elevation Model")
    private String demName = "SRTM 3Sec";

    @Parameter(defaultValue = ResamplingFactory.BICUBIC_INTERPOLATION_NAME,
            label = "DEM Resampling Method")
    private String demResamplingMethod = ResamplingFactory.BICUBIC_INTERPOLATION_NAME;

    @Parameter(label = "External DEM")
    private File externalDEMFile = null;

    @Parameter(label = "DEM No Data Value", defaultValue = "0")
    private double externalDEMNoDataValue = 0;

    @Parameter(defaultValue = ResamplingFactory.BISINC_5_POINT_INTERPOLATION_NAME,
            description = "The method to be used when resampling the slave grid onto the master grid.",
            label = "Resampling Type")
    private String resamplingType = ResamplingFactory.BISINC_5_POINT_INTERPOLATION_NAME;

    @Parameter(defaultValue = "true", label = "Mask out areas with no elevation")
    private boolean maskOutAreaWithoutElevation = true;

    @Parameter(defaultValue = "false", label = "Output Range and Azimuth Offset")
    private boolean outputRangeAzimuthOffset = false;

    @Parameter(defaultValue = "false", label = "Output Deramp and Demod Phase")
    private boolean outputDerampDemodPhase = false;

    @Parameter(defaultValue = "false", label = "Disable Reramp")
    private boolean disableReramp = false;

    private Resampling selectedResampling = null;

    private Product masterProduct = null;
    private List<SlaveData> slaveDataList = new ArrayList<>();

    private Sentinel1Utils mSU = null;
    private Sentinel1Utils.SubSwathInfo[] mSubSwath = null;
    private String mstSuffix = null;

    private ElevationModel dem = null;
    private boolean isElevationModelAvailable = false;
    private double demNoDataValue = 0; // no data value for DEM
    private double demSamplingLat = 0.0;
    private double demSamplingLon = 0.0;
    private double noDataValue = 0.0;

    private int subSwathIndex = 0;
    private boolean burstOffsetComputed = false;
    private String swathIndexStr = null;
    private String swathID = null;

    private final HashMap<Band, Band> targetBandToSlaveBandMap = new HashMap<>(2);
    private final HashMap<Band, SlaveData> targetBandToSlaveDataMap = new HashMap<>(2);

    private static final double invalidIndex = -9999.0;

    private static final String ETAD_PHASE_CORRECTION = "etadPhaseCorrection";
    private static final String ETAD_HEIGHT = "etadHeight";
    private static final String ETAD_GRADIENT = "etadGradient";
    private static final String PRODUCT_SUFFIX = "_Stack";

    private boolean outputDEM = false;

    /**
     * Default constructor. The graph processing framework
     * requires that an operator has a default constructor.
     */
    public BackGeocodingOp() {
    }

    /**
     * Initializes this operator and sets the one and only target product.
     * <p>The target product can be either defined by a field of type {@link Product} annotated with the
     * {@link TargetProduct TargetProduct} annotation or
     * by calling {@link #setTargetProduct} method.</p>
     * <p>The framework calls this method after it has created this operator.
     * Any client code that must be performed before computation of tile data
     * should be placed here.</p>
     *
     * @throws OperatorException If an error occurs during operator initialisation.
     * @see #getTargetProduct()
     */
    @Override
    public void initialize() throws OperatorException {

        try {
            if (sourceProduct == null) {
                return;
            }

            checkSourceProductValidity();

            masterProduct = sourceProduct[0];
            mSU = new Sentinel1Utils(masterProduct);
            mSubSwath = mSU.getSubSwath();
            mSU.computeDopplerRate();
            mSU.computeReferenceTime();

            for(Product product : sourceProduct) {
                if(product.equals(masterProduct))
                    continue;
                slaveDataList.add(new SlaveData(product));
            }

            /*
            outputToFile("c:\\output\\mSensorPosition.dat", mSU.getOrbit().sensorPosition);
            outputToFile("c:\\output\\mSensorVelocity.dat", mSU.getOrbit().sensorVelocity);
            outputToFile("c:\\output\\sSensorPosition.dat", sSU.getOrbit().sensorPosition);
            outputToFile("c:\\output\\sSensorVelocity.dat", sSU.getOrbit().sensorVelocity);
            */

            final String[] mSubSwathNames = mSU.getSubSwathNames();
            final String[] mPolarizations = mSU.getPolarizations();

            for(SlaveData slaveData : slaveDataList) {
                final String[] sSubSwathNames = slaveData.sSU.getSubSwathNames();
                if (mSubSwathNames.length != 1 || sSubSwathNames.length != 1) {
                    throw new OperatorException("Split product is expected.");
                }

                if (!mSubSwathNames[0].equals(sSubSwathNames[0])) {
                    throw new OperatorException("Same sub-swath is expected.");
                }

                final String[] sPolarizations = slaveData.sSU.getPolarizations();
                if (!StringUtils.containsIgnoreCase(sPolarizations, mPolarizations[0])) {
                    throw new OperatorException("Same polarization is expected.");
                }
            }

            subSwathIndex = 1; // subSwathIndex is always 1 because of split product
            swathIndexStr = mSubSwathNames[0].substring(2);
            swathID = mSubSwathNames[0];

            if (externalDEMFile == null) {
                DEMFactory.checkIfDEMInstalled(demName);
            }

            DEMFactory.validateDEM(demName, masterProduct);

            selectedResampling = ResamplingFactory.createResampling(resamplingType);
            if(selectedResampling == null) {
                throw new OperatorException("Resampling method "+ resamplingType + " is invalid");
            }

            createTargetProduct();

            final List<String> masterProductBands = new ArrayList<>();
            for (String bandName : masterProduct.getBandNames()) {
                if (masterProduct.getBand(bandName) instanceof VirtualBand) {
                    continue;
                }
                masterProductBands.add(bandName + mstSuffix);
            }

            StackUtils.saveMasterProductBandNames(targetProduct,
                    masterProductBands.toArray(new String[0]));
            StackUtils.saveSlaveProductNames(sourceProduct, targetProduct,
                    masterProduct, targetBandToSlaveBandMap);

            updateTargetProductMetadata();

            final Band masterBandI = getBand(masterProduct, "i_", swathIndexStr, mSU.getPolarizations()[0]);
            if(masterBandI != null && masterBandI.isNoDataValueUsed()) {
                noDataValue = masterBandI.getNoDataValue();
            }

            // coregister master and slave TPGs if ETAD data is found in TPGs
            if (findETADTPG()) {
                final String[] tgtSlvTPGList = getTargetSlvTPGList();
                final boolean tgtSlvTPGCreated = checkTargetTPG(tgtSlvTPGList);
                if (!tgtSlvTPGCreated) {
                    coregisterETADTPG();
                }
                saveMasterETADTPG();
                saveSlaveETADTPG(tgtSlvTPGList);
                saveSlaveBurstIndexArray();
            }

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    //vvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvv
    private boolean findETADTPG() {

        boolean foundETADTPG = false;
        for(SlaveData slaveData : slaveDataList) {
            final TiePointGrid[] tpgs = slaveData.slaveProduct.getTiePointGrids();
            for (TiePointGrid tpg : tpgs) {
                final String tpgName = tpg.getName();
                if (tpgName.contains(ETAD_PHASE_CORRECTION)) {
                    slaveData.foundETADCorrection = true;
                } else if (tpg.getName().contains(ETAD_HEIGHT)) {
                    slaveData.foundETADHeight = true;
                } else if (tpg.getName().contains(ETAD_GRADIENT)) {
                    slaveData.foundETADGradient = true;
                }
            }
            if (!foundETADTPG && slaveData.foundETADCorrection && slaveData.foundETADHeight && slaveData.foundETADGradient) {
                foundETADTPG = true;
            }
        }
        return foundETADTPG;
    }

    private void coregisterETADTPG() throws Exception {

        Resampling resampling = ResamplingFactory.createResampling(ResamplingFactory.BILINEAR_INTERPOLATION_NAME);
        if (resampling == null) {
            return;
        }

        TPGManager.instance().removeAllTPGs(); // need this line, otherwise cached data from previous run is used

        final ElevationModel dem = DEMFactory.createElevationModel("Copernicus 90m Global DEM",
                ResamplingFactory.BILINEAR_INTERPOLATION_NAME);
        final double demNoDataValue = dem.getDescriptor().getNoDataValue();
        final double demSamplingLat = (double)dem.getDescriptor().getTileWidthInDegrees() /
                (double)dem.getDescriptor().getTileWidth();
        final double demSamplingLon = demSamplingLat;

        int i = 1;
        for(SlaveData slaveData : slaveDataList) {
            final String slvSuffix = StackUtils.SLV + i + StackUtils.createBandTimeStamp(slaveData.slaveProduct);
            final List<Integer> slvBurstIndexList = getBurstIndexList(slaveData.slaveProduct);

            final int[] slvBurstIndexArray = new int[slvBurstIndexList.size()];
            final int[] mstBurstIndexArray = new int[slvBurstIndexList.size()];
            int bc = 0;
            for (final int slvBurstIndex : slvBurstIndexList) {
                final ETADUtils.Burst slvBurst = getBurst(slvBurstIndex, slaveData.slaveProduct.getMetadataRoot());
                final int mstBurstIndex = findMasterBurstIndex(slvBurst, masterProduct.getMetadataRoot());
                slvBurstIndexArray[bc] = slvBurstIndex;
                mstBurstIndexArray[bc++] = mstBurstIndex;
                if (mstBurstIndex == -1) {
                    continue;
                }
                final ETADUtils.Burst mstBurst = getBurst(mstBurstIndex, masterProduct.getMetadataRoot());

                final PixelPos[][] slaveBurstPointPos = computeSlaveBurstPointPosition(slaveData, slvBurst, mstBurst,
                        dem, demNoDataValue, demSamplingLat, demSamplingLon);

                if (slaveBurstPointPos == null) {
                    continue;
                }

                final TiePointGrid[] slvTpgs = slaveData.slaveProduct.getTiePointGrids();
                for (TiePointGrid slvTpg : slvTpgs) {
                    final String tpgName = slvTpg.getName();
                    if (!tpgName.startsWith("etad") || !tpgName.endsWith("_" + slvBurstIndex)) {
                        continue;
                    }

                    final float[] coregisteredSlaveBurst = computeCoregisteredSlaveETADBurst(
                            slvTpg, slaveBurstPointPos, resampling);

                    TPGManager.instance().setTPG(tpgName + slvSuffix, mstBurst.rangeExtent,
                            mstBurst.azimuthExtent, coregisteredSlaveBurst);
                }
            }
            // save mstBurstIndexArray and slvBurstIndexArray to memory
            final String mstSuffix = StackUtils.MST + i + StackUtils.createBandTimeStamp(masterProduct);
            TPGManager.instance().setBurstIndexArray(mstSuffix, mstBurstIndexArray);
            TPGManager.instance().setBurstIndexArray(slvSuffix, slvBurstIndexArray);
            ++i;
        }
    }

    private String[] getTargetSlvTPGList() {

        List<String> list = new LinkedList<String>();
        int i = 1;
        for(SlaveData slaveData : slaveDataList) {
            final String slvSuffix = StackUtils.SLV + i + StackUtils.createBandTimeStamp(slaveData.slaveProduct);
            final List<Integer> burstIndexList = getBurstIndexList(slaveData.slaveProduct);
            for (final int burstIndex : burstIndexList) {
                final TiePointGrid[] tpgs = slaveData.slaveProduct.getTiePointGrids();
                for (TiePointGrid tpg : tpgs) {
                    final String tpgName = tpg.getName();
                    if (tpgName.startsWith("etad") && tpgName.endsWith("_" + burstIndex)) {
                        list.add(tpgName + slvSuffix);
                    }
                }
            }
            ++i;
        }

        return list.toArray(new String[0]);
    }

    private boolean checkTargetTPG(final String[] tgtTPGList) {

        for (String tpgName : tgtTPGList) {
            if (TPGManager.instance().getTPG(tpgName) == null) {
                return false;
            }
        }
        return true;
    }

    private void saveMasterETADTPG() {

        final String mstSuffix = StackUtils.MST + StackUtils.createBandTimeStamp(masterProduct);
        final TiePointGrid[] tpgsMst = masterProduct.getTiePointGrids();
        for (TiePointGrid tpgMst : tpgsMst) {
            final String tpgName = tpgMst.getName();
            if (!tpgName.startsWith("etad")) {
                continue;
            }

            final TiePointGrid tgtTPG = targetProduct.getTiePointGrid(tpgName);
            if (tgtTPG != null) {
                targetProduct.removeTiePointGrid(tgtTPG);
            }

            tpgMst.setName(tpgMst.getName() + mstSuffix);
            targetProduct.addTiePointGrid(tpgMst.cloneTiePointGrid());
        }
    }

    private void saveSlaveETADTPG(final String[] tgtSlvTPGList) {

        for (String tpgName : tgtSlvTPGList) {
            final TiePointGrid tpg = TPGManager.instance().getTPG(tpgName);
            final TiePointGrid tpgSaved = TPGManager.instance().getTPG(tpgName);
            if (tpgSaved == null) {
                continue;
            }

            final String tpgNameInProduct = tpgName.substring(0, tpgName.indexOf("_slv"));
            final TiePointGrid oldTPG = targetProduct.getTiePointGrid(tpgNameInProduct);
            if (oldTPG != null) {
                targetProduct.removeTiePointGrid(oldTPG);
            }
            targetProduct.addTiePointGrid(new TiePointGrid(tpgName, tpg.getGridWidth(), tpg.getGridHeight(),
                    0, 0, 1, 1, tpgSaved.getTiePoints()));
        }
    }

    private void saveSlaveBurstIndexArray() {

        int i = 1;
        for(SlaveData slaveData : slaveDataList) {
            final String slvSuffix = StackUtils.SLV + i + StackUtils.createBandTimeStamp(slaveData.slaveProduct);
            final String mstSuffix = StackUtils.MST + i + StackUtils.createBandTimeStamp(masterProduct);
            final int[] mstBurstIndexArray = TPGManager.instance().getBurstIndexArray(mstSuffix);
            final int[] slvBurstIndexArray = TPGManager.instance().getBurstIndexArray(slvSuffix);
            saveSlaveBurstIndexArray(mstBurstIndexArray, slvBurstIndexArray, slaveData.slaveProduct);
            ++i;
        }
    }

    private void saveSlaveBurstIndexArray(final int[] mstBurstIndexArray, final int[] slvBurstIndexArray,
                                          final Product slaveProduct) {

        MetadataElement slaveElem = targetProduct.getMetadataRoot().getElement(AbstractMetadata.SLAVE_METADATA_ROOT);
        if (slaveElem == null) {
            return;
        }
        final MetadataElement[] slaveRoot = slaveElem.getElements();
        for (MetadataElement meta : slaveRoot) {
            if(meta.getName().contains(slaveProduct.getName())) {
                final MetadataElement etadBurstsElem = new MetadataElement("ETAD_Burst_Index_Array");
                String mstBursts = "", slvBursts = "";
                for (int i = 0; i < mstBurstIndexArray.length; ++i) {
                    if (mstBurstIndexArray[i] != -1) {
                        mstBursts += mstBurstIndexArray[i] + " ";
                        slvBursts += slvBurstIndexArray[i] + " ";
                    }
                }
                etadBurstsElem.setAttributeString("master_bursts", mstBursts);
                etadBurstsElem.setAttributeString("slave_bursts", slvBursts);
                meta.addElement(etadBurstsElem);
            }
        }
    }

    private List<Integer> getBurstIndexList(final Product product) {

        final List<Integer> burstIndexList = new ArrayList<>();
        final TiePointGrid[] tpgs = product.getTiePointGrids();
        for (TiePointGrid tpg : tpgs) {
            final String tpgName = tpg.getName();
            if (tpgName.contains(ETAD_PHASE_CORRECTION)) {
                final int burstIndex = Integer.parseInt(tpgName.substring(tpgName.lastIndexOf('_') + 1));
                if (!burstIndexList.contains(burstIndex)) {
                    burstIndexList.add(burstIndex);
                }
            }
        }
        return burstIndexList;
    }

    private ETADUtils.Burst getBurst(final int burstIndex, final MetadataElement metadataRoot) {

        final MetadataElement etadElem = metadataRoot.getElement("ETAD_Product_Metadata");
        final MetadataElement annotationElem = etadElem.getElement("annotation");
        return ETADUtils.createBurst(burstIndex, annotationElem);
    }

    private int findMasterBurstIndex(final ETADUtils.Burst slvBurst, final MetadataElement mstMetadataRoot) {

        final MetadataElement etadElem = mstMetadataRoot.getElement("ETAD_Product_Metadata");
        final MetadataElement annotationElem = etadElem.getElement("annotation");
        final MetadataElement etadProductElem = annotationElem.getElement("etadProduct");
        final MetadataElement etadBurstListElem = etadProductElem.getElement("etadBurstList");
        final MetadataElement[] elements = etadBurstListElem.getElements();

        for (MetadataElement elem : elements) {
            // ID information
            final MetadataElement burstDataElem = elem.getElement("burstData");
            final int sIdx = Integer.parseInt(burstDataElem.getAttributeString("sIndex"));
            if (sIdx != slvBurst.sIndex) {
                continue;
            }

            // coverage information
            final MetadataElement burstCoverageElem = elem.getElement("burstCoverage");
            final MetadataElement spacialCoverageElem = burstCoverageElem.getElement("spatialCoverage");
            final MetadataElement[] coordinatesElemList = spacialCoverageElem.getElements();

            double maxLatError = 0.0, maxLonError = 0.0;
            for (MetadataElement coordinatesElem : coordinatesElemList) {
                final MetadataElement latitudeElem = coordinatesElem.getElement("latitude");
                final MetadataElement longitudeElem = coordinatesElem.getElement("longitude");
                final double lat = Double.parseDouble(latitudeElem.getAttributeString("latitude"));
                final double lon = Double.parseDouble(longitudeElem.getAttributeString("longitude"));
                final String corner = coordinatesElem.getAttributeString("corner");
                switch (corner) {
                    case "EarlyAzimuthNearRange":
                        maxLatError = Math.max(maxLatError, Math.abs(slvBurst.EarlyAzimuthNearRangeLat - lat));
                        maxLonError = Math.max(maxLonError, Math.abs(slvBurst.EarlyAzimuthNearRangeLon - lon));
                        break;
                    case "EarlyAzimuthFarRange":
                        maxLatError = Math.max(maxLatError, Math.abs(slvBurst.EarlyAzimuthFarRangeLat - lat));
                        maxLonError = Math.max(maxLonError, Math.abs(slvBurst.EarlyAzimuthFarRangeLon - lon));
                        break;
                    case "LateAzimuthNearRange":
                        maxLatError = Math.max(maxLatError, Math.abs(slvBurst.LateAzimuthNearRangeLat - lat));
                        maxLonError = Math.max(maxLonError, Math.abs(slvBurst.LateAzimuthNearRangeLon - lon));
                        break;
                    case "LateAzimuthFarRange":
                        maxLatError = Math.max(maxLatError, Math.abs(slvBurst.LateAzimuthFarRangeLat - lat));
                        maxLonError = Math.max(maxLonError, Math.abs(slvBurst.LateAzimuthFarRangeLon - lon));
                        break;
                }
            }

            if (maxLatError < 0.1 && maxLonError < 0.1) {
                return Integer.parseInt(burstDataElem.getAttributeString("bIndex"));
            }
        }
        return -1;
    }

    private PixelPos[][] computeSlaveBurstPointPosition(final SlaveData slaveData, final ETADUtils.Burst slvBurst,
                                                        final ETADUtils.Burst mstBurst,
                                                        final ElevationModel dem, final double demNoDataValue,
                                                        final double demSamplingLat, final double demSamplingLon)
            throws Exception {

        final double[] latLonMinMax = new double[4];
        computeBurstGeoBoundary(mstBurst, latLonMinMax);

        final int[] latLonDEMIndices = getBoundaryInDEM(latLonMinMax, dem, demSamplingLat, demSamplingLon);
        final int latMinIdx = latLonDEMIndices[0];
        final int latMaxIdx = latLonDEMIndices[1];
        final int lonMinIdx = latLonDEMIndices[2];
        final int lonMaxIdx = latLonDEMIndices[3];

        final int numLines = latMinIdx - latMaxIdx;
        final int numPixels = lonMaxIdx - lonMinIdx;
        double[][] masterAz = new double[numLines][numPixels];
        double[][] masterRg = new double[numLines][numPixels];
        double[][] slaveAz = new double[numLines][numPixels];
        double[][] slaveRg = new double[numLines][numPixels];
        double[][] lat = new double[numLines][numPixels];
        double[][] lon = new double[numLines][numPixels];

        final boolean noValidSlavePixPos = computeMasterSlavePositions(latMaxIdx, lonMinIdx, numLines, numPixels,
                slaveData, mstBurst, slvBurst, dem, demNoDataValue, masterAz, masterRg, slaveAz, slaveRg, lat, lon);

        if (noValidSlavePixPos) {
            return null;
        }

        final org.jlinda.core.Window tileWindow = new org.jlinda.core.Window(
                0, mstBurst.azimuthExtent - 1, 0, mstBurst.rangeExtent - 1);

        final double rgAzRatio = mSU.rangeSpacing / mSU.azimuthSpacing;
        final double[][] latArray = new double[(int)tileWindow.lines()][(int)tileWindow.pixels()];
        final double[][] lonArray = new double[(int)tileWindow.lines()][(int)tileWindow.pixels()];
        final double[][] azArray = new double[(int)tileWindow.lines()][(int)tileWindow.pixels()];
        final double[][] rgArray = new double[(int)tileWindow.lines()][(int)tileWindow.pixels()];
        for (double[] data : azArray) {
            Arrays.fill(data, invalidIndex);
        }
        for (double[] data : rgArray) {
            Arrays.fill(data, invalidIndex);
        }

        TriangleInterpolator.ZData[] dataList = new TriangleInterpolator.ZData[] {
                new TriangleInterpolator.ZData(slaveAz, azArray),
                new TriangleInterpolator.ZData(slaveRg, rgArray),
                new TriangleInterpolator.ZData(lat, latArray),
                new TriangleInterpolator.ZData(lon, lonArray)
        };

        TriangleInterpolator.gridDataLinear(masterAz, masterRg, dataList,
                tileWindow, rgAzRatio, 1, 1, invalidIndex, 0);

        final PixelPos[][] slaveBurstPointPos = new PixelPos[mstBurst.azimuthExtent][mstBurst.rangeExtent];
        for(int yy = 0; yy < mstBurst.azimuthExtent; ++yy) {
            for (int xx = 0; xx < mstBurst.rangeExtent; xx++) {
                if (rgArray[yy][xx] == invalidIndex || azArray[yy][xx] == invalidIndex) {
                    slaveBurstPointPos[yy][xx] = null;
                } else {
                    slaveBurstPointPos[yy][xx] = new PixelPos(rgArray[yy][xx], azArray[yy][xx]);
                }
            }
        }

        return slaveBurstPointPos;
    }

    private int[] getBoundaryInDEM(final double[] latLonMinMax, final ElevationModel dem, final double demSamplingLat,
                                   final double demSamplingLon) throws Exception {

        final double delta = Math.max(demSamplingLat, demSamplingLon);
        final double extralat = 20*delta;
        final double extralon = 20*delta;

        final double latMin = latLonMinMax[0] - extralat;
        final double latMax = latLonMinMax[1] + extralat;
        final double lonMin = latLonMinMax[2] - extralon;
        final double lonMax = latLonMinMax[3] + extralon;

        final PixelPos upperLeft = dem.getIndex(new GeoPos(latMax, lonMin));
        final PixelPos lowerRight = dem.getIndex(new GeoPos(latMin, lonMax));
        final int latMaxIdx = (int)Math.floor(upperLeft.getY());
        final int latMinIdx = (int)Math.ceil(lowerRight.getY());
        final int lonMinIdx = (int)Math.floor(upperLeft.getX());
        final int lonMaxIdx = (int)Math.ceil(lowerRight.getX());

        return new int[]{latMinIdx, latMaxIdx, lonMinIdx, lonMaxIdx};
    }

    private void computeBurstGeoBoundary(final ETADUtils.Burst burst, final double[] latLonMinMax) {

        final double[] lats = {burst.EarlyAzimuthNearRangeLat, burst.EarlyAzimuthFarRangeLat,
                burst.LateAzimuthNearRangeLat, burst.LateAzimuthFarRangeLat};

        final double[] lons = {burst.EarlyAzimuthNearRangeLon, burst.EarlyAzimuthFarRangeLon,
                burst.LateAzimuthNearRangeLon, burst.LateAzimuthFarRangeLon};

        getLatLonMinMax(lats, lons, latLonMinMax);
    }

    private boolean computeMasterSlavePositions(final int latMaxIdx, final int lonMinIdx,
                                                final int numLines, final int numPixels, final SlaveData slaveData,
                                                final ETADUtils.Burst mstBurst, final ETADUtils.Burst slvBurst,
                                                final ElevationModel dem, final double demNoDataValue,
                                                final double[][] masterAz, final double[][] masterRg,
                                                final double[][] slaveAz, final double[][] slaveRg,
                                                final double[][] lat, final double[][] lon) throws Exception {

        final PositionData posData = new PositionData();
        final PixelPos pix = new PixelPos();
        final EarthGravitationalModel96 egm = EarthGravitationalModel96.instance();

        boolean noValidSlavePixPos = true;
        for (int l = 0; l < numLines; ++l) {
            for (int p = 0; p < numPixels; ++p) {

                pix.setLocation(lonMinIdx + p, latMaxIdx + l);
                GeoPos gp = dem.getGeoPos(pix);
                lat[l][p] = gp.lat;
                lon[l][p] = gp.lon;

                Double alt = dem.getElevation(gp);
                if (alt.equals(demNoDataValue) && !maskOutAreaWithoutElevation) { // get corrected elevation for 0
                    alt = (double)egm.getEGM(gp.lat, gp.lon);
                }

                if (!alt.equals(demNoDataValue)) {
                    GeoUtils.geo2xyzWGS84(gp.lat, gp.lon, alt, posData.earthPoint);
                    if(getPosition(mstBurst, mSU, posData)) {

                        masterAz[l][p] = posData.azimuthIndex;
                        masterRg[l][p] = posData.rangeIndex;
                        if (getPosition(slvBurst, slaveData.sSU, posData)) {

                            slaveAz[l][p] = posData.azimuthIndex;
                            slaveRg[l][p] = posData.rangeIndex;
                            noValidSlavePixPos = false;
                            continue;
                        }
                    }
                }
                masterAz[l][p] = invalidIndex;
                masterRg[l][p] = invalidIndex;
            }
        }
        return noValidSlavePixPos;
    }

    private static boolean getPosition(final ETADUtils.Burst burst, final Sentinel1Utils su, final PositionData data) {

        try {
            final double zeroDopplerTimeInDays = SARGeocoding.getZeroDopplerTime(
                    su.lineTimeInterval, su.wavelength, data.earthPoint, su.getOrbit());

            if (zeroDopplerTimeInDays == SARGeocoding.NonValidZeroDopplerTime) {
                return false;
            }

            final double zeroDopplerTime = zeroDopplerTimeInDays * Constants.secondsInDay;
            data.azimuthIndex = (zeroDopplerTime - burst.azimuthTimeMin) / burst.gridSamplingAzimuth;

            final double slantRangeTime = SARGeocoding.computeSlantRange(
                    zeroDopplerTimeInDays, su.getOrbit(), data.earthPoint, data.sensorPos) / Constants.lightSpeed;

            data.rangeIndex = (2.0*slantRangeTime - burst.rangeTimeMin) / burst.gridSamplingRange;

            if (!su.nearRangeOnLeft) {
                data.rangeIndex = burst.rangeExtent - 1 - data.rangeIndex;
            }
            return true;
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException("getPosition", e);
        }
        return false;
    }

    private double[][] oneD2TwoD( final float[] oneD, final int rows, final int cols ) {

        if (oneD.length != (rows*cols)) {
            throw new IllegalArgumentException("Invalid array length");
        }
        double[][] twoD = new double[rows][cols];
        for ( int r = 0; r < rows; ++r) {
            for (int c = 0; c < cols; ++c) {
                twoD[r][c] = oneD[r*cols + c];
            }
        }
        return twoD;
    }

    private float[] twoD2OneD(final double[][] twoD) {

        final int rows = twoD.length;
        final int cols = twoD[0].length;
        final float[] oneD = new float[rows*cols];
        for (int r = 0; r < rows; ++r) {
            for (int c = 0; c < cols; ++c) {
                oneD[r*cols + c] = (float)twoD[r][c];
            }
        }
        return oneD;
    }

    private float[] computeCoregisteredSlaveETADBurst(final TiePointGrid tpgSlaveETADBurst,
                                                      final PixelPos[][] slaveBurstPointPos,
                                                      final Resampling resampling) throws Exception{

        final int sbh = tpgSlaveETADBurst.getGridHeight();
        final int sbw = tpgSlaveETADBurst.getGridWidth();
        final double[][] slaveBurst = oneD2TwoD(tpgSlaveETADBurst.getTiePoints(), sbh, sbw);

        final BurstResamplingRaster resamplingRaster = new BurstResamplingRaster(0.0, slaveBurst);
        final Resampling.Index resamplingIndex = resampling.createIndex();

        final int mbh = slaveBurstPointPos.length;
        final int mbw = slaveBurstPointPos[0].length;
        final double[][] coregisteredSlvBurst = new double[mbh][mbw];
        for (int yy = 0; yy < mbh; ++yy) {
            for (int xx = 0; xx < mbw; ++xx) {
                final PixelPos slavePointPos = slaveBurstPointPos[yy][xx];
                if (slavePointPos == null || slavePointPos.x < 0 || slavePointPos.x >= sbw ||
                        slavePointPos.y < 0 || slavePointPos.y >= sbh) {
                    coregisteredSlvBurst[yy][xx] = noDataValue;
                    continue;
                }
                resampling.computeCornerBasedIndex(slavePointPos.x, slavePointPos.y, sbw, sbh, resamplingIndex);
                coregisteredSlvBurst[yy][xx] = resampling.resample(resamplingRaster, resamplingIndex);
            }
        }
        return twoD2OneD(coregisteredSlvBurst);
    }

    private static class BurstResamplingRaster implements Resampling.Raster {

        private final double[][] data;
        private final double noDataValue;

        public BurstResamplingRaster(final double noDataValue, final double[][] data) {
            this.data = data;
            this.noDataValue = noDataValue;
        }

        public final int getWidth() {
            return data[0].length;
        }

        public final int getHeight() {
            return data.length;
        }

        public boolean getSamples(final int[] x, final int[] y, final double[][] samples) throws Exception {
            boolean allValid = true;

            try {
                double val;
                int i = 0;
                while (i < y.length) {
                    int j = 0;
                    while (j < x.length) {
                        val = data[y[i]][x[j]];
                        if (noDataValue == val) {
                            val = Double.NaN;
                            allValid = false;
                        }
                        samples[i][j] = val;
                        ++j;
                    }
                    ++i;
                }
            } catch (Exception e) {
                SystemUtils.LOG.severe(e.getMessage());
                allValid = false;
            }
            return allValid;
        }
    }

    //^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

    private static void outputToFile(final String filePath, double[][] fbuf) throws IOException {

        try{
            FileOutputStream fos = new FileOutputStream(filePath);
            DataOutputStream dos = new DataOutputStream(fos);

            for (double[] aFbuf : fbuf) {
                for (int j = 0; j < fbuf[0].length; j++) {
                    dos.writeDouble(aFbuf[j]);
                }
            }
            //dos.flush();
            dos.close();
            fos.close();
        }catch(Exception e){
            e.printStackTrace();
        }
    }

    /**
     * Check source product validity.
     */
    private void checkSourceProductValidity() throws OperatorException {

        final MetadataElement mAbsRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct[0]);
        if(mAbsRoot == null) {
            throw new OperatorException("Abstracted Metadata not found");
        }
        final String mAcquisitionMode = mAbsRoot.getAttributeString(AbstractMetadata.ACQUISITION_MODE);
        for(Product product : sourceProduct) {
            final InputProductValidator validator1 = new InputProductValidator(product);
            validator1.checkIfSARProduct();
            validator1.checkIfSentinel1Product();
            validator1.checkIfSLC();

            final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
            if(absRoot == null) {
                throw new OperatorException("Abstracted Metadata not found");
            }
            final String acquisitionMode = absRoot.getAttributeString(AbstractMetadata.ACQUISITION_MODE);
            if (!mAcquisitionMode.equals(acquisitionMode)) {
                throw new OperatorException("Source products should have the same acquisition modes");
            }
        }
    }

    /**
     * Create target product.
     */
    private void createTargetProduct() {

        targetProduct = new Product(
                masterProduct.getName() + PRODUCT_SUFFIX,
                masterProduct.getProductType(),
                masterProduct.getSceneRasterWidth(),
                masterProduct.getSceneRasterHeight());

        ProductUtils.copyProductNodes(masterProduct, targetProduct);

        final String[] masterBandNames = masterProduct.getBandNames();
        mstSuffix = StackUtils.MST + StackUtils.createBandTimeStamp(masterProduct);
        for (String bandName : masterBandNames) {
            final Band srcBand = masterProduct.getBand(bandName);
            if (srcBand instanceof VirtualBand) {
                continue;
            }

            Band targetBand;
            if (disableReramp) {
                targetBand = new Band(bandName + mstSuffix, ProductData.TYPE_FLOAT32,
                        srcBand.getRasterWidth(), srcBand.getRasterHeight());

                targetBand.setUnit(srcBand.getUnit());
                targetBand.setDescription(srcBand.getDescription());
                targetProduct.addBand(targetBand);

            } else {
                targetBand = ProductUtils.copyBand(bandName, masterProduct, bandName + mstSuffix,
                        targetProduct, true);
            }

            if(targetBand != null && targetBand.getUnit() != null && targetBand.getUnit().equals(Unit.IMAGINARY)) {
                int idx = targetProduct.getBandIndex(targetBand.getName());
                ReaderUtils.createVirtualIntensityBand(targetProduct, targetProduct.getBandAt(idx-1), targetBand, mstSuffix);
            }
        }

        final Band masterBand = masterProduct.getBand(masterBandNames[0]);
        final int masterBandWidth = masterBand.getRasterWidth();
        final int masterBandHeight = masterBand.getRasterHeight();

        if (outputDerampDemodPhase) {
            final Band mstPhaseBand = new Band(
                    "derampDemodPhase" + mstSuffix,
                    ProductData.TYPE_FLOAT32,
                    masterBandWidth,
                    masterBandHeight);

            mstPhaseBand.setUnit("radian");
            targetProduct.addBand(mstPhaseBand);
        }

        int i = 1;
        for(SlaveData slaveData : slaveDataList) {
            final String[] slaveBandNames = slaveData.slaveProduct.getBandNames();
            final String slvSuffix = StackUtils.SLV + i + StackUtils.createBandTimeStamp(slaveData.slaveProduct);
            slaveData.slvSuffix = slvSuffix;
            for (String bandName : slaveBandNames) {
                final Band srcBand = slaveData.slaveProduct.getBand(bandName);
                if (srcBand instanceof VirtualBand) {
                    continue;
                }

                final Band targetBand = new Band(
                        bandName + slvSuffix,
                        ProductData.TYPE_FLOAT32,
                        masterBandWidth,
                        masterBandHeight);

                targetBand.setUnit(srcBand.getUnit());
                targetBand.setDescription(srcBand.getDescription());
                if (maskOutAreaWithoutElevation) {
                    targetBand.setNoDataValueUsed(true);
                    targetBand.setNoDataValue(noDataValue);
                }
                targetProduct.addBand(targetBand);
                targetBandToSlaveBandMap.put(targetBand, srcBand);

                if (targetBand.getUnit().equals(Unit.IMAGINARY)) {
                    int idx = targetProduct.getBandIndex(targetBand.getName());
                    ReaderUtils.createVirtualIntensityBand(targetProduct, targetProduct.getBandAt(idx - 1), targetBand, slvSuffix);
                }

                targetBandToSlaveDataMap.put(targetBand, slaveData);
            }

            copySlaveMetadata(slaveData.slaveProduct);

            if (outputRangeAzimuthOffset) {
                final Band azOffsetBand = new Band(
                        "azOffset" + slvSuffix,
                        ProductData.TYPE_FLOAT32,
                        masterBandWidth,
                        masterBandHeight);

                azOffsetBand.setUnit("Index");
                targetProduct.addBand(azOffsetBand);

                final Band rgOffsetBand = new Band(
                        "rgOffset" + slvSuffix,
                        ProductData.TYPE_FLOAT32,
                        masterBandWidth,
                        masterBandHeight);

                rgOffsetBand.setUnit("Index");
                targetProduct.addBand(rgOffsetBand);
            }

            if (outputDerampDemodPhase) {
                final Band slvPhaseBand = new Band(
                        "derampDemodPhase" + slvSuffix,
                        ProductData.TYPE_FLOAT32,
                        masterBandWidth,
                        masterBandHeight);

                slvPhaseBand.setUnit("radian");
                targetProduct.addBand(slvPhaseBand);
            }
            ++i;
        }

        // set non-elevation areas to no data value for the master bands using the slave bands
        DEMAssistedCoregistrationOp.setMasterValidPixelExpression(targetProduct, maskOutAreaWithoutElevation);

        if (outputDEM) {
            final Band elevBand = new Band(
                    "elevation",
                    ProductData.TYPE_FLOAT32,
                    masterBandWidth,
                    masterBandHeight);

            elevBand.setUnit(Unit.METERS);
            targetProduct.addBand(elevBand);
        }
    }

    private void copySlaveMetadata(final Product slaveProduct) {

        final MetadataElement targetSlaveMetadataRoot = AbstractMetadata.getSlaveMetadata(targetProduct.getMetadataRoot());
        final MetadataElement slvAbsMetadata = AbstractMetadata.getAbstractedMetadata(slaveProduct);
        if (slvAbsMetadata != null) {
            final String timeStamp = StackUtils.createBandTimeStamp(slaveProduct);
            final MetadataElement targetSlaveMetadata = new MetadataElement(slaveProduct.getName() + timeStamp);
            targetSlaveMetadataRoot.addElement(targetSlaveMetadata);
            ProductUtils.copyMetadata(slvAbsMetadata, targetSlaveMetadata);
        }
    }

    /**
     * Update target product metadata.
     */
    private void updateTargetProductMetadata() {

        final MetadataElement absTgt = AbstractMetadata.getAbstractedMetadata(targetProduct);
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.coregistered_stack, 1);

        final MetadataElement inputElem = ProductInformation.getInputProducts(targetProduct);
        for(SlaveData slaveData : slaveDataList) {
            final MetadataElement slvInputElem = ProductInformation.getInputProducts(slaveData.slaveProduct);
            final MetadataAttribute[] slvInputProductAttrbList = slvInputElem.getAttributes();
            for (MetadataAttribute attrib : slvInputProductAttrbList) {
                final MetadataAttribute inputAttrb = AbstractMetadata.addAbstractedAttribute(
                        inputElem, "InputProduct", ProductData.TYPE_ASCII, "", "");
                inputAttrb.getData().setElems(attrib.getData().getElemString());
            }
        }

        CreateStackOp.getBaselines(sourceProduct, targetProduct);
    }

    /**
     * Called by the framework in order to compute a tile for the given target band.
     * <p>The default implementation throws a runtime exception with the message "not implemented".</p>
     *
     * @param targetTileMap   The target tiles associated with all target bands to be computed.
     * @param targetRectangle The rectangle of target tile.
     * @param pm              A progress monitor which should be used to determine computation cancelation requests.
     * @throws OperatorException
     *          If an error occurs during computation of the target raster.
     */
    @Override
     public void computeTileStack(Map<Band, Tile> targetTileMap, Rectangle targetRectangle, ProgressMonitor pm)
             throws OperatorException {

        try {
            final int tx0 = targetRectangle.x;
            final int ty0 = targetRectangle.y;
            final int tw = targetRectangle.width;
            final int th = targetRectangle.height;
            final int tyMax = ty0 + th;
            //System.out.println("tx0 = " + tx0 + ", ty0 = " + ty0 + ", tw = " + tw + ", th = " + th);

            if (!isElevationModelAvailable) {
                getElevationModel();
            }

            if (!burstOffsetComputed) {
                computeBurstOffset();
            }

            for (int burstIndex = 0; burstIndex < mSubSwath[subSwathIndex - 1].numOfBursts; burstIndex++) {
                final int firstLineIdx = burstIndex*mSubSwath[subSwathIndex - 1].linesPerBurst;
                final int lastLineIdx = firstLineIdx + mSubSwath[subSwathIndex - 1].linesPerBurst - 1;

                if (tyMax <= firstLineIdx || ty0 > lastLineIdx) {
                    continue;
                }

                final int ntx0 = tx0;
                final int ntw = tw;
                final int nty0 = Math.max(ty0, firstLineIdx);
                final int ntyMax = Math.min(tyMax, lastLineIdx + 1);
                final int nth = ntyMax - nty0;
                //System.out.println("burstIndex = " + burstIndex + ": ntx0 = " + ntx0 + ", nty0 = " + nty0 + ", ntw = " + ntw + ", nth = " + nth);

                double[] extendedAmount = {0.0, 0.0, 0.0, 0.0};
                computeExtendedAmount(ntx0, nty0, ntw, nth, extendedAmount);

                for(SlaveData slaveData : slaveDataList) {
                    //slaveData.print();

                    computePartialTile(subSwathIndex, burstIndex, ntx0, nty0, ntw, nth, targetTileMap,
                            slaveData, extendedAmount);
                }
            }

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        } finally {
            pm.done();
        }
    }

    /**
     * Get elevation model.
     *
     * @throws Exception The exceptions.
     */
    private synchronized void getElevationModel() throws Exception {

        if (isElevationModelAvailable) return;
        try {
            if (externalDEMFile != null) { // if external DEM file is specified by user
                dem = new FileElevationModel(externalDEMFile, demResamplingMethod, externalDEMNoDataValue);
                demNoDataValue = externalDEMNoDataValue;
                demName = externalDEMFile.getPath();
                try {
                    demSamplingLat = Math.abs(dem.getGeoPos(new PixelPos(0, 1)).getLat() -
                            dem.getGeoPos(new PixelPos(0, 0)).getLat());

                    demSamplingLon = Math.abs(dem.getGeoPos(new PixelPos(1, 0)).getLon() -
                            dem.getGeoPos(new PixelPos(0, 0)).getLon());
                } catch (Exception e) {
                    throw new OperatorException("The DEM '" + demName + "' cannot be properly interpreted.");
                }

            } else {
                dem = DEMFactory.createElevationModel(demName, demResamplingMethod);
                demNoDataValue = dem.getDescriptor().getNoDataValue();
                demSamplingLat = (double)dem.getDescriptor().getTileWidthInDegrees() /
                        (double)dem.getDescriptor().getTileWidth();
                demSamplingLon = demSamplingLat;
            }
        } catch (Throwable t) {
            SystemUtils.LOG.severe("Unable to get elevation model: " + t.getMessage());
        }
        isElevationModelAvailable = true;
    }

    private synchronized void computeBurstOffset() throws Exception {

        if (burstOffsetComputed) return;
        try {
            final int h = mSubSwath[subSwathIndex - 1].latitude.length;
            final int w = mSubSwath[subSwathIndex - 1].latitude[0].length;
            final PosVector earthPoint = new PosVector();
            for (int i = 0; i < h; i++) {
                for (int j = 0; j < w; j++) {
                    final double lat = mSubSwath[subSwathIndex - 1].latitude[i][j];
                    final double lon = mSubSwath[subSwathIndex - 1].longitude[i][j];
                    final Double alt = dem.getElevation(new GeoPos(lat, lon));
                    if (alt.equals(demNoDataValue)) {
                        continue;
                    }
                    GeoUtils.geo2xyzWGS84(lat, lon, alt, earthPoint);
                    final BurstIndices mBurstIndices = getBurstIndices(subSwathIndex, mSU, earthPoint);

                    if (mBurstIndices == null) {
                        continue;
                    }

                    for(SlaveData slaveData : slaveDataList) {
                        if(slaveData.burstOffset != -9999)
                            continue;

                        final Sentinel1Utils sSU = slaveData.sSU;
                        final BurstIndices sBurstIndices = getBurstIndices(subSwathIndex, sSU, earthPoint);
                        if (mBurstIndices == null || sBurstIndices == null ||
                                (mBurstIndices.firstBurstIndex == -1 && mBurstIndices.secondBurstIndex == -1) ||
                                (sBurstIndices.firstBurstIndex == -1 && sBurstIndices.secondBurstIndex == -1)) {
                            continue;
                        }

                        if (mBurstIndices.inUpperPartOfFirstBurst == sBurstIndices.inUpperPartOfFirstBurst) {
                            slaveData.burstOffset = sBurstIndices.firstBurstIndex - mBurstIndices.firstBurstIndex;
                        } else if (sBurstIndices.secondBurstIndex != -1 &&
                                mBurstIndices.inUpperPartOfFirstBurst == sBurstIndices.inUpperPartOfSecondBurst) {
                            slaveData.burstOffset = sBurstIndices.secondBurstIndex - mBurstIndices.firstBurstIndex;
                        } else if (mBurstIndices.secondBurstIndex != -1 &&
                                mBurstIndices.inUpperPartOfSecondBurst == sBurstIndices.inUpperPartOfFirstBurst) {
                            slaveData.burstOffset = sBurstIndices.firstBurstIndex - mBurstIndices.secondBurstIndex;
                        } else if (mBurstIndices.secondBurstIndex != -1 && sBurstIndices.secondBurstIndex != -1 &&
                                mBurstIndices.inUpperPartOfSecondBurst == sBurstIndices.inUpperPartOfSecondBurst) {
                            slaveData.burstOffset = sBurstIndices.secondBurstIndex - mBurstIndices.secondBurstIndex;
                        }
                    }

                    boolean allComputed = true;
                    for(SlaveData slaveData : slaveDataList) {
                        if (slaveData.burstOffset == -9999) {
                            allComputed = false;
                            break;
                        }
                    }
                    if(!allComputed)
                        continue;

                    burstOffsetComputed = true;
                    return;
                }
            }

            for(SlaveData slaveData : slaveDataList) {
                slaveData.burstOffset = 0;
            }
            burstOffsetComputed = true;

        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static BurstIndices getBurstIndices(final int subSwathIndex, final Sentinel1Utils su,
                                                final PosVector earthPoint) {

        try {
            Sentinel1Utils.SubSwathInfo subSwath = su.getSubSwath()[subSwathIndex - 1];

            final double zeroDopplerTimeInDays = SARGeocoding.getZeroDopplerTime(
                    su.lineTimeInterval, su.wavelength, earthPoint, su.getOrbit());

            if (zeroDopplerTimeInDays == SARGeocoding.NonValidZeroDopplerTime) {
                return null;
            }

            final double zeroDopplerTime = zeroDopplerTimeInDays * Constants.secondsInDay;

            BurstIndices burstIndices = new BurstIndices();
            int k = 0;
            for (int i = 0; i < subSwath.numOfBursts; i++) {
                if (zeroDopplerTime >= subSwath.burstFirstLineTime[i] && zeroDopplerTime < subSwath.burstLastLineTime[i]) {
                    boolean inUpperPartOfBurst = (zeroDopplerTime >=
                            (subSwath.burstFirstLineTime[i] + subSwath.burstLastLineTime[i])/2.0);

                    if (k == 0) {
                        burstIndices.firstBurstIndex = i;
                        burstIndices.inUpperPartOfFirstBurst = inUpperPartOfBurst;
                    } else {
                        burstIndices.secondBurstIndex = i;
                        burstIndices.inUpperPartOfSecondBurst = inUpperPartOfBurst;
                        break;
                    }
                    ++k;
                }
            }
            return burstIndices;

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException("getBurstIndices", e);
        }
        return null;
    }

    private void computeExtendedAmount(final int x0, final int y0, final int w, final int h,
                                       final double[] extendedAmount)
            throws Exception {

        final EarthGravitationalModel96 egm = EarthGravitationalModel96.instance();

        final GeoPos geoPos = new GeoPos();
        final PositionData posData = new PositionData();
        double azExtendedAmountMax = -Double.MAX_VALUE;
        double azExtendedAmountMin = Double.MAX_VALUE;
        double rgExtendedAmountMax = -Double.MAX_VALUE;
        double rgExtendedAmountMin = Double.MAX_VALUE;

        for (int y = y0; y < y0 + h; y += 20) {
            final int burstIndex = getBurstIndex(y);

            for (int x = x0; x < x0 + w; x += 20) {
                final double azTime = getAzimuthTime(y, burstIndex);
                final double rgTime = getSlantRangeTime(x);
                final double lat = mSU.getLatitude(azTime, rgTime, subSwathIndex);
                final double lon = mSU.getLongitude(azTime, rgTime, subSwathIndex);
                geoPos.setLocation(lat, lon);
                Double alt = dem.getElevation(geoPos);
                if (alt.equals(demNoDataValue)) {
                    alt = (double)egm.getEGM(lat, lon);
                }

                GeoUtils.geo2xyzWGS84(geoPos.getLat(), geoPos.getLon(), alt, posData.earthPoint);

                if (getPosition(subSwathIndex, burstIndex, mSU, posData)) {
                    double azExtendedAmount = posData.azimuthIndex - y;
                    double rgExtendedAmount = posData.rangeIndex - x;
                    if (azExtendedAmount > azExtendedAmountMax) {
                        azExtendedAmountMax = azExtendedAmount;
                    }
                    if (azExtendedAmount < azExtendedAmountMin) {
                        azExtendedAmountMin = azExtendedAmount;
                    }
                    if (rgExtendedAmount > rgExtendedAmountMax) {
                        rgExtendedAmountMax = rgExtendedAmount;
                    }
                    if (rgExtendedAmount < rgExtendedAmountMin) {
                        rgExtendedAmountMin = rgExtendedAmount;
                    }
                }
            }
        }

        if (azExtendedAmountMin != Double.MAX_VALUE && azExtendedAmountMin < 0.0) {
            extendedAmount[0] = azExtendedAmountMin;
        } else {
            extendedAmount[0] = 0.0;
        }

        if (azExtendedAmountMax != -Double.MAX_VALUE && azExtendedAmountMax > 0.0) {
            extendedAmount[1] = azExtendedAmountMax;
        } else {
            extendedAmount[1] = 0.0;
        }

        if (rgExtendedAmountMin != Double.MAX_VALUE && rgExtendedAmountMin < 0.0) {
            extendedAmount[2] = rgExtendedAmountMin;
        } else {
            extendedAmount[2] = 0.0;
        }

        if (rgExtendedAmountMax != -Double.MAX_VALUE && rgExtendedAmountMax > 0.0) {
            extendedAmount[3] = rgExtendedAmountMax;
        } else {
            extendedAmount[3] = 0.0;
        }
    }

    private int getBurstIndex(final int y) {
        for (int burstIndex = 0; burstIndex < mSubSwath[subSwathIndex - 1].numOfBursts; burstIndex++) {
            final int firstLineIdx = burstIndex*mSubSwath[subSwathIndex - 1].linesPerBurst;
            final int lastLineIdx = firstLineIdx + mSubSwath[subSwathIndex - 1].linesPerBurst - 1;
            if (y >= firstLineIdx && y <= lastLineIdx) {
                return burstIndex;
            }
        }
        return -1;
    }

    private double getAzimuthTime(final int y, final int burstIndex) {
        final Sentinel1Utils.SubSwathInfo subSwath = mSubSwath[subSwathIndex - 1];
        return subSwath.burstFirstLineTime[burstIndex] +
                (y - burstIndex * subSwath.linesPerBurst) * subSwath.azimuthTimeInterval;
    }

    private double getSlantRangeTime(final int x) {
        return mSubSwath[subSwathIndex - 1].slrTimeToFirstPixel + x * mSU.rangeSpacing / Constants.lightSpeed;
    }

    private void computePartialTile(final int subSwathIndex, final int mBurstIndex,
                                    final int x0, final int y0, final int w, final int h,
                                    final Map<Band, Tile> targetTileMap, final SlaveData slaveData,
                                    final double[] extendedAmount)
            throws Exception {

        final int sBurstIndex = mBurstIndex + slaveData.burstOffset;
        if (sBurstIndex < 0 || sBurstIndex >= slaveData.sSU.getSubSwath()[subSwathIndex - 1].numOfBursts) {
            return;
        }

        double[][] elevation = null;
        if (outputDEM) {
            elevation = new double[h][w];
        }

        final PixelPos[][] slavePixPos = new PixelPos[h][w];
        final boolean isSuccessful = computeSlavePixPos(
                subSwathIndex, mBurstIndex, sBurstIndex, x0, y0, w, h, extendedAmount, slavePixPos, slaveData, elevation);

        if (!isSuccessful) {
            return;
        }

        if (outputRangeAzimuthOffset) {
            outputRangeAzimuthOffsets(x0, y0, w, h, targetTileMap, slavePixPos, subSwathIndex, slaveData,
                    mBurstIndex, sBurstIndex);
        }

        if (outputDEM) {
            outputDEM(x0, y0, w, h, targetTileMap, elevation);
        }

        final int margin = selectedResampling.getKernelSize();
        final Rectangle sourceRectangle = getBoundingBox(slavePixPos, margin, subSwathIndex, sBurstIndex,
                slaveData.sSU.getSubSwath());

        if (sourceRectangle == null) {
            return;
        }

        final double[][] slvDerampDemodPhase = slaveData.sSU.computeDerampDemodPhase(slaveData.sSU.getSubSwath(),
                subSwathIndex, sBurstIndex, sourceRectangle);

        if (slvDerampDemodPhase == null) {
            return;
        }

        final Rectangle targetRectangle = new Rectangle(x0, y0, w, h);
        final double[][] mstDerampDemodPhase = mSU.computeDerampDemodPhase(mSubSwath,
                subSwathIndex, mBurstIndex, targetRectangle);

        if (mstDerampDemodPhase == null) {
            return;
        }

        for(String polarization : mSU.getPolarizations()) {

            // master bands
            if (disableReramp) {
                final Band masterBandI = getBand(masterProduct, "i_", swathIndexStr, polarization);
                final Band masterBandQ = getBand(masterProduct, "q_", swathIndexStr, polarization);
                final Tile masterTileI = getSourceTile(masterBandI, targetRectangle);
                final Tile masterTileQ = getSourceTile(masterBandQ, targetRectangle);

                if (masterTileI == null || masterTileQ == null) {
                    return;
                }

                final double[][] mstDerampDemodI = new double[targetRectangle.height][targetRectangle.width];
                final double[][] mstDerampDemodQ = new double[targetRectangle.height][targetRectangle.width];

                performDerampDemod(masterTileI, masterTileQ, targetRectangle, mstDerampDemodPhase,
                        mstDerampDemodI, mstDerampDemodQ);

                saveMasterBands(x0, y0, w, h, targetTileMap, mstDerampDemodPhase, mstDerampDemodI,
                        mstDerampDemodQ, polarization);
            }

            // slave bands
            final Band slaveBandI = getBand(slaveData.slaveProduct, "i_", swathIndexStr, polarization);
            final Band slaveBandQ = getBand(slaveData.slaveProduct, "q_", swathIndexStr, polarization);
            final Tile slaveTileI = getSourceTile(slaveBandI, sourceRectangle);
            final Tile slaveTileQ = getSourceTile(slaveBandQ, sourceRectangle);

            if (slaveTileI == null || slaveTileQ == null) {
                return;
            }

            final double[][] slvDerampDemodI = new double[sourceRectangle.height][sourceRectangle.width];
            final double[][] slvDerampDemodQ = new double[sourceRectangle.height][sourceRectangle.width];

            performDerampDemod(slaveTileI, slaveTileQ, sourceRectangle, slvDerampDemodPhase,
                    slvDerampDemodI, slvDerampDemodQ);

            performInterpolation(x0, y0, w, h, sourceRectangle, slaveTileI, slaveTileQ, targetTileMap, slvDerampDemodPhase,
                    slvDerampDemodI, slvDerampDemodQ, slavePixPos, subSwathIndex, sBurstIndex, slaveData, polarization);
        }

    }

    private boolean computeSlavePixPos(final int subSwathIndex, final int mBurstIndex, final int sBurstIndex,
                                       final int x0, final int y0, final int w, final int h,
                                       final double[] extendedAmount, final PixelPos[][] slavePixelPos,
                                       final SlaveData slaveData,
                                       final double[][] elevation)
            throws Exception {

        try {
            final int xmin = x0 - (int)extendedAmount[3];
            final int ymin = y0 - (int)extendedAmount[1];
            final int ymax = y0 + h + (int)Math.abs(extendedAmount[0]);
            final int xmax = x0 + w + (int)Math.abs(extendedAmount[2]);

            // Compute lat/lon boundaries (with extensions) for target tile
            final double[] latLonMinMax = new double[4];
            computeImageGeoBoundary(subSwathIndex, mBurstIndex, xmin, xmax, ymin, ymax, latLonMinMax);

            final int[] latLonDEMIndices = getBoundaryInDEM(latLonMinMax);
            final int latMinIdx = latLonDEMIndices[0];
            final int latMaxIdx = latLonDEMIndices[1];
            final int lonMinIdx = latLonDEMIndices[2];
            final int lonMaxIdx = latLonDEMIndices[3];

            // Loop through all DEM points bounded by the indices computed above. For each point,
            // get its lat/lon and its azimuth/range indices in target image;
            final int numLines = latMinIdx - latMaxIdx;
            final int numPixels = lonMaxIdx - lonMinIdx;
            double[][] masterAz = new double[numLines][numPixels];
            double[][] masterRg = new double[numLines][numPixels];
            double[][] slaveAz = new double[numLines][numPixels];
            double[][] slaveRg = new double[numLines][numPixels];
            double[][] lat = new double[numLines][numPixels];
            double[][] lon = new double[numLines][numPixels];
            final PositionData posData = new PositionData();
            final PixelPos pix = new PixelPos();

            final EarthGravitationalModel96 egm = EarthGravitationalModel96.instance();

            boolean noValidSlavePixPos = true;
            for (int l = 0; l < numLines; l++) {
                for (int p = 0; p < numPixels; p++) {

                    pix.setLocation(lonMinIdx + p, latMaxIdx + l);
                    GeoPos gp = dem.getGeoPos(pix);
                    lat[l][p] = gp.lat;
                    lon[l][p] = gp.lon;

                    Double alt = dem.getElevation(gp);
                    if (alt.equals(demNoDataValue) && !maskOutAreaWithoutElevation) { // get corrected elevation for 0
                        alt = (double)egm.getEGM(gp.lat, gp.lon);
                    }

                    if (!alt.equals(demNoDataValue)) {
                        GeoUtils.geo2xyzWGS84(gp.lat, gp.lon, alt, posData.earthPoint);
                        if(getPosition(subSwathIndex, mBurstIndex, mSU, posData)) {

                            masterAz[l][p] = posData.azimuthIndex;
                            masterRg[l][p] = posData.rangeIndex;
                            if (getPosition(subSwathIndex, sBurstIndex, slaveData.sSU, posData)) {

                                slaveAz[l][p] = posData.azimuthIndex;
                                slaveRg[l][p] = posData.rangeIndex;
                                noValidSlavePixPos = false;
                                continue;
                            }
                        }
                    }

                    masterAz[l][p] = invalidIndex;
                    masterRg[l][p] = invalidIndex;
                }
            }

            if (noValidSlavePixPos) {
                return false;
            }

            // Compute azimuth/range offsets for pixels in target tile using Delaunay interpolation
            final org.jlinda.core.Window tileWindow = new org.jlinda.core.Window(y0, y0 + h - 1, x0, x0 + w - 1);

            //final double rgAzRatio = computeRangeAzimuthSpacingRatio(w, h, latLonMinMax);
            final double rgAzRatio = mSU.rangeSpacing / mSU.azimuthSpacing;

            final double[][] latArray = new double[(int)tileWindow.lines()][(int)tileWindow.pixels()];
            final double[][] lonArray = new double[(int)tileWindow.lines()][(int)tileWindow.pixels()];
            final double[][] azArray = new double[(int)tileWindow.lines()][(int)tileWindow.pixels()];
            final double[][] rgArray = new double[(int)tileWindow.lines()][(int)tileWindow.pixels()];
            for (double[] data : azArray) {
                Arrays.fill(data, invalidIndex);
            }
            for (double[] data : rgArray) {
                Arrays.fill(data, invalidIndex);
            }

            TriangleInterpolator.ZData[] dataList = new TriangleInterpolator.ZData[] {
                    new TriangleInterpolator.ZData(slaveAz, azArray),
                    new TriangleInterpolator.ZData(slaveRg, rgArray),
                    new TriangleInterpolator.ZData(lat, latArray),
                    new TriangleInterpolator.ZData(lon, lonArray)
            };

            TriangleInterpolator.gridDataLinear(masterAz, masterRg, dataList,
                    tileWindow, rgAzRatio, 1, 1, invalidIndex, 0);

            boolean allElementsAreNull = true;
            Double alt;
            for(int yy = 0; yy < h; yy++) {
                for (int xx = 0; xx < w; xx++) {
                    if (rgArray[yy][xx] == invalidIndex || azArray[yy][xx] == invalidIndex) {
                        slavePixelPos[yy][xx] = null;
                    } else {
                        if (maskOutAreaWithoutElevation || elevation != null) {
                            alt = dem.getElevation(new GeoPos(latArray[yy][xx], lonArray[yy][xx]));
                            if(elevation != null) {
                                elevation[yy][xx] = alt;
                            }
                            if (!alt.equals(demNoDataValue)) {
                                slavePixelPos[yy][xx] = new PixelPos(rgArray[yy][xx], azArray[yy][xx]);
                                allElementsAreNull = false;
                            } else {
                                slavePixelPos[yy][xx] = null;
                            }
                        } else {
                            slavePixelPos[yy][xx] = new PixelPos(rgArray[yy][xx], azArray[yy][xx]);
                            allElementsAreNull = false;
                        }
                    }
                }
            }

            return !allElementsAreNull;

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException("computeSlavePixPos", e);
        }

        return false;
    }

    /**
     * Compute source image geodetic boundary (minimum/maximum latitude/longitude) from the its corner
     * latitude/longitude.
     *
     * @throws Exception The exceptions.
     */
    private void computeImageGeoBoundary(final int subSwathIndex, final int burstIndex,
                                         final int xMin, final int xMax, final int yMin, final int yMax,
                                         double[] latLonMinMax) throws Exception {

        final Sentinel1Utils.SubSwathInfo subSwath = mSubSwath[subSwathIndex - 1];

        final double azTimeMin = subSwath.burstFirstLineTime[burstIndex] +
                (yMin - burstIndex * subSwath.linesPerBurst) * subSwath.azimuthTimeInterval;

        final double azTimeMax = subSwath.burstFirstLineTime[burstIndex] +
                (yMax - burstIndex * subSwath.linesPerBurst) * subSwath.azimuthTimeInterval;

        final double rgTimeMin = subSwath.slrTimeToFirstPixel + xMin * mSU.rangeSpacing / Constants.lightSpeed;

        final double rgTimeMax = subSwath.slrTimeToFirstPixel + xMax * mSU.rangeSpacing / Constants.lightSpeed;

        final double latUL = mSU.getLatitude(azTimeMin, rgTimeMin, subSwathIndex);
        final double lonUL = mSU.getLongitude(azTimeMin, rgTimeMin, subSwathIndex);
        final double latUR = mSU.getLatitude(azTimeMin, rgTimeMax, subSwathIndex);
        final double lonUR = mSU.getLongitude(azTimeMin, rgTimeMax, subSwathIndex);
        final double latLL = mSU.getLatitude(azTimeMax, rgTimeMin, subSwathIndex);
        final double lonLL = mSU.getLongitude(azTimeMax, rgTimeMin, subSwathIndex);
        final double latLR = mSU.getLatitude(azTimeMax, rgTimeMax, subSwathIndex);
        final double lonLR = mSU.getLongitude(azTimeMax, rgTimeMax, subSwathIndex);

        final double[] lats = {latUL, latUR, latLL, latLR};
        final double[] lons = {lonUL, lonUR, lonLL, lonLR};
        getLatLonMinMax(lats, lons, latLonMinMax);
    }

    private void getLatLonMinMax(final double[] lats, final double[] lons, final double[] latLonMinMax) {
        double latMin = 90.0;
        double latMax = -90.0;
        for (double lat : lats) {
            if (lat < latMin) {
                latMin = lat;
            }
            if (lat > latMax) {
                latMax = lat;
            }
        }

        double lonMin = 180.0;
        double lonMax = -180.0;
        for (double lon : lons) {
            if (lon < lonMin) {
                lonMin = lon;
            }
            if (lon > lonMax) {
                lonMax = lon;
            }
        }

        latLonMinMax[0] = latMin;
        latLonMinMax[1] = latMax;
        latLonMinMax[2] = lonMin;
        latLonMinMax[3] = lonMax;
    }

    private int[] getBoundaryInDEM(final double[] latLonMinMax) throws Exception {

        final double delta = Math.max(demSamplingLat, demSamplingLon);
        final double extralat = 20*delta;
        final double extralon = 20*delta;

        final double latMin = latLonMinMax[0] - extralat;
        final double latMax = latLonMinMax[1] + extralat;
        final double lonMin = latLonMinMax[2] - extralon;
        final double lonMax = latLonMinMax[3] + extralon;

        // Compute lat/lon indices in DEM for the boundaries;
        final PixelPos upperLeft = dem.getIndex(new GeoPos(latMax, lonMin));
        final PixelPos lowerRight = dem.getIndex(new GeoPos(latMin, lonMax));
        final int latMaxIdx = (int)Math.floor(upperLeft.getY());
        final int latMinIdx = (int)Math.ceil(lowerRight.getY());
        final int lonMinIdx = (int)Math.floor(upperLeft.getX());
        final int lonMaxIdx = (int)Math.ceil(lowerRight.getX());

        return new int[]{latMinIdx, latMaxIdx, lonMinIdx, lonMaxIdx};
    }

    /**
     * Compute azimuth and range indices in SAR image for a given target point on the Earth's surface.
     */
    private static boolean getPosition(final int subSwathIndex, final int burstIndex, final Sentinel1Utils su,
                                       final PositionData data) {

        try {
            Sentinel1Utils.SubSwathInfo subSwath = su.getSubSwath()[subSwathIndex - 1];

            final double zeroDopplerTimeInDays = SARGeocoding.getZeroDopplerTime(
                    su.lineTimeInterval, su.wavelength, data.earthPoint, su.getOrbit());

            if (zeroDopplerTimeInDays == SARGeocoding.NonValidZeroDopplerTime) {
                return false;
            }

            final double zeroDopplerTime = zeroDopplerTimeInDays * Constants.secondsInDay;

            data.azimuthIndex = burstIndex * subSwath.linesPerBurst +
                    (zeroDopplerTime - subSwath.burstFirstLineTime[burstIndex]) / subSwath.azimuthTimeInterval;

            final double slantRange = SARGeocoding.computeSlantRange(
                    zeroDopplerTimeInDays, su.getOrbit(), data.earthPoint, data.sensorPos);

            if (!su.srgrFlag) {
                data.rangeIndex = (slantRange - subSwath.slrTimeToFirstPixel*Constants.lightSpeed) / su.rangeSpacing;
            } else {
                data.rangeIndex = SARGeocoding.computeRangeIndex(
                        su.srgrFlag, su.sourceImageWidth, su.firstLineUTC, su.lastLineUTC,
                        su.rangeSpacing, zeroDopplerTimeInDays, slantRange, su.nearEdgeSlantRange, su.srgrConvParams);
            }

            if (!su.nearRangeOnLeft) {
                data.rangeIndex = su.sourceImageWidth - 1 - data.rangeIndex;
            }
            return true;
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException("getPosition", e);
        }
        return false;
    }

    /**
     * Get the source rectangle in slave image that contains all the given pixels.
     */
    public static Rectangle getBoundingBox(
            final PixelPos[][] slavePixPos, final int margin, final int subSwathIndex, final int sBurstIndex,
            Sentinel1Utils.SubSwathInfo[] sSubswath) {

        final int firstLineIndex = sBurstIndex*sSubswath[subSwathIndex - 1].linesPerBurst;
        final int lastLineIndex = firstLineIndex + sSubswath[subSwathIndex - 1].linesPerBurst - 1;
        final int firstPixelIndex = 0;
        final int lastPixelIndex = sSubswath[subSwathIndex - 1].samplesPerBurst - 1;

        int minX = Integer.MAX_VALUE;
        int maxX = -Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = -Integer.MAX_VALUE;

        for (PixelPos[] slavePixPo : slavePixPos) {
            for (int j = 0; j < slavePixPos[0].length; j++) {
                if (slavePixPo[j] != null) {
                    final int x = (int) Math.floor(slavePixPo[j].getX());
                    final int y = (int) Math.floor(slavePixPo[j].getY());

                    if (x < minX) {
                        minX = x;
                    }
                    if (x > maxX) {
                        maxX = x;
                    }
                    if (y < minY) {
                        minY = y;
                    }
                    if (y > maxY) {
                        maxY = y;
                    }
                }
            }
        }

        minX = Math.max(minX - margin, firstPixelIndex);
        maxX = Math.min(maxX + margin, lastPixelIndex);
        minY = Math.max(minY - margin, firstLineIndex);
        maxY = Math.min(maxY + margin, lastLineIndex);

        if (minX > maxX || minY > maxY) {
            return null;
        }

        return new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    public static void performDerampDemod(final Tile tileI, final Tile tileQ,
                                   final Rectangle rectangle, final double[][] derampDemodPhase,
                                   final double[][] derampDemodI, final double[][] derampDemodQ) {

        try {
            final int x0 = rectangle.x;
            final int y0 = rectangle.y;
            final int xMax = x0 + rectangle.width;
            final int yMax = y0 + rectangle.height;

            final ProductData dataI = tileI.getDataBuffer();
            final ProductData dataQ = tileQ.getDataBuffer();
            final TileIndex index = new TileIndex(tileI);

            for (int y = y0; y < yMax; y++) {
                index.calculateStride(y);
                final int yy = y - y0;
                for (int x = x0; x < xMax; x++) {
                    final int idx = index.getIndex(x);
                    final int xx = x - x0;
                    final double valueI = dataI.getElemDoubleAt(idx);
                    final double valueQ = dataQ.getElemDoubleAt(idx);
                    final double cosPhase = FastMath.cos(derampDemodPhase[yy][xx]);
                    final double sinPhase = FastMath.sin(derampDemodPhase[yy][xx]);
                    derampDemodI[yy][xx] = valueI*cosPhase - valueQ*sinPhase;
                    derampDemodQ[yy][xx] = valueI*sinPhase + valueQ*cosPhase;
                }
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException("performDerampDemod", e);
        }
    }

    private void saveMasterBands(final int x0, final int y0, final int w, final int h,
                                 final Map<Band, Tile> targetTileMap, final double[][] mstDerampDemodPhase,
                                 final double[][] mstDerampDemodI, final double[][] mstDerampDemodQ,
                                 final String polarization) throws OperatorException {

        try {
            final Band iBand = getTargetBand("i_", mstSuffix, polarization);
            final Band qBand = getTargetBand("q_", mstSuffix, polarization);

            if (iBand == null || qBand == null) {
                throw new OperatorException("Unable to find " + iBand.getName() +" or "+ qBand.getName());
            }

            final Tile tgtTileI = targetTileMap.get(iBand);
            final Tile tgtTileQ = targetTileMap.get(qBand);
            final ProductData tgtBufferI = tgtTileI.getDataBuffer();
            final ProductData tgtBufferQ = tgtTileQ.getDataBuffer();
            final TileIndex tgtIndex = new TileIndex(tgtTileI);

            Tile tgtTilePhase;
            ProductData tgtBufferPhase = null;
            if (outputDerampDemodPhase) {
                final Band phaseBand = getTargetBand("derampDemodPhase", mstSuffix, null);
                if(phaseBand == null) {
                    throw new OperatorException("derampDemodPhase not found");
                }
                tgtTilePhase = targetTileMap.get(phaseBand);
                tgtBufferPhase = tgtTilePhase.getDataBuffer();
            }

            for (int y = y0; y < y0 + h; y++) {
                tgtIndex.calculateStride(y);
                final int yy = y - y0;
                for (int x = x0; x < x0 + w; x++) {
                    final int xx = x - x0;
                    final int tgtIdx = tgtIndex.getIndex(x);
                    tgtBufferI.setElemDoubleAt(tgtIdx, mstDerampDemodI[yy][xx]);
                    tgtBufferQ.setElemDoubleAt(tgtIdx, mstDerampDemodQ[yy][xx]);

                    if (outputDerampDemodPhase && tgtBufferPhase != null) {
                        tgtBufferPhase.setElemFloatAt(tgtIdx, (float)mstDerampDemodPhase[yy][xx]);
                    }
                }
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException("saveMasterBands", e);
        }
    }

    private void performInterpolation(final int x0, final int y0, final int w, final int h,
                                      final Rectangle sourceRectangle, final Tile slaveTileI, final Tile slaveTileQ,
                                      final Map<Band, Tile> targetTileMap, final double[][] derampDemodPhase,
                                      final double[][] derampDemodI, final double[][] derampDemodQ,
                                      final PixelPos[][] slavePixPos, final int subswathIndex, final int sBurstIndex,
                                      final SlaveData slaveData, final String polarization) throws OperatorException {

        try {
            final ResamplingRaster resamplingRasterI = new ResamplingRaster(slaveTileI, derampDemodI);
            final ResamplingRaster resamplingRasterQ = new ResamplingRaster(slaveTileQ, derampDemodQ);
            final ResamplingRaster resamplingRasterPhase = new ResamplingRaster(slaveTileI, derampDemodPhase);

            final Band iBand = getTargetBand("i_", slaveData.slvSuffix, polarization);
            final Band qBand = getTargetBand("q_", slaveData.slvSuffix, polarization);

            if (iBand == null || qBand == null) {
                throw new OperatorException("Unable to find " + iBand.getName() +" or "+ qBand.getName());
            }

            final Tile tgtTileI = targetTileMap.get(iBand);
            final Tile tgtTileQ = targetTileMap.get(qBand);
            final ProductData tgtBufferI = tgtTileI.getDataBuffer();
            final ProductData tgtBufferQ = tgtTileQ.getDataBuffer();
            final TileIndex tgtIndex = new TileIndex(tgtTileI);

            Tile tgtTilePhase;
            ProductData tgtBufferPhase = null;
            if (outputDerampDemodPhase) {
                final Band phaseBand = getTargetBand("derampDemodPhase", slaveData.slvSuffix, null);
                if(phaseBand == null) {
                    throw new OperatorException("derampDemodPhase not found");
                }
                tgtTilePhase = targetTileMap.get(phaseBand);
                tgtBufferPhase = tgtTilePhase.getDataBuffer();
            }

            final Resampling.Index resamplingIndex = selectedResampling.createIndex();

            final int sxMin = sourceRectangle.x;
            final int syMin = sourceRectangle.y;
            final int sxMax = sourceRectangle.x + sourceRectangle.width - 1;
            final int syMax = sourceRectangle.y + sourceRectangle.height - 1;

            for (int y = y0; y < y0 + h; y++) {
                tgtIndex.calculateStride(y);
                final int yy = y - y0;
                for (int x = x0; x < x0 + w; x++) {
                    final int xx = x - x0;
                    final int tgtIdx = tgtIndex.getIndex(x);
                    final PixelPos slavePixelPos = slavePixPos[yy][xx];

                    if (slavePixelPos == null || slavePixelPos.x < sxMin || slavePixelPos.x > sxMax ||
                            slavePixelPos.y < syMin || slavePixelPos.y > syMax) {

                        tgtBufferI.setElemDoubleAt(tgtIdx, noDataValue);
                        tgtBufferQ.setElemDoubleAt(tgtIdx, noDataValue);

                        if (outputDerampDemodPhase) {
                            tgtBufferPhase.setElemFloatAt(tgtIdx, (float)noDataValue);
                        }
                        continue;
                    }

                    selectedResampling.computeCornerBasedIndex(
                            slavePixelPos.x - sourceRectangle.x, slavePixelPos.y - sourceRectangle.y,
                            sourceRectangle.width, sourceRectangle.height, resamplingIndex);

                    final double samplePhase = selectedResampling.resample(resamplingRasterPhase, resamplingIndex);
                    final double cosPhase = FastMath.cos(samplePhase);
                    final double sinPhase = FastMath.sin(samplePhase);
                    double sampleI = selectedResampling.resample(resamplingRasterI, resamplingIndex);
                    double sampleQ = selectedResampling.resample(resamplingRasterQ, resamplingIndex);

                    double rerampRemodI;
                    if (Double.isNaN(sampleI)) {
                        sampleI = noDataValue;
                        rerampRemodI = noDataValue;
                    } else {
                        rerampRemodI = sampleI * cosPhase + sampleQ * sinPhase;
                    }

                    double rerampRemodQ;
                    if (Double.isNaN(sampleQ)) {
                        sampleQ = noDataValue;
                        rerampRemodQ = noDataValue;
                    } else {
                        rerampRemodQ = -sampleI * sinPhase + sampleQ * cosPhase;
                    }

                    if (disableReramp) {
                        tgtBufferI.setElemDoubleAt(tgtIdx, sampleI);
                        tgtBufferQ.setElemDoubleAt(tgtIdx, sampleQ);
                    } else {
                        tgtBufferI.setElemDoubleAt(tgtIdx, rerampRemodI);
                        tgtBufferQ.setElemDoubleAt(tgtIdx, rerampRemodQ);
                    }

                    if (outputDerampDemodPhase) {
                        tgtBufferPhase.setElemFloatAt(tgtIdx, (float)samplePhase);
                    }
                }
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException("performInterpolation", e);
        }
    }

    private void performInterpolationOnETADBand(
            final int x0, final int y0, final int w, final int h, final Rectangle sourceRectangle,
            final Map<Band, Tile> targetTileMap, final PixelPos[][] secPixPos, final SlaveData secData,
            final String bandName) throws OperatorException {

        try {
            final Band secETADBand = secData.slaveProduct.getBand(bandName);
            final Tile secETADTile = getSourceTile(secETADBand, sourceRectangle);
            final double[][] secETADData = getETADData(secETADTile, sourceRectangle);

            final Band tgtETADBand = getTargetBand(bandName, secData.slvSuffix, null);
            final Tile tgtETADTile = targetTileMap.get(tgtETADBand);
            final ProductData tgtETADBuffer = tgtETADTile.getDataBuffer();
            final TileIndex tgtIndex = new TileIndex(tgtETADTile);

            final ResamplingRaster resamplingRaster = new ResamplingRaster(secETADTile, secETADData);
            final Resampling.Index resamplingIndex = selectedResampling.createIndex();

            final int sxMin = sourceRectangle.x;
            final int syMin = sourceRectangle.y;
            final int sxMax = sourceRectangle.x + sourceRectangle.width - 1;
            final int syMax = sourceRectangle.y + sourceRectangle.height - 1;

            for (int y = y0; y < y0 + h; ++y) {
                tgtIndex.calculateStride(y);
                final int yy = y - y0;

                for (int x = x0; x < x0 + w; ++x) {
                    final int xx = x - x0;
                    final int tgtIdx = tgtIndex.getIndex(x);

                    final PixelPos secPixelPos = secPixPos[yy][xx];
                    if (secPixelPos == null || secPixelPos.x < sxMin || secPixelPos.x > sxMax ||
                            secPixelPos.y < syMin || secPixelPos.y > syMax) {

                        tgtETADBuffer.setElemDoubleAt(tgtIdx, noDataValue);
                        continue;
                    }

                    selectedResampling.computeCornerBasedIndex(
                            secPixelPos.x - sourceRectangle.x, secPixelPos.y - sourceRectangle.y,
                            sourceRectangle.width, sourceRectangle.height, resamplingIndex);

                    double sample = selectedResampling.resample(resamplingRaster, resamplingIndex);

                    tgtETADBuffer.setElemDoubleAt(tgtIdx, sample);
                }
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException("performInterpolationOnETADCorrection", e);
        }
    }

    static double[][] getETADData(final Tile tile, final Rectangle rectangle) {

        try {
            final int x0 = rectangle.x;
            final int y0 = rectangle.y;
            final int xMax = x0 + rectangle.width;
            final int yMax = y0 + rectangle.height;
            final double[][] corr = new double[rectangle.height][rectangle.width];

            final ProductData data = tile.getDataBuffer();
            final TileIndex index = new TileIndex(tile);

            for (int y = y0; y < yMax; y++) {
                index.calculateStride(y);
                final int yy = y - y0;
                for (int x = x0; x < xMax; x++) {
                    final int idx = index.getIndex(x);
                    corr[yy][x - x0] = data.getElemDoubleAt(idx);
                }
            }
            return corr;

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException("getETADCorrection", e);
        }
        return null;
    }

    public static Band getBand(
            final Product product, final String prefix, final String swathIndexStr, final String polarization) {

        final String[] bandNames = product.getBandNames();
        for (String bandName:bandNames) {
            if (bandName.contains(prefix) && bandName.contains(swathIndexStr) && bandName.contains(polarization)) {
                return product.getBand(bandName);
            }
        }
        return null;
    }

    private boolean isSlavePixPosValid(final PixelPos slavePixPos, final int subswathIndex, final int sBurstIndex,
                                       final Sentinel1Utils.SubSwathInfo[] sSubswath) {
        return (slavePixPos != null &&
                slavePixPos.y >= sSubswath[subswathIndex - 1].linesPerBurst*sBurstIndex &&
                slavePixPos.y < sSubswath[subswathIndex - 1].linesPerBurst*(sBurstIndex+1));
    }

    private void outputRangeAzimuthOffsets(final int x0, final int y0, final int w, final int h,
                                           final Map<Band, Tile> targetTileMap, final PixelPos[][] slavePixPos,
                                           final int subSwathIndex, final SlaveData slaveData,
                                           final int mBurstIndex, final int sBurstIndex) {

        try {
            final Band azOffsetBand = getTargetBand("azOffset", slaveData.slvSuffix, null);
            final Band rgOffsetBand = getTargetBand("rgOffset", slaveData.slvSuffix, null);

            if (azOffsetBand == null || rgOffsetBand == null) {
                return;
            }

            //Sentinel1Utils.SubSwathInfo mSubSwath = mSU.getSubSwath()[subSwathIndex - 1];
            //Sentinel1Utils.SubSwathInfo sSubSwath = slaveData.sSU.getSubSwath()[subSwathIndex - 1];

            final Tile tgtTileAzOffset = targetTileMap.get(azOffsetBand);
            final Tile tgtTileRgOffset = targetTileMap.get(rgOffsetBand);
            final ProductData tgtBufferAzOffset = tgtTileAzOffset.getDataBuffer();
            final ProductData tgtBufferRgOffset = tgtTileRgOffset.getDataBuffer();
            final TileIndex tgtIndex = new TileIndex(tgtTileAzOffset);

            for (int y = y0; y < y0 + h; y++) {
                tgtIndex.calculateStride(y);
                final int yy = y - y0;
                for (int x = x0; x < x0 + w; x++) {
                    final int tgtIdx = tgtIndex.getIndex(x);
                    final int xx = x - x0;

                    if (slavePixPos[yy][xx] == null) {
                        tgtBufferAzOffset.setElemFloatAt(tgtIdx, (float) noDataValue);
                        tgtBufferRgOffset.setElemFloatAt(tgtIdx, (float) noDataValue);
                    } else {
/*
                        final double mta = mSubSwath.burstFirstLineTime[mBurstIndex] +
                                (y - mBurstIndex*mSubSwath.linesPerBurst)*mSubSwath.azimuthTimeInterval;

                        final double mY = (mta - mSubSwath.burstFirstLineTime[0]) / mSubSwath.azimuthTimeInterval;

                        final double sta = sSubSwath.burstFirstLineTime[sBurstIndex] +
                                (slavePixPos[yy][xx].y - sBurstIndex*sSubSwath.linesPerBurst)*sSubSwath.azimuthTimeInterval;

                        final double sY = (sta - sSubSwath.burstFirstLineTime[0]) / sSubSwath.azimuthTimeInterval;

                        final float yOffset = (float)(mY - sY);

                        tgtBufferAzOffset.setElemFloatAt(tgtIdx, yOffset);
                        tgtBufferRgOffset.setElemFloatAt(tgtIdx, (float)(x - slavePixPos[yy][xx].x));
*/
                        //tgtBufferAzOffset.setElemFloatAt(tgtIdx, (float)(y - slavePixPos[yy][xx].y));
                        //tgtBufferRgOffset.setElemFloatAt(tgtIdx, (float)(x - slavePixPos[yy][xx].x));
                        tgtBufferAzOffset.setElemFloatAt(tgtIdx, (float)(slavePixPos[yy][xx].y));
                        tgtBufferRgOffset.setElemFloatAt(tgtIdx, (float)(slavePixPos[yy][xx].x));
                    }
                }
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException("outputRangeAzimuthOffsets", e);
        }
    }

    private void outputDEM(final int x0, final int y0, final int w, final int h,
                           final Map<Band, Tile> targetTileMap, final double[][] elevation) {

        try {
            final Band elevBand = getTargetBand("elevation", null, null);
            if (elevBand == null) {
                return;
            }

            final Tile tgtTileElev = targetTileMap.get(elevBand);
            final ProductData tgtBufferElev = tgtTileElev.getDataBuffer();
            final TileIndex tgtIndex = new TileIndex(tgtTileElev);

            for (int y = y0; y < y0 + h; y++) {
                tgtIndex.calculateStride(y);
                final int yy = y - y0;
                for (int x = x0; x < x0 + w; x++) {
                    final int tgtIdx = tgtIndex.getIndex(x);
                    final int xx = x - x0;
                    tgtBufferElev.setElemFloatAt(tgtIdx, (float)(elevation[yy][xx]));
                }
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException("outputDEM", e);
        }
    }

    private Band getTargetBand(final String name, final String tag, final String pol) {

        final Band[] targetBands = targetProduct.getBands();
        for (Band band : targetBands) {
            final String bandName = band.getName();
            if (bandName.contains(name)) {
                if (tag != null) {
                    if(bandName.contains(tag)) {
                        if (pol == null) {
                            return band;
                        } else if (bandName.contains(pol)) {
                            return band;
                        }
                    }
                } else {
                    if (pol == null) {
                        return band;
                    } else if (bandName.contains(pol)) {
                        return band;
                    }
                }
            }
        }
        return null;
    }

    private static class PositionData {
        final PosVector earthPoint = new PosVector();
        final PosVector sensorPos = new PosVector();
        double azimuthIndex;
        double rangeIndex;
    }

    public static class ResamplingRaster implements Resampling.Raster {

        private final Tile tile;
        private final double[][] data;
        private final boolean usesNoData;
        private final double noDataValue;

        public ResamplingRaster(final Tile tile, final double[][] data) {
            this.tile = tile;
            this.data = data;
            final RasterDataNode rasterDataNode = tile.getRasterDataNode();
            this.usesNoData = rasterDataNode.isNoDataValueUsed();
            this.noDataValue = rasterDataNode.getNoDataValue();
        }

        public final int getWidth() {
            return tile.getWidth();
        }

        public final int getHeight() {
            return tile.getHeight();
        }

        public boolean getSamples(final int[] x, final int[] y, final double[][] samples) throws Exception {
            boolean allValid = true;

            try {
                double val;
                int i = 0;
                while (i < y.length) {
                    int j = 0;
                    while (j < x.length) {
                        val = data[y[i]][x[j]];

                        if (usesNoData) {
                            if (noDataValue == val) {
                                val = Double.NaN;
                                allValid = false;
                            }
                        }
                        samples[i][j] = val;
                        ++j;
                    }
                    ++i;
                }
            } catch (Exception e) {
                SystemUtils.LOG.severe(e.getMessage());
                allValid = false;
            }

            return allValid;
        }
    }

    private static class BurstIndices {
        int firstBurstIndex = -1;
        int secondBurstIndex = -1;
        boolean inUpperPartOfFirstBurst = false;
        boolean inUpperPartOfSecondBurst = false;
    }

    private static class SlaveData {
        Product slaveProduct;
        Sentinel1Utils sSU;
        int burstOffset = -9999;
        String slvSuffix;
        boolean foundETADCorrection = false;
        boolean foundETADHeight = false;
        boolean foundETADGradient = false;

        SlaveData(final Product product) throws Exception {
            this.slaveProduct = product;
            this.sSU = new Sentinel1Utils(product);

            sSU.computeDopplerRate();
            sSU.computeReferenceTime();
        }

        public void print() {
            SystemUtils.LOG.info(slvSuffix +" burstOffset="+burstOffset);
        }
    }

    /**
     * The SPI is used to register this operator in the graph processing framework
     * via the SPI configuration file
     * {@code META-INF/services/org.esa.snap.core.gpf.OperatorSpi}.
     * This class may also serve as a factory for new operator instances.
     *
     * @see OperatorSpi#createOperator()
     * @see OperatorSpi#createOperator(java.util.Map, java.util.Map)
     */
    public static class Spi extends OperatorSpi {
        public Spi() {
            super(BackGeocodingOp.class);
        }
    }
}
