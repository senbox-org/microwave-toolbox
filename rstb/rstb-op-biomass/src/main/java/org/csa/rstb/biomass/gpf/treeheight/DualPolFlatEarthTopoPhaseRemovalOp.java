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
package org.csa.rstb.biomass.gpf.treeheight;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.insar.gpf.InterferogramOp;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.dataop.dem.ElevationModel;
import org.esa.snap.core.dataop.dem.ElevationModelDescriptor;
import org.esa.snap.core.dataop.dem.ElevationModelRegistry;
import org.esa.snap.core.dataop.resamp.Resampling;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.dem.dataio.FileElevationModel;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.StackUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;
import org.jblas.ComplexDouble;
import org.jblas.ComplexDoubleMatrix;
import org.jblas.DoubleMatrix;
import org.jblas.MatrixFunctions;
import org.jlinda.core.Orbit;
import org.jlinda.core.SLCImage;
import org.jlinda.core.geom.DemTile;
import org.jlinda.core.geom.TopoPhase;
import org.jlinda.core.utils.CplxContainer;
import org.jlinda.core.utils.PolyUtils;
import org.jlinda.core.utils.ProductContainer;
import org.jlinda.core.utils.TileUtilsDoris;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * The input to this operator should be co-registered master and slave SLC products.
 * The operator will compute flat-Earth phase and topographic phase and remove them
 * from the slave image.
 */
@OperatorMetadata(alias = "DualPolFlatEarthTopoPhaseRemoval",
        category = "Radar/Biomass",
        authors = "Jun Lu, Luis Veci",
        version = "1.0",
        copyright = "Copyright (C) 2017 by Array Systems Computing Inc.",
        description = "Remove flat-earth phase and topographic phase for stack of coregistered images")
public class DualPolFlatEarthTopoPhaseRemovalOp extends Operator {

    @SourceProduct
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(defaultValue = "false", label = "Subtract flat-earth phase in coherence phase")
    private boolean subtractFlatEarthPhase = false;

    @Parameter(valueSet = {"1", "2", "3", "4", "5", "6", "7", "8"},
            description = "Order of 'Flat earth phase' polynomial",
            defaultValue = "5",
            label = "Degree of \"Flat Earth\" polynomial")
    private int srpPolynomialDegree = 5;

    @Parameter(valueSet = {"301", "401", "501", "601", "701", "801", "901", "1001"},
            description = "Number of points for the 'flat earth phase' polynomial estimation",
            defaultValue = "501",
            label = "Number of \"Flat Earth\" estimation points")
    private int srpNumberPoints = 501;

    @Parameter(valueSet = {"1", "2", "3", "4", "5"},
            description = "Degree of orbit (polynomial) interpolator",
            defaultValue = "3",
            label = "Orbit interpolation degree")
    private int orbitDegree = 3;

    @Parameter(defaultValue = "false", label = "Subtract topographic phase")
    private boolean subtractTopographicPhase = false;

    @Parameter(description = "The digital elevation model.",
            defaultValue = "SRTM 3Sec",
            label = "Digital Elevation Model")
    private String demName = "SRTM 3Sec";

    @Parameter(label = "External DEM")
    private File externalDEMFile = null;

    @Parameter(label = "DEM No Data Value", defaultValue = "0")
    private double externalDEMNoDataValue = 0;

    @Parameter(label = "Tile Extension [%]",
            description = "Define extension of tile for DEM simulation (optimization parameter).",
            defaultValue = "100")
    private String tileExtensionPercent = "100";

    private CplxContainer mstInfo = null;
    private CplxContainer slvInfo = null;
    private ProductContainer mstSlvPair = null;
    private Map<String, ProductContainer> targetMap = new HashMap<>();

    private MetadataElement mstRoot = null;
    private MetadataElement slvRoot = null;
    private MetadataElement origMetadataRoot = null;

    private Band iHHMstBand = null;
    private Band qHHMstBand = null;
    private Band iVVMstBand = null;
    private Band qVVMstBand = null;
    private Band iHHSlvBand = null;
    private Band qHHSlvBand = null;
    private Band iVVSlvBand = null;
    private Band qVVSlvBand = null;
    private double srcNoDataValue = 0.0;

    private HashMap<String, DoubleMatrix> flatEarthPolyMap = new HashMap<>();
    private int sourceImageWidth;
    private int sourceImageHeight;

    private ElevationModel dem = null;
    private double demNoDataValue = 0;
    private double demSamplingLat;
    private double demSamplingLon;
    private boolean isBistatic = false;

