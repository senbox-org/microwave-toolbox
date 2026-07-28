/*
 * Copyright (C) 2016 by Array Systems Computing Inc. http://www.array.ca
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

import com.bc.ceres.core.ProgressMonitor;
import org.apache.commons.math3.util.FastMath;
import eu.esa.sar.commons.SARUtils;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.dataop.dem.ElevationModel;
import org.esa.snap.core.dataop.dem.ElevationModelDescriptor;
import org.esa.snap.core.dataop.dem.ElevationModelRegistry;
import org.esa.snap.core.dataop.resamp.ResamplingFactory;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.core.util.math.MathUtils;
import org.esa.snap.dem.dataio.DEMFactory;
import org.esa.snap.dem.dataio.FileElevationModel;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.gpf.StackUtils;
import org.esa.snap.engine_utilities.datamodel.OrbitStateVector;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;
import org.esa.snap.engine_utilities.util.Maths;
import org.jlinda.core.Baseline;
import org.jlinda.core.Orbit;
import org.jlinda.core.SLCImage;
import org.jlinda.core.Window;
import org.jlinda.core.geocode.Slant2Height;
import org.jlinda.core.utils.PolyUtils;
import org.jlinda.core.utils.TileUtilsDoris;
import org.jblas.DoubleMatrix;

import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


@OperatorMetadata(alias = "PhaseToElevation",
        category = "Radar/Interferometric/Products",
        authors = "Jun Lu, Luis Veci, Petar Marinkovic",
        version = "1.1",
        copyright = "Copyright (C) 2016 by Array Systems Computing Inc.",
        description = "DEM Generation")
public final class PhaseToElevationOp extends Operator {

    /** Linearised height-of-ambiguity conversion referenced to DEM seed points. */
    public static final String METHOD_DEM_SEED = "DEM Seed";
    /** Doris "Schwabisch" polynomial conversion. Needs no DEM. */
    public static final String METHOD_SCHWABISCH = "Schwabisch";

    @SourceProduct(alias = "source")
    private Product sourceProduct;
    @TargetProduct
    private Product targetProduct;

    @Parameter(valueSet = {METHOD_DEM_SEED, METHOD_SCHWABISCH}, defaultValue = METHOD_DEM_SEED,
            label = "Method",
            description = "DEM Seed: linearise the phase-to-height relation about a reference point " +
                    "solved from low-slope DEM seeds (requires a DEM). " +
                    "Schwabisch: fit reference phase at several altitudes with a 1D polynomial per point " +
                    "and a 2D polynomial across the scene (requires no DEM).")
    private String method = METHOD_DEM_SEED;

    @Parameter(description = "The digital elevation model.",
            defaultValue = "Copernicus 30m Global DEM", label = "Digital Elevation Model")
    private String demName = "Copernicus 30m Global DEM";

    @Parameter(defaultValue = ResamplingFactory.BILINEAR_INTERPOLATION_NAME,
            label = "DEM Resampling Method")
    private String demResamplingMethod = ResamplingFactory.BILINEAR_INTERPOLATION_NAME;

    @Parameter(label = "External DEM")
    private File externalDEMFile = null;

    @Parameter(label = "DEM No Data Value", defaultValue = "0")
    private double externalDEMNoDataValue = 0;

    @Parameter(valueSet = {"100", "200", "300", "400", "500"}, defaultValue = "200",
            label = "Number of estimation points",
            description = "Schwabisch only: number of points at which reference phase is evaluated.")
    private int nPoints = 200;

    @Parameter(valueSet = {"2", "3", "4", "5"}, defaultValue = "3",
            label = "Number of height samples",
            description = "Schwabisch only: number of height samples in the range [0, 5000) m.")
    private int nHeights = 3;

    @Parameter(valueSet = {"1", "2", "3", "4", "5"}, defaultValue = "2",
            label = "Degree of 1D polynomial",
            description = "Schwabisch only: degree of the 1D polynomial fitting height against reference phase.")
    private int degree1D = 2;

    @Parameter(valueSet = {"1", "2", "3", "4", "5", "6", "7", "8"}, defaultValue = "5",
            label = "Degree of 2D polynomial",
            description = "Schwabisch only: degree of the 2D polynomial fitting the 1D coefficients across the scene.")
    private int degree2D = 5;

    @Parameter(valueSet = {"2", "3", "4", "5"}, defaultValue = "3",
            label = "Orbit interpolation degree",
            description = "Degree of the polynomial orbit interpolator.")
    private int orbitDegree = 3;

    private ElevationModel dem = null;
    private FileElevationModel fileElevationModel = null;
    private TiePointGrid latitudeTPG = null;
    private TiePointGrid longitudeTPG = null;
    private TiePointGrid incidenceAngleTPG = null;
    private TiePointGrid slantRangeTimeTPG = null;

    private int sourceImageWidth = 0;
    private int sourceImageHeight = 0;
    private boolean isElevationModelAvailable = false;
    private boolean refHeightPhaseComputed = false;

    private double waveNumber = 0.0;
    private double refHeight = 0.0;
    private double refPhase = 0.0;

    private double demNoDataValue = 0; // no data value for DEM
    private double[] lookAngles = null;
    private double firstLineUTC = 0.0; // in days
    private OrbitStateVector[] orbitStateVectors = null;

    private final Baseline baseline = new Baseline();

    // Schwabisch state. Immutable once doExecute() has run, so computeTile()
    // may call applySchwabisch() concurrently from JAI threads.
    private Slant2Height slant2Height = null;
    private boolean isSchwabisch = false;

    private Band unwrappedPhaseBand;
    private static final String PRODUCT_SUFFIX = "_Hgt";
    private static final String ELEVATION_BAND_NAME = "elevation";

    /**
     * Initializes this operator and sets the one and only target product.
     * <p>The target product can be either defined by a field of type {@link Product}
     * annotated with the {@link TargetProduct TargetProduct} annotation or
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
            isSchwabisch = METHOD_SCHWABISCH.equals(method);

            // Map-projected input is no longer rejected outright — that blanket guard also blocked
            // the geocode-first (GSLC) workflow. Instead each method states what it actually needs:
            //
            //  - Schwabisch fits a slant-range-to-height polynomial over a window in RADAR image
            //    coordinates (see computeSchwabischModel), so on a map grid the model would be
            //    evaluated in the wrong coordinate system and return silently wrong heights. It
            //    therefore still requires radar geometry, and says so.
            //  - DEM Seed reads per-pixel latitude/longitude/incidence-angle/slant-range-time tie
            //    point grids. It already fails with a precise message when they are absent, which
            //    is the correct behaviour for a product that lacks them, so no blanket check is
            //    needed: a map-projected product that does carry those grids will work.
            if (isSchwabisch && InputProductValidator.isMapProjected(sourceProduct)) {
                throw new OperatorException("The " + METHOD_SCHWABISCH + " method requires a product in " +
                        "radar geometry: it fits a slant-range-to-height polynomial in radar image " +
                        "coordinates, which is not meaningful on a map-projected grid. Use the '" +
                        METHOD_DEM_SEED + "' method, or run this operator before terrain correction.");
            }

            getMetadata();

            createTargetProduct();

            if (isSchwabisch) {
                // Schwabisch works from the orbits alone - no tie point grids, no DEM.
                validateSchwabischParameters();
            } else {
                getTiePointGrid();
                if (externalDEMFile == null) {
                    DEMFactory.checkIfDEMInstalled(demName);
                }
                DEMFactory.validateDEM(demName, sourceProduct);
            }

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    /**
     * Heavy, scene-global precomputation. Runs once, before any tile is served,
     * so the parameter dialog and Graph Builder stay responsive.
     */
    @Override
    public void doExecute(ProgressMonitor pm) throws OperatorException {

        try {
            pm.beginTask("Computing phase to elevation model...", 100);

            if (isSchwabisch) {
                computeSchwabischModel();
            } else {
                getElevationModel();
                getBaseline();
                computeReferenceHeightAndPhase(unwrappedPhaseBand, baseline);
            }

            pm.worked(100);
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        } finally {
            pm.done();
        }
    }

    /**
     * The 2D polynomial in the Schwabisch solution has
     * {@code numberOfCoefficients(degree2D)} unknowns, and each estimation point
     * contributes one observation. Fail early with an actionable message rather
     * than letting jlinda throw a bare IllegalArgumentException mid-run.
     */
    private void validateSchwabischParameters() {

        final int numUnknowns = PolyUtils.numberOfCoefficients(degree2D);
        if (nPoints < numUnknowns) {
            throw new OperatorException("Schwabisch: " + nPoints + " estimation points is fewer than the "
                    + numUnknowns + " coefficients of a degree-" + degree2D + " 2D polynomial. "
                    + "Increase 'Number of estimation points' or decrease 'Degree of 2D polynomial'.");
        }
    }

    @Override
    public synchronized void dispose() {
        if (dem != null) {
            dem.dispose();
            dem = null;
        }
        if (fileElevationModel != null) {
            fileElevationModel.dispose();
        }
    }

    /**
     * Retrieve required data from Abstracted Metadata
     *
     * @throws Exception if metadata not found
     */
    private void getMetadata() throws Exception {

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);
        final double wavelength = SARUtils.getRadarWavelength(absRoot);
        waveNumber = Constants.TWO_PI / wavelength;
        orbitStateVectors = AbstractMetadata.getOrbitStateVectors(absRoot);
        firstLineUTC = absRoot.getAttributeUTC(AbstractMetadata.first_line_time).getMJD(); // in days

        sourceImageWidth = sourceProduct.getSceneRasterWidth();
        sourceImageHeight = sourceProduct.getSceneRasterHeight();
    }

    /**
     * Get incidence angle and slant range time tie point grids.
     */
    private void getTiePointGrid() {

        latitudeTPG = OperatorUtils.getLatitude(sourceProduct);
        if (latitudeTPG == null) {
            throw new OperatorException("Cannot find latitude tie point grid with the source product");
        }

        longitudeTPG = OperatorUtils.getLongitude(sourceProduct);
        if (longitudeTPG == null) {
            throw new OperatorException("Cannot find longitude tie point grid with the source product");
        }

        incidenceAngleTPG = OperatorUtils.getIncidenceAngle(sourceProduct);
        if (incidenceAngleTPG == null) {
            throw new OperatorException("Cannot find incidence angle tie point grid with the source product");
        }

        slantRangeTimeTPG = OperatorUtils.getSlantRangeTime(sourceProduct);
        if (slantRangeTimeTPG == null) {
            throw new OperatorException("Cannot find slant range time tie point grid with the source product");
        }
    }

    /**
     * Create target product.
     */
    private void createTargetProduct() {

        targetProduct = new Product(sourceProduct.getName() + PRODUCT_SUFFIX,
                sourceProduct.getProductType(),
                sourceImageWidth,
                sourceImageHeight);

        addSelectedBands();

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        final MetadataElement absTgt = AbstractMetadata.getAbstractedMetadata(targetProduct);

        if (isSchwabisch) {
            absTgt.setAttributeString("phase to elevation method", METHOD_SCHWABISCH);
            return;
        }

        absTgt.setAttributeString("phase to elevation method", METHOD_DEM_SEED);

        if (externalDEMFile != null && fileElevationModel == null) { // if external DEM file is specified by user
            AbstractMetadata.setAttribute(absTgt, AbstractMetadata.DEM, externalDEMFile.getPath());
        } else {
            AbstractMetadata.setAttribute(absTgt, AbstractMetadata.DEM, demName);
        }

        absTgt.setAttributeString("DEM resampling method", demResamplingMethod);

        if (externalDEMFile != null) {
            absTgt.setAttributeDouble("external DEM no data value", externalDEMNoDataValue);
        }
    }

    /**
     * Add user selected bands to target product.
     */
    private void addSelectedBands() {

        unwrappedPhaseBand = findUnwrappedPhaseBand(
                OperatorUtils.getSourceBands(sourceProduct, null, false));

        if (unwrappedPhaseBand == null) {
            throw new OperatorException("Cannot find UnwrappedPhase band in the source product. "
                    + "Expected a band with unit '" + Unit.ABS_PHASE + "' or a name starting with 'Unw'. "
                    + "Run phase unwrapping (Snaphu Import) before this operator.");
        }

        final Band targetBand = new Band(ELEVATION_BAND_NAME, ProductData.TYPE_FLOAT32,
                sourceImageWidth, sourceImageHeight);

        targetBand.setUnit(Unit.METERS);
        targetBand.setNoDataValue(Double.NaN);
        targetBand.setNoDataValueUsed(true);
        targetProduct.addBand(targetBand);
    }

    /**
     * Locate the unwrapped phase band. Unit-based discovery is preferred because
     * Snaphu Import tags unwrapped bands as {@link Unit#ABS_PHASE} regardless of
     * how they are named; the name check remains as a fallback for products
     * written before that convention.
     */
    static Band findUnwrappedPhaseBand(final Band[] sourceBands) {

        for (Band band : sourceBands) {
            if (Unit.ABS_PHASE.equals(band.getUnit())) {
                return band;
            }
        }
        for (Band band : sourceBands) {
            if (band.getName().toLowerCase().startsWith("unw")) {
                return band;
            }
        }
        return null;
    }

    private void getBaseline() throws Exception {
        final MetadataElement referenceMeta = AbstractMetadata.getAbstractedMetadata(sourceProduct);
        final SLCImage referenceMetaData = new SLCImage(referenceMeta, sourceProduct);
        final Orbit referenceOrbit = new Orbit(referenceMeta, orbitDegree);

        final MetadataElement[] secondaryRoot = StackUtils.findSecondaryMetadataRoot(sourceProduct).getElements();
        final SLCImage secondaryMetaData = new SLCImage(secondaryRoot[0], sourceProduct);
        final Orbit secondaryOrbit = new Orbit(secondaryRoot[0], orbitDegree);

        baseline.model(referenceMetaData, secondaryMetaData, referenceOrbit, secondaryOrbit);
    }

    /**
     * Set up the Doris "Schwabisch" phase-to-height model (ported from the
     * deprecated PhaseToHeight / Slant2HeightOp operator):
     * <ol>
     *   <li>evaluate reference phase at {@code nHeights} altitudes in {@code nPoints}
     *       points distributed over the scene, using the precise orbits;</li>
     *   <li>solve h(phi) as a {@code degree1D} polynomial at each point;</li>
     *   <li>model each 1D coefficient as a {@code degree2D} 2D polynomial in (line, pixel).</li>
     * </ol>
     * No DEM is involved - the ambiguity reference comes from the orbit geometry.
     */
    private void computeSchwabischModel() throws Exception {

        final MetadataElement referenceMeta = AbstractMetadata.getAbstractedMetadata(sourceProduct);
        final SLCImage referenceMetaData = new SLCImage(referenceMeta, sourceProduct);
        final Orbit referenceOrbit = new Orbit(referenceMeta, orbitDegree);

        final MetadataElement[] secondaryRoot = StackUtils.findSecondaryMetadataRoot(sourceProduct).getElements();
        if (secondaryRoot.length == 0) {
            throw new OperatorException("No secondary metadata found. "
                    + "PhaseToElevation requires an interferometric product.");
        }
        final SLCImage secondaryMetaData = new SLCImage(secondaryRoot[0], sourceProduct);
        final Orbit secondaryOrbit = new Orbit(secondaryRoot[0], orbitDegree);

        final Slant2Height s2h = new Slant2Height(nPoints, nHeights, degree1D, degree2D,
                referenceMetaData, referenceOrbit, secondaryMetaData, secondaryOrbit);
        // Window bounds match those used by the original Slant2HeightOp so that
        // results are reproducible against the deprecated operator. They only set
        // the polynomial normalisation range and the point distribution.
        s2h.setDataWindow(new Window(0, sourceImageHeight, 0, sourceImageWidth));
        s2h.schwabisch();

        slant2Height = s2h;
    }

    /**
     * Compute the elevation band for one tile. All scene-global state was
     * computed in {@link #doExecute} and is read-only here, so this is safe to
     * call concurrently from the JAI tile scheduler.
     *
     * @param targetBand The target band.
     * @param targetTile The current tile to be computed.
     * @param pm         A progress monitor used to determine computation cancelation requests.
     * @throws OperatorException if an error occurs during computation of the target raster.
     */
    @Override
    public void computeTile(final Band targetBand, final Tile targetTile, final ProgressMonitor pm)
            throws OperatorException {

        try {
            if (isSchwabisch) {
                computeSchwabischTile(targetTile);
            } else {
                computeSeedReferencedTile(targetTile);
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    /**
     * Evaluate the Schwabisch polynomial model h = f(line, pixel, phase) over the tile.
     */
    private void computeSchwabischTile(final Tile targetTile) {

        final Rectangle rect = targetTile.getRectangle();
        final Tile sourceTile = getSourceTile(unwrappedPhaseBand, rect);

        // applySchwabisch converts in place: pull the phase, overwrite with height.
        final DoubleMatrix data = TileUtilsDoris.pullDoubleMatrix(sourceTile);
        final Window tileWindow = new Window(rect.y, rect.y + rect.height - 1,
                rect.x, rect.x + rect.width - 1);
        slant2Height.applySchwabisch(tileWindow, data);

        TileUtilsDoris.pushDoubleMatrix(data, targetTile, rect);
    }

    /**
     * Linearised conversion about the reference (height, phase) solved from DEM seeds.
     */
    private void computeSeedReferencedTile(final Tile targetTile) throws Exception {

        final Rectangle rect = targetTile.getRectangle();
        final Tile sourceTile = getSourceTile(unwrappedPhaseBand, rect);
        final ProductData sourceData = sourceTile.getDataBuffer();
        final ProductData targetData = targetTile.getDataBuffer();
        final TileIndex srcIndex = new TileIndex(sourceTile);
        final TileIndex trgIndex = new TileIndex(targetTile);

        final int x0 = rect.x;
        final int y0 = rect.y;
        final int w = rect.width;
        final int h = rect.height;

        final int xc = sourceImageWidth / 2;
        double phase, slantRange, incidenceAngle, bn, bp, alpha, height, flatAngle;
        for (int y = y0; y < y0 + h; y++) {
            srcIndex.calculateStride(y);
            trgIndex.calculateStride(y);
            for (int x = x0; x < x0 + w; x++) {

                phase = sourceData.getElemDoubleAt(srcIndex.getIndex(x));
                slantRange = slantRangeTimeTPG.getPixelDouble(x, y) / Constants.oneBillion * Constants.halfLightSpeed;
                incidenceAngle = incidenceAngleTPG.getPixelDouble(x, y) * MathUtils.DTOR;
                bn = baseline.getBperp(y, x);
                bp = baseline.getBpar(y, x);
                flatAngle = lookAngles[x] - lookAngles[xc];
                alpha = -slantRange * FastMath.sin(incidenceAngle) /
                        (2 * waveNumber * (bp * FastMath.sin(flatAngle) + bn * FastMath.cos(flatAngle)));
                height = refHeight + alpha * (phase - refPhase);
                targetData.setElemDoubleAt(trgIndex.getIndex(x), height);
            }
        }
    }

    /**
     * Get elevation model.
     *
     * @throws Exception The exceptions.
     */
    private synchronized void getElevationModel() throws Exception {

        if (isElevationModelAvailable) {
            return;
        }

        if (externalDEMFile != null && fileElevationModel == null) { // if external DEM file is specified by user

            fileElevationModel = new FileElevationModel(externalDEMFile, demResamplingMethod, externalDEMNoDataValue);
            demNoDataValue = externalDEMNoDataValue;
            demName = externalDEMFile.getPath();

        } else {

            final ElevationModelRegistry elevationModelRegistry = ElevationModelRegistry.getInstance();
            final ElevationModelDescriptor demDescriptor = elevationModelRegistry.getDescriptor(demName);
            if (demDescriptor == null) {
                throw new OperatorException("The DEM '" + demName + "' is not supported.");
            }

            dem = demDescriptor.createDem(ResamplingFactory.createResampling(demResamplingMethod));
            if (dem == null) {
                throw new OperatorException("The DEM '" + demName + "' has not been installed.");
            }

            demNoDataValue = dem.getDescriptor().getNoDataValue();
        }
        isElevationModelAvailable = true;
    }

    private synchronized void computeReferenceHeightAndPhase(final Band unwrappedPhaseBand, final Baseline baseline)
            throws Exception {

        if (refHeightPhaseComputed) {
            return;
        }

        computeLookAngles();

        // get initial 100x100 seeds and compute their slopes
        final int seedGridSize = 100;
        final int slopeCalRadius = 4;
        final int seedGridResY = (sourceImageHeight - 1 - 2 * slopeCalRadius) / (seedGridSize - 1);
        final int seedGridResX = (sourceImageWidth - 1 - 2 * slopeCalRadius) / (seedGridSize - 1);
        List<SeedRecord> seedList = new ArrayList<>(seedGridSize * seedGridSize);

        for (int r = 0; r < seedGridSize; r++) {
            final int y = r * seedGridResY + slopeCalRadius;
            for (int c = 0; c < seedGridSize; c++) {
                final int x = c * seedGridResX + slopeCalRadius;
                final Double h = getElevation(x, y);
                if (!h.equals(demNoDataValue) && h > 0.0) {
                    SeedRecord seed = new SeedRecord(x, y, h, computeSlope(x, y, slopeCalRadius));
                    seedList.add(seed);
                }
            }
        }

        // sort the seed list in ascending order according to the seed's slope
        Collections.sort(seedList);

        // get the final seed list
        final int maskSize = 15;
        final int totalFinalSeeds = 150;
        boolean[][] mask = new boolean[maskSize][maskSize];
        SeedRecord[] finalSeedList = new SeedRecord[totalFinalSeeds];
        int numSeeds = 0;
        for (SeedRecord seed : seedList) {
            int maskX = (int) ((double) seed.x / sourceImageWidth * maskSize);
            int maskY = (int) ((double) seed.y / sourceImageHeight * maskSize);
            if (!mask[maskY][maskX]) {
                finalSeedList[numSeeds++] = new SeedRecord(seed.x, seed.y, seed.height, seed.slope);
                if (numSeeds >= totalFinalSeeds) {
                    break;
                }
                mask[maskY][maskX] = true;
            }
        }

        // get unwrapped phases for seeds in the final seed list
        final double[] phaseList = new double[numSeeds];
        for (int i = 0; i < numSeeds; i++) {
            SeedRecord seed = finalSeedList[i];
            final Rectangle srcRect = new Rectangle(seed.x, seed.y, 1, 1);
            final Tile sourceTile = getSourceTile(unwrappedPhaseBand, srcRect);
            phaseList[i] = sourceTile.getDataBuffer().getElemDoubleAt(sourceTile.getDataBufferIndex(seed.x, seed.y));
        }

        // Compute reference (elevation, phase) using least square method
        final int xc = sourceImageWidth / 2;
        double phase, slantRange, incidenceAngle, bn, bp, alpha, flatAngle;
        double a = 0.0, b = 0.0, c = 0.0, d = 0.0, e = 0.0, f = 0.0;
        for (int i = 0; i < numSeeds; i++) {
            SeedRecord seed = finalSeedList[i];
            phase = phaseList[i];
            slantRange = slantRangeTimeTPG.getPixelDouble(seed.x, seed.y) / Constants.oneBillion * Constants.halfLightSpeed;
            incidenceAngle = incidenceAngleTPG.getPixelDouble(seed.x, seed.y) * MathUtils.DTOR;
            bn = baseline.getBperp(seed.y, seed.x);
            bp = baseline.getBpar(seed.y, seed.x);
            flatAngle = lookAngles[seed.x] - lookAngles[xc];
            alpha = -slantRange * FastMath.sin(incidenceAngle) /
                    (2 * waveNumber * (bp * FastMath.sin(flatAngle) + bn * FastMath.cos(flatAngle)));
//            alpha = -slantRange*Math.sin(incidenceAngle)/(2*waveNumber*bn);
            a += -alpha * alpha;
            b += alpha;
            e += alpha * (seed.height - alpha * phase);
            f += seed.height - alpha * phase;
        }
        c = -b;
        d = numSeeds;

        final double denom = a * d - c * b;
        if (numSeeds == 0 || denom == 0.0 || Double.isNaN(denom) || Double.isInfinite(denom)) {
            throw new OperatorException("PhaseToElevation: cannot solve for reference height/phase. "
                    + "No valid seeds (numSeeds=" + numSeeds + ") or degenerate geometry "
                    + "(all seeds share the same flat-Earth angle).");
        }

        refHeight = (a * f - c * e) / denom;
        refPhase = (e * d - b * f) / denom;

        refHeightPhaseComputed = true;
    }

    private synchronized void computeLookAngles() {

        double[] senPos = new double[3];
        getSensorPosition(firstLineUTC, senPos);

        final double ht = Math.sqrt(senPos[0] * senPos[0] + senPos[1] * senPos[1] + senPos[2] * senPos[2]); // satelliteHeight
        final double er = computeEarthRadius(senPos[2], ht);  // earthRadius

        lookAngles = new double[sourceImageWidth];
        for (int x = 0; x < sourceImageWidth; x++) {
            final double sr = slantRangeTimeTPG.getPixelDouble(x, 0) / Constants.oneBillion * Constants.halfLightSpeed;
            lookAngles[x] = FastMath.acos((sr * sr + ht * ht - er * er) / (2.0 * sr * ht));
        }
    }

    private void getSensorPosition(final double time, double[] senPos) {

        final int numVectors = orbitStateVectors.length;
        final int numVectorsUsed = Math.min(orbitStateVectors.length, 5);
        final int d = numVectors / numVectorsUsed;
        final double[] timeArray = new double[numVectorsUsed];
        final double[] xPosArray = new double[numVectorsUsed];
        final double[] yPosArray = new double[numVectorsUsed];
        final double[] zPosArray = new double[numVectorsUsed];
        for (int i = 0; i < numVectorsUsed; i++) {
            timeArray[i] = orbitStateVectors[i * d].time_mjd;
            xPosArray[i] = orbitStateVectors[i * d].x_pos; // m
            yPosArray[i] = orbitStateVectors[i * d].y_pos; // m
            zPosArray[i] = orbitStateVectors[i * d].z_pos; // m
        }
        senPos[0] = Maths.lagrangeInterpolatingPolynomial(timeArray, xPosArray, time);
        senPos[1] = Maths.lagrangeInterpolatingPolynomial(timeArray, yPosArray, time);
        senPos[2] = Maths.lagrangeInterpolatingPolynomial(timeArray, zPosArray, time);
    }

    private static double computeEarthRadius(final double senPosZ, final double satelliteHeight) {
        final double re = Constants.semiMajorAxis;
        final double rp = Constants.semiMinorAxis;
        final double lat = FastMath.asin(senPosZ / satelliteHeight);
        return (re * rp) / Math.sqrt(rp * rp * FastMath.cos(lat) * FastMath.cos(lat) + re * re * FastMath.sin(lat) * FastMath.sin(lat));
    }

    private double getElevation(final int x, final int y) throws Exception {

        final GeoPos geoPos = new GeoPos();
        double alt;
        geoPos.setLocation(latitudeTPG.getPixelDouble(x, y), longitudeTPG.getPixelDouble(x, y));
        if (externalDEMFile == null) {
            alt = dem.getElevation(geoPos);
        } else {
            alt = fileElevationModel.getElevation(geoPos);
        }

        return alt;
    }

    private double computeSlope(final int xc, final int yc, final int slopeCalRadius) throws Exception {

        double slope = 0.0;
        Double h = 0.0;
        int numPoints = 0;
        final double hc = getElevation(xc, yc);
        final int halfSlopeCalRadius = slopeCalRadius / 2;
        for (int y = yc - slopeCalRadius; y <= yc + slopeCalRadius; y += slopeCalRadius) {
            for (int x = xc - slopeCalRadius; x <= xc + slopeCalRadius; x += halfSlopeCalRadius) {
                h = getElevation(x, y);
                if (!h.equals(demNoDataValue)) {
                    slope += Math.abs(h - hc);
                    numPoints++;
                }
            }
        }
        return slope / numPoints;
    }

    static class SeedRecord implements Comparable<SeedRecord> {
        public int x;
        public int y;
        public double height;
        public double slope;

        SeedRecord(final int x, final int y, final double h, final double slope) {
            this.x = x;
            this.y = y;
            this.height = h;
            this.slope = slope;
        }

        public int compareTo(SeedRecord record) {
            // Use Double.compare to honour the Comparator contract (returns 0 for equal,
            // handles NaN/-0.0/+0.0 deterministically). The previous implementation
            // returned +1 for equal slopes, which makes Collections.sort throw
            // "Comparison method violates its general contract" on TimSort.
            return Double.compare(slope, record.slope);
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
            super(PhaseToElevationOp.class);
        }
    }
}