    private static final boolean OUTPUT_PHASE = true;
    private static final String FLAT_EARTH_PHASE = "flat_earth_phase";
    private static final String TOPO_PHASE = "topo_phase";

    /**
     * Default constructor. The graph processing framework
     * requires that an operator has a default constructor.
     */
    public DualPolFlatEarthTopoPhaseRemovalOp() {
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
            mstRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);
            final MetadataElement slaveElem =
                    sourceProduct.getMetadataRoot().getElement(AbstractMetadata.SLAVE_METADATA_ROOT);
            if (slaveElem != null) {
                slvRoot = slaveElem.getElements()[0];
            }
            origMetadataRoot = AbstractMetadata.getOriginalProductMetadata(sourceProduct);

            checkUserInput();

            getSourceBands();

            constructSourceMetadata();

            createTargetProduct();

            if (subtractFlatEarthPhase) {
                constructFlatEarthPolynomials();
            }

            if (subtractTopographicPhase) {
                defineDEM();
            }

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private void checkUserInput() {

        try {
            final InputProductValidator validator = new InputProductValidator(sourceProduct);
            validator.checkIfSARProduct();
            validator.checkIfCoregisteredStack();

            final String mission = mstRoot.getAttributeString(AbstractMetadata.MISSION);
            final String productType = mstRoot.getAttributeString(AbstractMetadata.PRODUCT_TYPE);
            if (!mission.startsWith("TDM") || !productType.contains("COSSC")) {
                throw new OperatorException("TanDEM-X dual-pol COSSC product is expected.");
            }

            final String polModeStr = origMetadataRoot.getElement("level1Product").getElement("ProductInfo").
                    getElement("acquisitionInfo").getAttributeString("polarisationMode");

            if (polModeStr == null || !polModeStr.toLowerCase().contains("dual")) {
                throw new OperatorException("TanDEM-X dual-pol COSSC product is expected.");
            }


            final MetadataElement root = sourceProduct.getMetadataRoot();
            if (root == null) {
                throw new OperatorException("Root metadata not found");
            }

            final String cooperativeModeStr = root.getElement("CoSSC_Metadata").getElement("cossc_product").
                    getElement("commonAcquisitionInfo").getAttributeString("cooperativeMode");

            isBistatic = cooperativeModeStr.contains("bistatic");

            sourceImageWidth = sourceProduct.getSceneRasterWidth();
            sourceImageHeight = sourceProduct.getSceneRasterHeight();
        } catch (Exception e) {
            throw new OperatorException(e);
        }
    }

    private void getSourceBands() {

        final Band[] srcBands = sourceProduct.getBands();
        for (Band band : srcBands) {
            if (band instanceof VirtualBand) {
                continue;
            }

            final String bandName = band.getName();
            if (bandName.contains("i_HH_slv")) {
                iHHSlvBand = band;
            } else if (bandName.contains("q_HH_slv")) {
                qHHSlvBand = band;
            } else if (bandName.contains("i_VV_slv")) {
                iVVSlvBand = band;
            } else if (bandName.contains("q_VV_slv")) {
                qVVSlvBand = band;
            } else if (bandName.contains("i_HH_mst")) {
                iHHMstBand = band;
            } else if (bandName.contains("q_HH_mst")) {
                qHHMstBand = band;
            } else if (bandName.contains("i_VV_mst")) {
                iVVMstBand = band;
            } else if (bandName.contains("q_VV_mst")) {
                qVVMstBand = band;
            }
        }

        if (iHHSlvBand == null || qHHSlvBand == null || iVVSlvBand == null || qVVSlvBand == null ||
                iHHMstBand == null || qHHMstBand == null || iVVMstBand == null || qVVMstBand == null) {
            throw new OperatorException("TanDEM-X dual-pol COSSC product is expected.");
        }

        srcNoDataValue = iHHSlvBand.getNoDataValue();
    }

    private void constructSourceMetadata() throws Exception {

        final String mstDate = OperatorUtils.getAcquisitionDate(mstRoot);
        final SLCImage mstMeta = new SLCImage(mstRoot, sourceProduct);
        final Orbit mstOrbit = new Orbit(mstRoot, orbitDegree);
        mstInfo = new CplxContainer(mstDate, mstMeta, mstOrbit, iHHMstBand, qHHMstBand);

        final String slvDate = OperatorUtils.getAcquisitionDate(slvRoot);
        final SLCImage slvMeta = new SLCImage(slvRoot, sourceProduct);
        final Orbit slvOrbit = new Orbit(slvRoot, orbitDegree);
        slvInfo = new CplxContainer(slvDate, slvMeta, slvOrbit, iHHSlvBand, qHHSlvBand);

        mstSlvPair = new ProductContainer(null, mstInfo, slvInfo, false);

        final int keyMaster = mstRoot.getAttributeInt(AbstractMetadata.ABS_ORBIT);
        final int keySlave = slvRoot.getAttributeInt(AbstractMetadata.ABS_ORBIT);
        String productName = keyMaster + "_" + keySlave;
        targetMap.put(productName, mstSlvPair);
    }

    private void createTargetProduct() {

        targetProduct = new Product(sourceProduct.getName(),
                sourceProduct.getProductType(),
                sourceImageWidth,
                sourceImageHeight);

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        final Band[] sourceBands = sourceProduct.getBands();

        for (Band srcBand : sourceBands) {

            final String bandName = srcBand.getName();
            if (srcBand instanceof VirtualBand) {
                final VirtualBand virtBand = new VirtualBand(bandName,
                        ProductData.TYPE_FLOAT32,
                        srcBand.getRasterWidth(),
                        srcBand.getRasterHeight(),
                        ((VirtualBand) srcBand).getExpression());

                virtBand.setUnit(srcBand.getUnit());
                virtBand.setDescription(srcBand.getDescription());
                virtBand.setNoDataValueUsed(true);
                virtBand.setNoDataValue(srcBand.getNoDataValue());
                virtBand.setOwner(targetProduct);
                targetProduct.addBand(virtBand);

                if (srcBand.getGeoCoding() != targetProduct.getSceneGeoCoding()) {
                    virtBand.setGeoCoding(srcBand.getGeoCoding());
                }

                targetProduct.setQuicklookBandName(virtBand.getName());
                continue;
            }

            if (StackUtils.isMasterBand(bandName, sourceProduct)) {
                ProductUtils.copyBand(bandName, sourceProduct, bandName, targetProduct, true);
            } else {
                final Band targetBand = targetProduct.addBand(bandName, srcBand.getDataType());
                ProductUtils.copyRasterDataNodeProperties(srcBand, targetBand);
            }
        }

        if (subtractTopographicPhase && OUTPUT_PHASE) {
            final Band tgpBand = targetProduct.addBand(TOPO_PHASE, ProductData.TYPE_FLOAT32);
            tgpBand.setUnit(Unit.PHASE);
        }

        if (subtractFlatEarthPhase && OUTPUT_PHASE) {
            final Band fepBand = targetProduct.addBand(FLAT_EARTH_PHASE, ProductData.TYPE_FLOAT32);
            fepBand.setUnit(Unit.PHASE);
        }
    }

    private void constructFlatEarthPolynomials() throws Exception {

        flatEarthPolyMap.put(slvInfo.name, InterferogramOp.estimateFlatEarthPolynomial(
                mstInfo.metaData, mstInfo.orbit, slvInfo.metaData, slvInfo.orbit, sourceImageWidth,
                sourceImageHeight, srpPolynomialDegree, srpNumberPoints, sourceProduct));
    }

    private void defineDEM() throws IOException {

        Resampling resampling = Resampling.BILINEAR_INTERPOLATION;
        final ElevationModelRegistry elevationModelRegistry;
        final ElevationModelDescriptor demDescriptor;

        if (externalDEMFile == null) {
            elevationModelRegistry = ElevationModelRegistry.getInstance();
            demDescriptor = elevationModelRegistry.getDescriptor(demName);

            if (demDescriptor == null) {
                throw new OperatorException("The DEM '" + demName + "' is not supported.");
            }

            dem = demDescriptor.createDem(resampling);
            if (dem == null) {
                throw new OperatorException("The DEM '" + demName + "' has not been installed.");
            }

            demNoDataValue = demDescriptor.getNoDataValue();
            demSamplingLat = demDescriptor.getTileWidthInDegrees() * (1.0f / demDescriptor.getTileWidth()) *
                    org.jlinda.core.Constants.DTOR;
            demSamplingLon = demSamplingLat;

        } else {

            dem = new FileElevationModel(externalDEMFile, resampling.getName(), externalDEMNoDataValue);
            demName = externalDEMFile.getPath();
            demNoDataValue = externalDEMNoDataValue;

            try {
                demSamplingLat =
                        (dem.getGeoPos(new PixelPos(0, 1)).getLat() - dem.getGeoPos(new PixelPos(0, 0)).getLat()) *
                                org.jlinda.core.Constants.DTOR;
                demSamplingLon =
                        (dem.getGeoPos(new PixelPos(1, 0)).getLon() - dem.getGeoPos(new PixelPos(0, 0)).getLon()) *
                                org.jlinda.core.Constants.DTOR;
            } catch (Exception e) {
                throw new OperatorException("The DEM '" + demName + "' cannot be properly interpreted.");
            }
        }
    }


    /**
     * Called by the framework in order to compute a tile for the given target band.
     * <p>The default implementation throws a runtime exception with the message "not implemented".</p>
     *
     * @param targetTileMap   The target tiles associated with all target bands to be computed.
     * @param targetRectangle The rectangle of target tile.
     * @param pm              A progress monitor which should be used to determine computation cancellation requests.
     * @throws org.esa.snap.core.gpf.OperatorException If an error occurs during computation of the target raster.
     */
    @Override
    public void computeTileStack(Map<Band, Tile> targetTileMap, Rectangle targetRectangle, ProgressMonitor pm)
            throws OperatorException {

        try {
            final int x0 = targetRectangle.x;
            final int y0 = targetRectangle.y;
            final int w = targetRectangle.width;
            final int h = targetRectangle.height;
            final int xMax = x0 + w;
            final int yMax = y0 + h;

            final org.jlinda.core.Window tileWindow = new org.jlinda.core.Window(y0, yMax - 1, x0, xMax - 1);

            DemTile demTile = null;
            if (subtractTopographicPhase) {
                demTile = TopoPhase.getDEMTile(tileWindow, targetMap, dem, demNoDataValue,
                        demSamplingLat, demSamplingLon, tileExtensionPercent);

                if (demTile.getData().length < 3 || demTile.getData()[0].length < 3) {
                    throw new OperatorException("The resolution of the selected DEM is too low, " +
                            "please select DEM with higher resolution.");
                }
            }

            final Tile iHHSlvTile = getSourceTile(iHHSlvBand, targetRectangle);
            final Tile qHHSlvTile = getSourceTile(qHHSlvBand, targetRectangle);
            final Tile iVVSlvTile = getSourceTile(iVVSlvBand, targetRectangle);
            final Tile qVVSlvTile = getSourceTile(qVVSlvBand, targetRectangle);

            final ComplexDoubleMatrix dataHHSlv =
                    TileUtilsDoris.pullComplexDoubleMatrix(iHHSlvTile, qHHSlvTile);

            final ComplexDoubleMatrix dataVVSlv =
                    TileUtilsDoris.pullComplexDoubleMatrix(iVVSlvTile, qVVSlvTile);

            if (isBistatic) {
                dataVVSlv.muli(-1.0);
            }

            if (subtractFlatEarthPhase) {
                final DoubleMatrix flatEarthPhase = computeFlatEarthPhase(x0, xMax - 1, w, y0, yMax - 1, h,
                        0, sourceImageWidth - 1, 0, sourceImageHeight - 1, mstSlvPair.sourceSec.name);

                final ComplexDoubleMatrix complexReferencePhase = new ComplexDoubleMatrix(
                        MatrixFunctions.cos(flatEarthPhase), MatrixFunctions.sin(flatEarthPhase));

                dataHHSlv.muli(complexReferencePhase);
                dataVVSlv.muli(complexReferencePhase);

                if (OUTPUT_PHASE) {
                    saveFlatEarthPhase(x0, xMax, y0, yMax, flatEarthPhase, targetTileMap);
                }
            }

            if (subtractTopographicPhase) {
                TopoPhase topoPhase = TopoPhase.computeTopoPhase(
                        mstSlvPair, tileWindow, demTile, false);

                final ComplexDoubleMatrix ComplexTopoPhase = new ComplexDoubleMatrix(
                        MatrixFunctions.cos(new DoubleMatrix(topoPhase.demPhase)),
                        MatrixFunctions.sin(new DoubleMatrix(topoPhase.demPhase)));

                dataHHSlv.muli(ComplexTopoPhase);
                dataVVSlv.muli(ComplexTopoPhase);

                if (OUTPUT_PHASE) {
                    saveTopoPhase(x0, xMax, y0, yMax, topoPhase.demPhase, targetTileMap);
                }
            }

            final Band iHHTgtSlvBand = targetProduct.getBand(iHHSlvBand.getName());
            final Band qHHTgtSlvBand = targetProduct.getBand(qHHSlvBand.getName());
            saveCplxBand(dataHHSlv, iHHTgtSlvBand, qHHTgtSlvBand, targetTileMap, targetRectangle);

            final Band iVVTgtSlvBand = targetProduct.getBand(iVVSlvBand.getName());
            final Band qVVTgtSlvBand = targetProduct.getBand(qVVSlvBand.getName());
            saveCplxBand(dataVVSlv, iVVTgtSlvBand, qVVTgtSlvBand, targetTileMap, targetRectangle);

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        } finally {
            pm.done();
        }
    }

    private DoubleMatrix computeFlatEarthPhase(final int xMin, final int xMax, final int xSize,
                                               final int yMin, final int yMax, final int ySize,
                                               final int minPixel, final int maxPixel,
                                               final int minLine, final int maxLine,
                                               final String polynomialName) {

        DoubleMatrix rangeAxisNormalized = DoubleMatrix.linspace(xMin, xMax, xSize);
        rangeAxisNormalized = InterferogramOp.normalizeDoubleMatrix(rangeAxisNormalized, minPixel, maxPixel);

        DoubleMatrix azimuthAxisNormalized = DoubleMatrix.linspace(yMin, yMax, ySize);
        azimuthAxisNormalized = InterferogramOp.normalizeDoubleMatrix(azimuthAxisNormalized, minLine, maxLine);

        final DoubleMatrix polyCoeffs = flatEarthPolyMap.get(polynomialName);

        return PolyUtils.polyval(azimuthAxisNormalized, rangeAxisNormalized,
                polyCoeffs, PolyUtils.degreeFromCoefficients(polyCoeffs.length));
    }

    private void saveTopoPhase(final int x0, final int xMax, final int y0, final int yMax,
                               final double[][] topoPhase, final Map<Band, Tile> targetTileMap) {

        final Band topoPhaseBand = targetProduct.getBand(TOPO_PHASE);
        final Tile topoPhaseTile = targetTileMap.get(topoPhaseBand);
        final ProductData topoPhaseData = topoPhaseTile.getDataBuffer();
        final TileIndex tgtIndex = new TileIndex(topoPhaseTile);

        for (int y = y0; y < yMax; y++) {
            tgtIndex.calculateStride(y);
            final int yy = y - y0;
            for (int x = x0; x < xMax; x++) {
                final int tgtIdx = tgtIndex.getIndex(x);
                final int xx = x - x0;
                topoPhaseData.setElemFloatAt(tgtIdx, (float) topoPhase[yy][xx]);
            }
        }
    }

    private void saveFlatEarthPhase(final int x0, final int xMax, final int y0, final int yMax,
                                    final DoubleMatrix refPhase, final Map<Band, Tile> targetTileMap) {

        final Band flatEarthPhaseBand = targetProduct.getBand(FLAT_EARTH_PHASE);
        final Tile flatEarthPhaseTile = targetTileMap.get(flatEarthPhaseBand);
        final ProductData flatEarthPhaseData = flatEarthPhaseTile.getDataBuffer();
        final TileIndex tgtIndex = new TileIndex(flatEarthPhaseTile);

        for (int y = y0; y < yMax; y++) {
            tgtIndex.calculateStride(y);
            final int yy = y - y0;
            for (int x = x0; x < xMax; x++) {
                final int tgtIdx = tgtIndex.getIndex(x);
                final int xx = x - x0;
                flatEarthPhaseData.setElemFloatAt(tgtIdx, (float) refPhase.get(yy, xx));
            }
        }
    }

    private void saveCplxBand(final ComplexDoubleMatrix cplxMatrix, final Band iBand, final Band qBand,
                              final Map<Band, Tile> targetTileMap, final Rectangle targetRectangle) {

        final int x0 = targetRectangle.x;
        final int y0 = targetRectangle.y;
        final int maxX = x0 + targetRectangle.width;
        final int maxY = y0 + targetRectangle.height;

        final Tile iTile = targetTileMap.get(iBand);
        final Tile qTile = targetTileMap.get(qBand);
        final ProductData iData = iTile.getDataBuffer();
        final ProductData qData = qTile.getDataBuffer();
        final TileIndex tgtIndex = new TileIndex(iTile);

        for (int y = y0; y < maxY; y++) {
            tgtIndex.calculateStride(y);
            final int yy = y - y0;
            for (int x = x0; x < maxX; x++) {
                final int tgtIdx = tgtIndex.getIndex(x);
                final int xx = x - x0;

                final ComplexDouble cplx = cplxMatrix.get(yy, xx);
                iData.setElemFloatAt(tgtIdx, (float) cplx.real());
                qData.setElemFloatAt(tgtIdx, (float) cplx.imag());
            }
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
            super(DualPolFlatEarthTopoPhaseRemovalOp.class);
        }
    }
}
