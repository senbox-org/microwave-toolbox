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
package eu.esa.sar.insar.gpf.coregistration;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.CRSGeoCodingHandler;
import eu.esa.sar.commons.Resolution;
import eu.esa.sar.commons.SARGeocoding;
import org.esa.snap.core.subset.PixelSubsetRegion;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import eu.esa.sar.insar.gpf.InSARStackOverview;
import org.esa.snap.core.dataio.ProductSubsetBuilder;
import org.esa.snap.core.dataio.ProductSubsetDef;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.GeoCoding;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.MetadataAttribute;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Placemark;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.ProductNodeGroup;
import org.esa.snap.core.datamodel.VirtualBand;
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
import org.esa.snap.core.util.FeatureUtils;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.ProductInformation;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.StackUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;
import org.jlinda.core.Orbit;
import org.jlinda.core.Point;
import org.jlinda.core.SLCImage;

import java.awt.Rectangle;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The CreateStack operator.
 */
@OperatorMetadata(alias = "CreateStack",
        category = "Radar/Coregistration/Stack Tools",
        authors = "Jun Lu, Luis Veci",
        version = "1.0",
        copyright = "Copyright (C) 2014 by Array Systems Computing Inc.",
        description = "Collocates two or more products based on their geo-codings.")
public class CreateStackOp extends Operator {

    @SourceProducts
    private Product[] sourceProduct;

    @Parameter(description = "The list of source bands.", alias = "masterBands",
            rasterDataNodeType = Band.class, label = "Reference Band")
    private String[] masterBandNames = null;

    @Parameter(description = "The list of source bands.", alias = "sourceBands",
            rasterDataNodeType = Band.class, label = "Secondary Bands")
    private String[] slaveBandNames = null;

    private Product referenceProduct = null;
    private final Band[] referenceBands = new Band[2];

    @TargetProduct(description = "The target product which will use the reference's grid.")
    private Product targetProduct = null;

    @Parameter(defaultValue = "NONE",
            description = "The method to be used when resampling the secondary grid onto the reference grid.",
            label = "Resampling Type")
    private String resamplingType = "NONE";
    private Resampling selectedResampling = null;

    @Parameter(valueSet = {MASTER_EXTENT, MIN_EXTENT, MAX_EXTENT},
            defaultValue = MASTER_EXTENT,
            description = "The output image extents.",
            label = "Output Extents")
    private String extent = MASTER_EXTENT;

    public final static String MASTER_EXTENT = "Master";
    public final static String MIN_EXTENT = "Minimum";
    public final static String MAX_EXTENT = "Maximum";

    public final static String INITIAL_OFFSET_GEOLOCATION = "Product Geolocation";
    public final static String INITIAL_OFFSET_ORBIT = "Orbit";

    @Parameter(valueSet = {INITIAL_OFFSET_ORBIT, INITIAL_OFFSET_GEOLOCATION},
            defaultValue = INITIAL_OFFSET_ORBIT,
            description = "Method for computing initial offset between reference and secondary",
            label = "Initial Offset Method")
    private String initialOffsetMethod = INITIAL_OFFSET_ORBIT;

    /**
     * When {@code true}, GSLC inputs trigger automatic cross-correlation-based bias
     * estimation and slave re-geocoding so the resulting stack is sub-pixel coregistered
     * without any user intervention. This is what makes the GUI's builtin
     * "Create Stack" graph produce coherent interferograms — but it's heavy
     * (reloads source SLCs from disk, runs CC, re-runs GSLC on each slave with bias).
     * Default {@code true} (user-facing convenience). Tests that don't need the bias
     * correction should set it to {@code false} to avoid the heavy work.
     */
    @Parameter(defaultValue = "true",
            description = "Auto-estimate cross-correlation bias and re-geocode GSLC slaves " +
                    "when the input stack contains geocoded products.",
            label = "Auto-coregister GSLC slaves")
    private boolean autoCoregisterGSLC = true;

    /**
     * If true, skip the (slow) cross-correlation pass against the master SLC and rely on
     * geometric coregistration only (bias=0). With master and slave on the same grid
     * (locked via {@link #applyMasterGridLockParams}), bias=0 still produces fringes —
     * possibly with a sub-pixel residual ramp, but correlation pattern visible. Useful
     * for fast iteration on full-resolution stacks where CC takes minutes.
     */
    @Parameter(defaultValue = "false",
            description = "Skip the cross-correlation refinement step; use pure geometric coregistration. Faster but less accurate.",
            label = "Skip GSLC bias estimation (geometric only)")
    private boolean skipBiasEstimation = false;

    private final Map<Band, Band> sourceRasterMap = new HashMap<>(10);
    private final Map<Product, int[]> secondaryOffsetMap = new HashMap<>(10);

    private boolean appendToReference = false;
    private boolean isResampling = false;

    private static final String PRODUCT_SUFFIX = "_Stack";

    /**
     * State that {@link #maybeAutoGeocodeAgainstReference} parks for later: the master
     * SLC reloaded from {@code gslc_source_slc_path}, plus a queue of jobs describing
     * each slave that needs bias estimation + GSLC rebuild. Populated in
     * {@link #initialize()}; consumed in {@link #doExecute(ProgressMonitor)}.
     */
    private Product reloadedMasterSlcForBias = null;
    private final java.util.List<PendingBiasJob> pendingBiasJobs = new java.util.ArrayList<>();
    private volatile boolean biasJobsRan = false;

    /**
     * Thread-local set to {@code true} while inside {@link #estimateSlcBias}. The nested
     * CreateStackOp that runs there must NEVER auto-coregister — otherwise we get
     * infinite recursion if reload-master-SLC returns the GSLC itself. The initialize()
     * safety net checks this flag before promoting {@code autoCoregisterGSLC=true}.
     */
    private static final ThreadLocal<Boolean> INSIDE_BIAS_ESTIMATION = ThreadLocal.withInitial(() -> false);

    /**
     * SNAP desktop installs {@code SnapAppGPFOperatorExecutor} as GPF's executor; its
     * constructor calls {@code WindowManager.getMainWindow()}, which is fatal off the EDT.
     * When CreateStack chains in-memory operators (the placeholder GSLC), every JAI tile
     * fetch triggers {@code GPF.executeOperator} on a worker thread and hits the EDT check.
     * We swap in a passthrough executor for the lifetime of this op instance and restore
     * the original in {@link #dispose}.
     */
    private static final Object GPF_EXECUTOR_LOCK = new Object();
    private static volatile Object savedGpfExecutor = null;
    private static volatile int executorSwapRefcount = 0;
    private boolean ownsExecutorSwap = false;

    /** One slave that needs bias-driven GSLC rebuild during {@code doExecute}. */
    private static final class PendingBiasJob {
        final int slaveIdx;          // index into sourceProduct[]
        final Product slaveSlc;      // slant-range SLC to use as the GSLC source
        final boolean disposeSlaveSlcAfter;  // true if slaveSlc was reloaded from disk
        final Product placeholderSlaveGslc;  // bias=0 GSLC built during initialize
        PendingBiasJob(final int slaveIdx, final Product slaveSlc,
                       final boolean disposeAfter, final Product placeholder) {
            this.slaveIdx = slaveIdx;
            this.slaveSlc = slaveSlc;
            this.disposeSlaveSlcAfter = disposeAfter;
            this.placeholderSlaveGslc = placeholder;
        }
    }

    /**
     * Replace GPF's progress-monitored operator executor with a passthrough one for the
     * duration of this operator's lifecycle. Reference-counted so nested CreateStacks
     * share the same swap. Saved value is restored in {@link #dispose}.
     */
    private void installPassthroughGpfExecutor() {
        synchronized (GPF_EXECUTOR_LOCK) {
            if (executorSwapRefcount == 0) {
                try {
                    final org.esa.snap.core.gpf.GPF gpf = org.esa.snap.core.gpf.GPF.getDefaultInstance();
                    final java.lang.reflect.Field field =
                            org.esa.snap.core.gpf.GPF.class.getDeclaredField("operatorExecutor");
                    field.setAccessible(true);
                    savedGpfExecutor = field.get(gpf);
                    final Class<?> ifaceClass = Class.forName(
                            "org.esa.snap.core.gpf.ProgressMonitoredOperatorExecutor");
                    final Object passthrough = java.lang.reflect.Proxy.newProxyInstance(
                            ifaceClass.getClassLoader(),
                            new Class<?>[]{ifaceClass},
                            (proxy, method, args) -> {
                                if ("execute".equals(method.getName()) && args != null && args.length == 1) {
                                    ((org.esa.snap.core.gpf.Operator) args[0])
                                            .execute(com.bc.ceres.core.ProgressMonitor.NULL);
                                }
                                return null;
                            });
                    field.set(gpf, passthrough);
                    SystemUtils.LOG.fine("CreateStack: installed passthrough GPF executor " +
                            "(saved=" + (savedGpfExecutor == null ? "null" : savedGpfExecutor.getClass().getName()) + ")");
                } catch (Throwable t) {
                    SystemUtils.LOG.warning("CreateStack: failed to install passthrough GPF executor: " +
                            t.getMessage() + " — nested operator chains may crash on the EDT.");
                    return;
                }
            }
            executorSwapRefcount++;
            ownsExecutorSwap = true;
        }
    }

    private void restoreGpfExecutor() {
        synchronized (GPF_EXECUTOR_LOCK) {
            if (!ownsExecutorSwap) return;
            ownsExecutorSwap = false;
            executorSwapRefcount--;
            if (executorSwapRefcount == 0 && savedGpfExecutor != null) {
                try {
                    final java.lang.reflect.Field field =
                            org.esa.snap.core.gpf.GPF.class.getDeclaredField("operatorExecutor");
                    field.setAccessible(true);
                    field.set(org.esa.snap.core.gpf.GPF.getDefaultInstance(), savedGpfExecutor);
                    SystemUtils.LOG.fine("CreateStack: restored GPF executor");
                } catch (Throwable t) {
                    SystemUtils.LOG.warning("CreateStack: failed to restore GPF executor: " + t.getMessage());
                }
                savedGpfExecutor = null;
            }
        }
    }

    @Override
    public void dispose() {
        restoreGpfExecutor();
        super.dispose();
    }

    @Override
    public void initialize() throws OperatorException {

        try {
            if (sourceProduct == null) {
                return;
            }

            if (sourceProduct.length < 2) {
                throw new OperatorException("Please select at least two source products");
            }

            // Safety net: if the UI / paramMap left autoCoregisterGSLC=false but the user
            // actually fed mixed-geometry inputs (master GSLC + raw SLC slaves), auto-enable
            // it so the workflow doesn't dead-end on the geometry-mixing throw below. Skip
            // when running inside estimateSlcBias's nested CreateStack — that path
            // explicitly opts out of auto-coregister and must stay opted out to avoid
            // recursion.
            if (!autoCoregisterGSLC && anySourceIsGeocoded() && !allSourcesAreGeocoded()
                    && !INSIDE_BIAS_ESTIMATION.get()) {
                SystemUtils.LOG.warning("CreateStack: mixed geocoded + slant-range inputs detected — " +
                        "auto-enabling GSLC coregistration.");
                autoCoregisterGSLC = true;
            }

            // We're about to chain GSLC-Terrain-Correction (and possibly Cross-Correlation)
            // inside our pipeline. Swap GPF's executor to a non-EDT passthrough so JAI
            // tile reads from those chained operators don't crash on the SnapApp executor's
            // getMainFrame() call (which requires EDT and can't be reached from JAI workers).
            if (autoCoregisterGSLC && anySourceIsGeocoded()) {
                installPassthroughGpfExecutor();
            }

            // If the user passed a geocoded reference (e.g. a master GSLC) alongside raw SLCs,
            // auto-promote those SLCs onto the reference's grid by invoking GSLC-Terrain-Correction
            // here. The user then only has to run GSLCGeocoding once (on the master) — the slave
            // geocoding-and-alignment is folded into the stack creation.
            maybeAutoGeocodeAgainstReference();

            for (final Product prod : sourceProduct) {
                final InputProductValidator validator = new InputProductValidator(prod);
                final MetadataElement prodAbsRoot = AbstractMetadata.getAbstractedMetadata(prod);
                final boolean isTerrainCorrected = prodAbsRoot != null &&
                        prodAbsRoot.getAttributeInt(AbstractMetadata.is_terrain_corrected, 0) == 1;
                if(validator.isTOPSARProduct() && !validator.isDebursted() && !isTerrainCorrected) {
                    throw new OperatorException("For S1 TOPS SLC products, TOPS Coregistration should be used");
                }

                if (prod.getSceneGeoCoding() == null) {
                    throw new OperatorException(
                            MessageFormat.format("Product ''{0}'' has no geo-coding", prod.getName()));
                }
            }

            if (masterBandNames == null || masterBandNames.length == 0 || getReferenceProduct(masterBandNames[0]) == null) {
                masterBandNames = getReferenceBands();
                if (masterBandNames.length == 0) {
                    targetProduct = OperatorUtils.createDummyTargetProduct(sourceProduct);
                    return;
                }
            }

            referenceProduct = getReferenceProduct(masterBandNames[0]);
            if (referenceProduct == null) {
                targetProduct = OperatorUtils.createDummyTargetProduct(sourceProduct);
                return;
            }

            appendToReference = AbstractMetadata.getAbstractedMetadata(referenceProduct).
                    getAttributeInt(AbstractMetadata.coregistered_stack, 0) == 1 ||
                    AbstractMetadata.getAbstractedMetadata(referenceProduct).getAttributeInt("collocated_stack", 0) == 1;
            final List<String> referenceProductBands = new ArrayList<>(referenceProduct.getNumBands());

            final Band[] secondaryBandList = getSecondaryBands();
            if (referenceProduct == null || secondaryBandList.length == 0 || secondaryBandList[0] == null) {
                targetProduct = OperatorUtils.createDummyTargetProduct(sourceProduct);
                return;
            }

            isResampling = !resamplingType.contains("NONE");
            if (!isResampling && !extent.equals(MASTER_EXTENT)) {
                throw new OperatorException("Please select only Master extents when resampling type is None");
            }

            if (appendToReference) {
                extent = MASTER_EXTENT;
            }

            switch (extent) {
                case MASTER_EXTENT:

                    targetProduct = new Product(OperatorUtils.createProductName(referenceProduct.getName(), PRODUCT_SUFFIX),
                                                referenceProduct.getProductType(),
                                                referenceProduct.getSceneRasterWidth(),
                                                referenceProduct.getSceneRasterHeight());

                    ProductUtils.copyProductNodes(referenceProduct, targetProduct);
                    break;
                case MIN_EXTENT:
                    determineMinExtents();
                    break;
                default:
                    determineMaxExtents();
                    break;
            }

            if (appendToReference) {
                // add all reference bands
                for (Band b : referenceProduct.getBands()) {
                    if (!(b instanceof VirtualBand)) {
                        final Band targetBand = new Band(b.getName(),
                                                         b.getDataType(),
                                                         targetProduct.getSceneRasterWidth(),
                                                         targetProduct.getSceneRasterHeight());
                        referenceProductBands.add(b.getName());
                        sourceRasterMap.put(targetBand, b);
                        targetProduct.addBand(targetBand);

                        ProductUtils.copyRasterDataNodeProperties(b, targetBand);
                        targetBand.setSourceImage(b.getSourceImage());
                    }
                }
            }

            String suffix = StackUtils.REF;
            // add reference bands first
            if (!appendToReference) {
                for (final Band srcBand : secondaryBandList) {
                    if (srcBand.getProduct() == referenceProduct) {
                        suffix = StackUtils.REF + StackUtils.createBandTimeStamp(srcBand.getProduct());
                        int dataType;
                        if (!extent.equals(MAX_EXTENT)) {
                            dataType = srcBand.getDataType();
                        } else {
                            dataType = ProductData.TYPE_FLOAT32;
                        }

                        final Band targetBand = new Band(srcBand.getName() + suffix,
                                                         dataType,
                                                         targetProduct.getSceneRasterWidth(),
                                                         targetProduct.getSceneRasterHeight());
                        referenceProductBands.add(targetBand.getName());
                        sourceRasterMap.put(targetBand, srcBand);
                        targetProduct.addBand(targetBand);

                        ProductUtils.copyRasterDataNodeProperties(srcBand, targetBand);
                        if(targetBand.getValidPixelExpression() != null) {
                            targetBand.setValidPixelExpression(srcBand.getValidPixelExpression().replace(srcBand.getName(), targetBand.getName()));
                        }

                        if (extent.equals(MASTER_EXTENT)) {
                            targetBand.setSourceImage(srcBand.getSourceImage());
                        }
                    }
                }
            }
            // then add secondary bands
            int cnt = 1;
            if (appendToReference) {
                for (Band trgBand : targetProduct.getBands()) {
                    final String name = trgBand.getName();
                    if (name.contains(StackUtils.SEC + cnt))
                        ++cnt;
                }
            }
            for (final Band srcBand : secondaryBandList) {
                if (srcBand.getProduct() != referenceProduct) {
                    if (srcBand.getUnit() != null && srcBand.getUnit().equals(Unit.IMAGINARY)) {
                    } else {
                        suffix = StackUtils.SEC + cnt++ + StackUtils.createBandTimeStamp(srcBand.getProduct());
                    }
                    final String tgtBandName = srcBand.getName() + suffix;

                    if (targetProduct.getBand(tgtBandName) == null) {
                        final Product srcProduct = srcBand.getProduct();
                        int dataType;
                        if (!isResampling) {
                            dataType = srcBand.getDataType();
                        } else {
                            dataType = ProductData.TYPE_FLOAT32;
                        }

                        final Band targetBand = new Band(tgtBandName,
                                                         dataType,
                                                         targetProduct.getSceneRasterWidth(),
                                                         targetProduct.getSceneRasterHeight());
                        sourceRasterMap.put(targetBand, srcBand);
                        targetProduct.addBand(targetBand);

                        ProductUtils.copyRasterDataNodeProperties(srcBand, targetBand);
                        if(targetBand.getValidPixelExpression() != null) {
                            targetBand.setValidPixelExpression(srcBand.getValidPixelExpression().replace(srcBand.getName(), targetBand.getName()));
                        }

                        if (!isResampling && extent.equals(MASTER_EXTENT) && srcProduct.isCompatibleProduct(targetProduct, 1.0e-3f)) {
                            targetBand.setSourceImage(srcBand.getSourceImage());
                        }

                        // Disable using of no data value in secondary so that valid 0s will be used in the interpolation
                        srcBand.setNoDataValueUsed(false);
                    }
                }
            }

            // copy secondary abstracted metadata
            copySecondaryMetadata();

            StackUtils.saveReferenceProductBandNames(targetProduct,
                                                  referenceProductBands.toArray(new String[0]));
            StackUtils.saveSecondaryProductNames(sourceProduct, targetProduct, referenceProduct, sourceRasterMap);

            updateMetadata();

            // copy GCPs if found to reference band
            final ProductNodeGroup<Placemark> referenceGCPgroup = referenceProduct.getGcpGroup();
            if (referenceGCPgroup.getNodeCount() > 0) {
                OperatorUtils.copyGCPsToTarget(referenceGCPgroup, GCPManager.instance().getGcpGroup(targetProduct.getBandAt(0)),
                                               targetProduct.getSceneGeoCoding());
            }

            if (isResampling) {
                selectedResampling = ResamplingFactory.createResampling(resamplingType);
                if(selectedResampling == null) {
                    throw new OperatorException("Resampling method "+ selectedResampling + " is invalid");
                }
            } else {
                if(initialOffsetMethod == null) {
                    initialOffsetMethod = INITIAL_OFFSET_ORBIT;
                }
                if (initialOffsetMethod.equals(INITIAL_OFFSET_GEOLOCATION)) {
                    computeTargetSecondaryCoordinateOffsets_GCP();
                }
                if (initialOffsetMethod.equals(INITIAL_OFFSET_ORBIT)) {
                    // The ORBIT method uses Orbit.lp2xyz which interprets (line, pixel) as
                    // (azimuth-time, slant-range). For geocoded products (e.g. GSLC), line/pixel
                    // are map coordinates — feeding (W/2, H/2) into the SLC orbit math returns
                    // garbage XYZ and a garbage pixel offset. Route those to a geocoding-based
                    // offset instead. Mixing geometries in one stack is rejected.
                    if (anySourceIsGeocoded()) {
                        if (!allSourcesAreGeocoded()) {
                            throw new OperatorException(
                                "CreateStack cannot mix geocoded (is_terrain_corrected=1) and " +
                                "slant-range SLC products in the same stack — they are in different geometries.");
                        }
                        computeTargetSecondaryCoordinateOffsets_Geocoded();
                    } else {
                        computeTargetSecondaryCoordinateOffsets_Orbits();
                    }
                }
            }

            // set non-elevation areas to no data value for the reference bands using the secondary bands
            if (!extent.equals(MAX_EXTENT)) {
                //DEMAssistedCoregistrationOp.setReferenceValidPixelExpression(targetProduct, true);
            }

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    /**
     * Lazy fallback for execution paths (notably the SNAP desktop / interactive open
     * flow) where the framework never calls {@link #doExecute(ProgressMonitor)} before
     * pulling tiles. Without this, the placeholder GSLCs (bias=0) would stay in the
     * stack and the interferogram comes out as noise. Called from {@link #computeTile}
     * on every tile; the work runs once.
     */
    private synchronized void ensureBiasJobsRan() throws OperatorException {
        if (biasJobsRan) return;
        if (pendingBiasJobs.isEmpty()) {
            biasJobsRan = true;
            return;
        }
        SystemUtils.LOG.info("CreateStack: doExecute was not called by the framework; " +
                "running " + pendingBiasJobs.size() + " bias job(s) lazily from computeTile.");
        doExecute(ProgressMonitor.NULL);
    }

    @Override
    public void doExecute(final ProgressMonitor pm) throws OperatorException {
        if (pendingBiasJobs.isEmpty()) {
            // Nothing to do — either no GSLC inputs or autoCoregisterGSLC=false.
            pm.beginTask("CreateStack", 1);
            pm.worked(1);
            pm.done();
            biasJobsRan = true;
            return;
        }

        pm.beginTask("Auto-coregistering " + pendingBiasJobs.size() + " GSLC slave(s)",
                pendingBiasJobs.size() * 3);
        try {
            for (final PendingBiasJob job : pendingBiasJobs) {
                final Product p = sourceProduct[job.slaveIdx];

                // Step 1 — cross-correlate master vs. slave SLCs.
                pm.setSubTaskName("Cross-correlating '" + job.slaveSlc.getName() + "'");
                double dRangePixels = 0.0;
                double dAzimuthPixels = 0.0;
                if (skipBiasEstimation) {
                    SystemUtils.LOG.info("CreateStack: skipBiasEstimation=true — using bias=0 " +
                            "for slave '" + job.slaveSlc.getName() + "' (geometric coregistration only).");
                    pm.worked(1);
                } else if (reloadedMasterSlcForBias != null) {
                    try {
                        final double[] bias = estimateSlcBias(reloadedMasterSlcForBias, job.slaveSlc,
                                com.bc.ceres.core.SubProgressMonitor.create(pm, 1));
                        dRangePixels = bias[0];
                        dAzimuthPixels = bias[1];
                        SystemUtils.LOG.info(String.format(
                                "CreateStack: bias for slave '%s' — Δrange=%+.4f px, Δazimuth=%+.4f px",
                                job.slaveSlc.getName(), dRangePixels, dAzimuthPixels));
                    } catch (Throwable t) {
                        SystemUtils.LOG.warning("CreateStack: bias estimation failed for '" +
                                job.slaveSlc.getName() + "': " + t.getMessage() +
                                " — keeping placeholder (zero bias).");
                    }
                } else {
                    pm.worked(1);
                }

                if (pm.isCanceled()) throw new OperatorException("Cancelled by user.");

                // Step 2 — rebuild the slave GSLC with the bias applied.
                if (dRangePixels != 0.0 || dAzimuthPixels != 0.0) {
                    pm.setSubTaskName("Re-geocoding '" + job.slaveSlc.getName() + "' with bias");
                    try {
                        final java.util.Map<String, Object> params = new HashMap<>();
                        params.put("outputFlattened", readMasterFlattenedState(referenceProduct));
                        params.put("alignToStandardGrid", true);
                        params.put("standardGridOriginX", 0.0);
                        params.put("standardGridOriginY", 0.0);
                        params.put("rangeOffsetPixels", dRangePixels);
                        params.put("azimuthOffsetPixels", dAzimuthPixels);
                        applyMasterGridLockParams(params, referenceProduct);
                        final Product corrected = createOperatorTargetProduct(
                                "GSLC-Terrain-Correction", params, job.slaveSlc);

                        // Step 3 — swap source band references so computeTile reads from
                        // the corrected GSLC instead of the placeholder.
                        swapSlaveBands(job.placeholderSlaveGslc, corrected);
                        sourceProduct[job.slaveIdx] = corrected;
                        job.placeholderSlaveGslc.dispose();
                    } catch (Throwable t) {
                        SystemUtils.LOG.warning("CreateStack: failed to rebuild GSLC for '" +
                                job.slaveSlc.getName() + "' with bias: " + t.getMessage() +
                                " — keeping placeholder.");
                    }
                }
                pm.worked(2);

                if (job.disposeSlaveSlcAfter) {
                    job.slaveSlc.dispose();
                }
            }

            if (reloadedMasterSlcForBias != null) {
                reloadedMasterSlcForBias.dispose();
                reloadedMasterSlcForBias = null;
            }
            pendingBiasJobs.clear();
            biasJobsRan = true;
        } finally {
            pm.done();
        }
    }

    /**
     * After rebuilding a slave's GSLC with the correct bias, update every entry in
     * {@link #sourceRasterMap} that pointed at the placeholder's bands to point at
     * the same-named band of the new (bias-corrected) product. The target bands in
     * {@code targetProduct} stay put; only their source-band mapping moves.
     */
    private void swapSlaveBands(final Product oldProduct, final Product newProduct) {
        final java.util.Map<Band, Band> updates = new HashMap<>();
        for (final java.util.Map.Entry<Band, Band> entry : sourceRasterMap.entrySet()) {
            final Band sourceBand = entry.getValue();
            if (sourceBand.getProduct() == oldProduct) {
                final Band newBand = newProduct.getBand(sourceBand.getName());
                if (newBand != null) {
                    updates.put(entry.getKey(), newBand);
                }
            }
        }
        sourceRasterMap.putAll(updates);
        // Recompute the integer offset for the corrected product against the master target
        // grid. The bias correction can shift the slave's projected footprint slightly,
        // so the placeholder's offset is not guaranteed to match the corrected one.
        // Without recomputing, computeTile gets a null offset and throws NPE on offset[0].
        try {
            final GeoCoding tgtGC = targetProduct.getSceneGeoCoding();
            if (tgtGC != null && newProduct.getSceneGeoCoding() != null) {
                final int tw = targetProduct.getSceneRasterWidth();
                final int th = targetProduct.getSceneRasterHeight();
                final PixelPos refAnchorPP = new PixelPos(tw / 2.0, th / 2.0);
                final GeoPos anchorGP = new GeoPos();
                tgtGC.getGeoPos(refAnchorPP, anchorGP);
                final PixelPos secPP = new PixelPos();
                newProduct.getSceneGeoCoding().getPixelPos(anchorGP, secPP);
                if (secPP.isValid()) {
                    final int offsetX = (int) Math.floor(secPP.x - refAnchorPP.x + 0.5);
                    final int offsetY = (int) Math.floor(secPP.y - refAnchorPP.y + 0.5);
                    secondaryOffsetMap.put(newProduct, new int[]{offsetX, offsetY});
                    secondaryOffsetMap.remove(oldProduct);
                    return;
                }
            }
        } catch (Throwable t) {
            SystemUtils.LOG.warning("CreateStack: offset recompute failed for bias-corrected slave (" +
                    t.getMessage() + "); falling back to placeholder's offset.");
        }
        // Fallback: carry placeholder's offset (same grid in nearly all cases, so close
        // enough to ship).
        final int[] offset = secondaryOffsetMap.get(oldProduct);
        if (offset != null) {
            secondaryOffsetMap.put(newProduct, offset);
        } else {
            SystemUtils.LOG.warning("CreateStack: no offset found for placeholder '" +
                    oldProduct.getName() + "'; downstream tile reads may fail.");
        }
    }

    private String[] getReferenceBands() {
        String[] masterBandNames = new String[] {};
        final Product defaultProd = sourceProduct[0];
        if (defaultProd != null) {
            int index = 0;
            for(Band band : defaultProd.getBands()) {
                if (band.getUnit() != null && band.getUnit().equals(Unit.REAL)) {
                    masterBandNames = new String[]{band.getName(),
                            defaultProd.getBandAt(index + 1).getName()};
                    break;
                }
                ++index;
            }
            if(masterBandNames.length == 0) {
                masterBandNames = new String[]{defaultProd.getBandAt(0).getName()};
            }
        }
        return masterBandNames;
    }

    private void updateMetadata() {
        final MetadataElement abstractedMetadata = AbstractMetadata.getAbstractedMetadata(targetProduct);
        if(abstractedMetadata != null) {
            abstractedMetadata.setAttributeInt("collocated_stack", 1);
        }

        final MetadataElement inputElem = ProductInformation.getInputProducts(targetProduct);

        getBaselines(sourceProduct, targetProduct);

        for (Product srcProduct : sourceProduct) {
            if (srcProduct == referenceProduct)
                continue;

            final MetadataElement secInputElem = ProductInformation.getInputProducts(srcProduct);
            final MetadataAttribute[] secInputProductAttrbList = secInputElem.getAttributes();
            for (MetadataAttribute attrib : secInputProductAttrbList) {
                final MetadataAttribute inputAttrb = AbstractMetadata.addAbstractedAttribute(inputElem, "InputProduct", ProductData.TYPE_ASCII, "", "");
                inputAttrb.getData().setElems(attrib.getData().getElemString());
            }
        }

        if (isBiomassL1c()) {
            MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(targetProduct);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.coregistered_stack, 1);
        }
    }

    private boolean isBiomassL1c() {
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(targetProduct);
        final MetadataElement origProdRoot = AbstractMetadata.getOriginalProductMetadata(targetProduct);
        final String mission = absRoot.getAttributeString(AbstractMetadata.MISSION);
        return mission.toLowerCase().contains("biomass") && (origProdRoot.getElement("annotation_coregistered") != null);
    }

    public static void getBaselines(final Product[] sourceProduct, final Product targetProduct) {
        try {
            final MetadataElement abstractedMetadata = AbstractMetadata.getAbstractedMetadata(targetProduct);
            final MetadataElement baselinesElem = getBaselinesElem(abstractedMetadata);

            final InSARStackOverview.IfgStack[] stackOverview = InSARStackOverview.calculateInSAROverview(sourceProduct);

            for(InSARStackOverview.IfgStack stack : stackOverview) {
                final InSARStackOverview.IfgPair[] secondaryList = stack.getMasterSlave();
                //System.out.println("======");
                //System.out.println("Ref_" + StackUtils.createBandTimeStamp(
                //        secondary[0].getMasterMetadata().getAbstractedMetadata().getProduct()).substring(1));

                final MetadataElement refElem = new MetadataElement("Ref_" + StackUtils.createBandTimeStamp(
                        secondaryList[0].getMasterMetadata().getAbstractedMetadata().getProduct()).substring(1));
                baselinesElem.addElement(refElem);

                for (InSARStackOverview.IfgPair secondary : secondaryList) {
                    //System.out.println("Secondary_" + StackUtils.createBandTimeStamp(
                    //        secondary.getSlaveMetadata().getAbstractedMetadata().getProduct()).substring(1) +
                    //        " perp baseline: " + secondary.getPerpendicularBaseline() +
                    //        " temp baseline: " + secondary.getTemporalBaseline());

                    final MetadataElement secElem = new MetadataElement("Secondary_" + StackUtils.createBandTimeStamp(
                            secondary.getSlaveMetadata().getAbstractedMetadata().getProduct()).substring(1));
                    refElem.addElement(secElem);

                    addAttrib(secElem, "Perp Baseline", secondary.getPerpendicularBaseline());
                    addAttrib(secElem, "Temp Baseline", secondary.getTemporalBaseline());
                    addAttrib(secElem, "Modelled Coherence", secondary.getCoherence());
                    addAttrib(secElem, "Height of Ambiguity", secondary.getHeightAmb());
                    addAttrib(secElem, "Doppler Difference", secondary.getDopplerDifference());
                }
                //System.out.println();
            }

        } catch (Error | Exception e) {
            // only log the warning and continue
            SystemUtils.LOG.warning("Unable to calculate baselines. " + e.getMessage());
        }
    }

    private static void addAttrib(final MetadataElement elem, final String tag, final double value) {
        final MetadataAttribute attrib = new MetadataAttribute(tag, ProductData.TYPE_FLOAT64);
        attrib.getData().setElemDouble(value);
        elem.addAttribute(attrib);
    }

    private static MetadataElement getBaselinesElem(final MetadataElement abstractedMetadata) {
        MetadataElement baselinesElem = abstractedMetadata.getElement("Baselines");
        if (baselinesElem == null) {
            baselinesElem = new MetadataElement("Baselines");
            abstractedMetadata.addElement(baselinesElem);
        }
        return baselinesElem;
    }

    private void copySecondaryMetadata() {
        final MetadataElement targetSecondaryMetadataRoot = AbstractMetadata.getSecondaryMetadata(targetProduct.getMetadataRoot());
        for (Product prod : sourceProduct) {
            if (prod != referenceProduct) {
                final MetadataElement secAbsMetadata = AbstractMetadata.getAbstractedMetadata(prod);
                if (secAbsMetadata != null) {
                    final String timeStamp = StackUtils.createBandTimeStamp(prod);
                    final MetadataElement targetSecondaryMetadata = new MetadataElement(prod.getName() + timeStamp);
                    targetSecondaryMetadataRoot.addElement(targetSecondaryMetadata);
                    ProductUtils.copyMetadata(secAbsMetadata, targetSecondaryMetadata);
                }
            }
        }
    }

    private Product getReferenceProduct(final String name) {
        final String referenceName = getProductName(name);
        for (Product prod : sourceProduct) {
            if (prod.getName().equals(referenceName)) {
                return prod;
            }
        }
        return null;
    }

    private Band[] getSecondaryBands() throws OperatorException {
        final List<Band> bandList = new ArrayList<>(5);

        // add reference band
        if (referenceProduct == null) {
            throw new OperatorException("referenceProduct is null");
        }
        if (masterBandNames.length > 2) {
            throw new OperatorException("Reference band should be one real band or a real and imaginary band");
        }
        referenceBands[0] = referenceProduct.getBand(getBandName(masterBandNames[0]));
        if (!appendToReference)
            bandList.add(referenceBands[0]);

        final String unit = referenceBands[0].getUnit();
        if (unit != null) {
            if (unit.contains(Unit.PHASE)) {
                throw new OperatorException("Phase band should not be selected for co-registration");
            } else if (unit.contains(Unit.IMAGINARY)) {
                throw new OperatorException("Real and imaginary reference bands should be selected in pairs");
            } else if (unit.contains(Unit.REAL)) {
                if (masterBandNames.length < 2) {
                    if (!contains(masterBandNames, slaveBandNames[0])) {
                        throw new OperatorException("Real and imaginary reference bands should be selected in pairs");
                    } else {
                        final int iBandIdx = referenceProduct.getBandIndex(getBandName(masterBandNames[0]));
                        referenceBands[1] = referenceProduct.getBandAt(iBandIdx + 1);
                        if (!referenceBands[1].getUnit().equals(Unit.IMAGINARY))
                            throw new OperatorException("For complex products select a real and an imaginary band");
                        if (!appendToReference)
                            bandList.add(referenceBands[1]);
                    }
                } else {
                    final Product prod = getReferenceProduct(masterBandNames[1]);
                    if (prod != referenceProduct) {
                        //throw new OperatorException("Please select reference bands from the same product");
                    }
                    referenceBands[1] = referenceProduct.getBand(getBandName(masterBandNames[1]));
                    if (!referenceBands[1].getUnit().equals(Unit.IMAGINARY))
                        throw new OperatorException("For complex products select a real and an imaginary band");
                    if (!appendToReference)
                        bandList.add(referenceBands[1]);
                }
            }
        }

        // add secondary bands
        if (slaveBandNames == null || slaveBandNames.length == 0 || contains(masterBandNames, slaveBandNames[0])) {
            for (Product secProduct : sourceProduct) {
                for (Band band : secProduct.getBands()) {
                    String bandUnit = band.getUnit();
                    if (bandUnit != null && bandUnit.equals(Unit.PHASE))
                        continue;
                    if (band instanceof VirtualBand && !(bandUnit != null && (bandUnit.equals(Unit.REAL) || bandUnit.equals(Unit.IMAGINARY))))
                        continue;
                    if (secProduct == referenceProduct && (band == referenceBands[0] || band == referenceBands[1] || appendToReference))
                        continue;

                    if(bandUnit == null) {
                        bandList.add(band);
                    } else {
                        for (Band refBand : referenceBands) {
                            if(refBand != null && bandUnit.equals(refBand.getUnit())) {
                                bandList.add(band);
                                break;
                            }
                        }
                    }
                }
            }
        } else {

            for (int i = 0; i < slaveBandNames.length; i++) {
                final String name = slaveBandNames[i];
                if (contains(masterBandNames, name)) {
                    throw new OperatorException("Please do not select the same band as reference and secondary");
                }
                final String bandName = getBandName(name);
                final String productName = getProductName(name);

                final Product prod = getProduct(productName, bandName);
                if (prod == null) continue;

                final Band band = prod.getBand(bandName);
                final String bandUnit = band.getUnit();
                if (bandUnit != null) {
                    if (bandUnit.contains(Unit.PHASE)) {
                        throw new OperatorException("Phase band should not be selected for co-registration");
                    } else if (bandUnit.contains(Unit.REAL) || bandUnit.contains(Unit.IMAGINARY)) {
                        if (slaveBandNames.length < 2) {
                            throw new OperatorException("Real and imaginary secondary bands should be selected in pairs");
                        }
                        final String nextBandName = getBandName(slaveBandNames[i + 1]);
                        final String nextBandProdName = getProductName(slaveBandNames[i + 1]);
                        if (!nextBandProdName.contains(productName)) {
                            throw new OperatorException("Real and imaginary secondary bands should be selected from the same product in pairs");
                        }
                        final Band nextBand = prod.getBand(nextBandName);
                        if ((bandUnit.contains(Unit.REAL) && !nextBand.getUnit().contains(Unit.IMAGINARY) ||
                                (bandUnit.contains(Unit.IMAGINARY) && !nextBand.getUnit().contains(Unit.REAL)))) {
                            throw new OperatorException("Real and imaginary secondary bands should be selected in pairs");
                        }
                        bandList.add(band);
                        bandList.add(nextBand);
                        i++;
                    } else {
                        bandList.add(band);
                    }
                } else {
                    bandList.add(band);
                }
            }
        }
        return bandList.toArray(new Band[0]);
    }

    private Product getProduct(final String productName, final String bandName) {
        for (Product prod : sourceProduct) {
            if (prod.getName().equals(productName)) {
                if (prod.getBand(bandName) != null)
                    return prod;
            }
        }
        return null;
    }

    private static boolean contains(final String[] nameList, final String name) {
        for (String nameInList : nameList) {
            if (name.equals(nameInList))
                return true;
        }
        return false;
    }

    private static String getBandName(final String name) {
        if (name.contains("::"))
            return name.substring(0, name.indexOf("::"));
        return name;
    }

    private String getProductName(final String name) {
        if (name.contains("::"))
            return name.substring(name.indexOf("::") + 2);
        return sourceProduct[0].getName();
    }

    /**
     * Minimum extents consists of the overlapping area
     */
    private void determineMinExtents() {

        Geometry tgtGeometry = FeatureUtils.createGeoBoundaryPolygon(referenceProduct);

        for (final Product secProd : sourceProduct) {
            if (secProd == referenceProduct) continue;

            final Geometry secGeometry = FeatureUtils.createGeoBoundaryPolygon(secProd);
            tgtGeometry = tgtGeometry.intersection(secGeometry);
        }

        final GeoCoding refGeoCoding = referenceProduct.getSceneGeoCoding();
        final PixelPos pixPos = new PixelPos();
        final GeoPos geoPos = new GeoPos();
        final double refWidth = referenceProduct.getSceneRasterWidth();
        final double refHeight = referenceProduct.getSceneRasterHeight();

        double maxX = 0, maxY = 0;
        double minX = refWidth;
        double minY = refHeight;
        for (Coordinate c : tgtGeometry.getCoordinates()) {
            //System.out.println("geo "+c.x +", "+ c.y);
            geoPos.setLocation(c.y, c.x);
            refGeoCoding.getPixelPos(geoPos, pixPos);
            //System.out.println("pix "+pixPos.x +", "+ pixPos.y);
            if (pixPos.isValid() && pixPos.x != -1 && pixPos.y != -1) {
                if (pixPos.x < minX) {
                    minX = Math.max(0, pixPos.x);
                }
                if (pixPos.y < minY) {
                    minY = Math.max(0, pixPos.y);
                }
                if (pixPos.x > maxX) {
                    maxX = Math.min(refWidth, pixPos.x);
                }
                if (pixPos.y > maxY) {
                    maxY = Math.min(refHeight, pixPos.y);
                }
            }
        }

        final ProductSubsetBuilder subsetReader = new ProductSubsetBuilder();
        final ProductSubsetDef subsetDef = new ProductSubsetDef();
        subsetDef.addNodeNames(referenceProduct.getTiePointGridNames());

        subsetDef.setSubsetRegion(new PixelSubsetRegion((int) minX, (int) minY, (int) (maxX - minX), (int) (maxY - minY), 0));
        subsetDef.setSubSampling(1, 1);
        subsetDef.setIgnoreMetadata(false);

        try {
            targetProduct = subsetReader.readProductNodes(referenceProduct, subsetDef);
            final Band[] bands = targetProduct.getBands();
            for (Band b : bands) {
                targetProduct.removeBand(b);
            }
        } catch (Throwable t) {
            throw new OperatorException(t);
        }
    }

    /**
     * Maximum extents consists of the overall area
     */
    private void determineMaxExtents() {

        final OperatorUtils.SceneProperties scnProp = new OperatorUtils.SceneProperties();
        OperatorUtils.computeImageGeoBoundary(sourceProduct, scnProp);

        final Resolution resolution = new Resolution(referenceProduct);
        final double rangeSpacing = resolution.getResX();
        final double azimuthSpacing = resolution.getResY();
        double pixelSize = Math.min(rangeSpacing, azimuthSpacing);

        OperatorUtils.getSceneDimensions(pixelSize, scnProp);

        int sceneWidth = scnProp.sceneWidth;
        int sceneHeight = scnProp.sceneHeight;
        final double ratio = sceneWidth / (double)sceneHeight;
        long dim = (long) sceneWidth * (long) sceneHeight;
        while (sceneWidth > 0 && sceneHeight > 0 && dim > Integer.MAX_VALUE) {
            sceneWidth -= 1000;
            sceneHeight = (int)(sceneWidth / ratio);
            dim = (long) sceneWidth * (long) sceneHeight;
        }

        final Product tempProduct = new Product(referenceProduct.getName(),
                                    referenceProduct.getProductType(),
                                    sceneWidth, sceneHeight);

        ProductUtils.copyProductNodes(referenceProduct, tempProduct);
        OperatorUtils.addGeoCoding(tempProduct, scnProp);

        try {
            final double pixelSpacingInDegree = SARGeocoding.getPixelSpacingInDegree(pixelSize);

            final CRSGeoCodingHandler crsHandler = new CRSGeoCodingHandler(tempProduct, "WGS84(DD)",
                    pixelSpacingInDegree, pixelSize,false, 0, 0);

            targetProduct = new Product(referenceProduct.getName(),
                    referenceProduct.getProductType(), crsHandler.getTargetWidth(), crsHandler.getTargetHeight());

            ProductUtils.copyProductNodes(referenceProduct, targetProduct);

            targetProduct.setSceneGeoCoding(crsHandler.getCrsGeoCoding());
        } catch (Exception e) {
            throw new OperatorException(e);
        }
    }

    private void computeTargetSecondaryCoordinateOffsets_GCP() {

        final GeoCoding targGeoCoding = targetProduct.getSceneGeoCoding();
        final int targImageWidth = targetProduct.getSceneRasterWidth();
        final int targImageHeight = targetProduct.getSceneRasterHeight();

        final Geometry tgtGeometry = FeatureUtils.createGeoBoundaryPolygon(targetProduct);

        final PixelPos secPixelPos = new PixelPos();
        final PixelPos tgtPixelPos = new PixelPos();
        final GeoPos secGeoPos = new GeoPos();

        for (final Product secProd : sourceProduct) {
            if (secProd == referenceProduct && extent.equals(MASTER_EXTENT)) {
                secondaryOffsetMap.put(secProd, new int[]{0, 0});
                continue;
            }

            final GeoCoding secGeoCoding = secProd.getSceneGeoCoding();
            final int secImageWidth = secProd.getSceneRasterWidth();
            final int secImageHeight = secProd.getSceneRasterHeight();

            boolean foundOverlapPoint = false;

            // test corners
            secGeoCoding.getGeoPos(new PixelPos(10, 10), secGeoPos);
            if (false) {// (pixelPosValid(targGeoCoding, secGeoPos, tgtPixelPos, targImageWidth, targImageHeight)) {

                addOffset(secProd, 0 - (int) tgtPixelPos.x, 0 - (int) tgtPixelPos.y);
                foundOverlapPoint = true;
            }
            if (false) {//!foundOverlapPoint) {
                secGeoCoding.getGeoPos(new PixelPos(secImageWidth - 10, secImageHeight - 10), secGeoPos);
                if (pixelPosValid(targGeoCoding, secGeoPos, tgtPixelPos, targImageWidth, targImageHeight)) {

                    addOffset(secProd, 0 - secImageWidth - (int) tgtPixelPos.x, secImageHeight - (int) tgtPixelPos.y);
                    foundOverlapPoint = true;
                }
            }

            if (!foundOverlapPoint) {
                final Geometry secGeometry = FeatureUtils.createGeoBoundaryPolygon(secProd);
                final Geometry intersect = tgtGeometry.intersection(secGeometry);

                for (Coordinate c : intersect.getCoordinates()) {
                    getPixelPos(c.y, c.x, secGeoCoding, secPixelPos);

                    if (secPixelPos.isValid() && secPixelPos.x >= 0 && secPixelPos.x < secImageWidth &&
                            secPixelPos.y >= 0 && secPixelPos.y < secImageHeight) {

                        getPixelPos(c.y, c.x, targGeoCoding, tgtPixelPos);
                        if (tgtPixelPos.isValid() && tgtPixelPos.x >= 0 && tgtPixelPos.x < targImageWidth &&
                                tgtPixelPos.y >= 0 && tgtPixelPos.y < targImageHeight) {

                            addOffset(secProd, (int) secPixelPos.x - (int) tgtPixelPos.x, (int) secPixelPos.y - (int) tgtPixelPos.y);
                            foundOverlapPoint = true;
                            break;
                        }
                    }
                }
            }

            //if(foundOverlapPoint) {
            //    final int[] offset = secondaryOffsetMap.get(secProd);
            //    System.out.println("offset x="+offset[0]+" y="+offset[1]);
            //}

            if (!foundOverlapPoint) {
                throw new OperatorException("Product " + secProd.getName() + " has no overlap with reference product.");
            }
        }
    }

    private void computeTargetSecondaryCoordinateOffsets_Orbits() throws Exception {
        try {
            // Note: This procedure will always compute some overlap

            // Similar as for GCPs but for every GCP use orbit information
            if (!AbstractMetadata.hasAbstractedMetadata(targetProduct)) {
                throw new Exception("Orbit offset method is not support for product " + targetProduct.getName());
            }
            MetadataElement root = AbstractMetadata.getAbstractedMetadata(targetProduct);

            final int orbitDegree = 3;

            SLCImage metaReference = new SLCImage(root, targetProduct);
            Orbit orbitReference = new Orbit(root, orbitDegree);
            SLCImage metaSecondary;
            Orbit orbitSecondary;

            // Reference point in reference radar geometry
            Point tgtLP = metaReference.getApproxRadarCentreOriginal();

            MetadataElement orbitOffsets = new MetadataElement("Orbit_Offsets");
            MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(targetProduct);
            absRoot.addElement(orbitOffsets);
            for (final Product secProd : sourceProduct) {

                if (secProd == referenceProduct) {
                    // if reference product put 0-es for offset
                    secondaryOffsetMap.put(secProd, new int[]{0, 0});
                    continue;
                }

                // Secondary metadata
                if (!AbstractMetadata.hasAbstractedMetadata(secProd)) {
                    throw new Exception("Orbit offset method is not support for product " + secProd.getName());
                }
                root = AbstractMetadata.getAbstractedMetadata(secProd);
                metaSecondary = new SLCImage(root, secProd);
                orbitSecondary = new Orbit(root, orbitDegree);

                // (lp_reference) & (reference_orbit)-> (xyz_reference) & (secondary_orbit)-> (lp_secondary)
                Point tgtXYZ = orbitReference.lp2xyz(tgtLP, metaReference);
                Point secLP = orbitSecondary.xyz2lp(tgtXYZ, metaSecondary);

                // Offset: secondary minus reference
                Point offsetLP = secLP.min(tgtLP);

                int offsetX = (int) Math.floor(offsetLP.x + .5);
                int offsetY = (int) Math.floor(offsetLP.y + .5);

                // Add to metadata
                String timeStamp = StackUtils.createBandTimeStamp(secProd).substring(1);
                MetadataElement bandElem = null;
                for (String bandName : targetProduct.getBandNames()){
                    String bandTimeStamp = bandName.split("_")[bandName.split("_").length - 1];
                    if (bandTimeStamp.equals(timeStamp)){
                        bandElem = new MetadataElement("init_offsets" + StackUtils.getBandSuffix(bandName));
                        bandElem.setAttributeInt("init_offset_X", offsetX);
                        bandElem.setAttributeInt("init_offset_Y", offsetY);
                    }
                }
                orbitOffsets.addElement(bandElem);

                addOffset(secProd, offsetX, offsetY);

            }
        } catch (Exception e) {
            throw new IOException("Orbit offset method is not support for this product: "+e.getMessage());
        }
    }

    /**
     * If the stack contains at least one geocoded product (e.g. a master GSLC) alongside raw
     * slant-range SLCs, auto-promote each SLC by invoking {@code GSLC-Terrain-Correction} on
     * it. The replacement happens in-place on {@code sourceProduct[]} so the rest of
     * {@link #initialize()} sees a homogeneous stack of geocoded products.
     * <p>
     * Grid alignment between master and the auto-geocoded slaves is enforced by
     * {@link #applyMasterGridLockParams}, which extracts master's CRS + pixel size and
     * passes them to the slave's GSLC build. Both products then snap to the same world
     * grid; {@link #computeTargetSecondaryCoordinateOffsets_Geocoded()} resolves the
     * integer-pixel offset between them.
     * <p>
     * The slave inherits master's {@code outputFlattened} state (read via
     * {@link #readMasterFlattenedState}) so the interferogram phase remains coherent
     * regardless of whether master is phase-flattened or not.
     * <p>
     * This pass runs from {@link #initialize()}; pixel data is not computed here. For
     * each slave that needs auto-coregistration, the input is replaced with a
     * <em>placeholder</em> GSLC built with {@code bias=0} (the operator's
     * own initialize() sets up the target schema only) and a {@link PendingBiasJob}
     * is queued to run the heavy cross-correlation in
     * {@link #doExecute(ProgressMonitor)}.
     */
    private void maybeAutoGeocodeAgainstReference() throws OperatorException {
        if (!autoCoregisterGSLC) return;

        // Find the first geocoded product (presumed master GSLC).
        Product masterGslc = null;
        int masterIdx = -1;
        for (int i = 0; i < sourceProduct.length; i++) {
            if (isGeocoded(sourceProduct[i])) {
                masterGslc = sourceProduct[i];
                masterIdx = i;
                break;
            }
        }
        if (masterGslc == null) return;

        // The master must be complex (carrier-preserving) for InSAR. A regular Range-Doppler
        // Terrain-Corrected product (RangeDopplerGeocodingOp output) is amplitude-only and
        // can't be used as InSAR master. Detect by looking for at least one i/q band.
        boolean masterIsComplex = false;
        for (final Band b : masterGslc.getBands()) {
            final String u = b.getUnit();
            if (u != null && (u.equals(Unit.REAL) || u.equals(Unit.IMAGINARY))) {
                masterIsComplex = true;
                break;
            }
        }
        if (!masterIsComplex) {
            // No complex bands → almost certainly amplitude-only Range-Doppler TC, not GSLC.
            // Skip auto-coregister silently rather than throw — the user may legitimately be
            // stacking amplitude products. Downstream geometry-mixing throw will guide them
            // if the slaves don't match.
            SystemUtils.LOG.warning("CreateStack: master '" + masterGslc.getName() + "' is map-projected " +
                    "but has no complex i/q bands; skipping GSLC auto-coregister (this looks like " +
                    "an amplitude-only TC product, not a GSLC).");
            return;
        }

        // If the master GSLC carries a stamp pointing back to its source SLC on disk,
        // load it once for the (later) cross-correlation pass. ProductIO.readProduct
        // opens the file lazily — no pixels are read here.
        reloadedMasterSlcForBias = tryReloadMasterSlc(masterGslc);
        if (reloadedMasterSlcForBias == null) {
            SystemUtils.LOG.warning("CreateStack: could not reload master SLC for bias estimation. " +
                    "Slaves will be auto-geocoded without bias correction (pure geometric coregistration).");
        } else {
            SystemUtils.LOG.info("CreateStack: master SLC reloaded from '" +
                    reloadedMasterSlcForBias.getFileLocation() +
                    "' — will auto-estimate per-slave bias during doExecute.");
        }

        for (int i = 0; i < sourceProduct.length; i++) {
            if (i == masterIdx) continue;
            final Product p = sourceProduct[i];

            // Resolve the slave's slant-range SLC source.
            final Product slaveSlc;
            final boolean slaveIsGslc = isGeocoded(p);
            if (slaveIsGslc) {
                slaveSlc = tryReloadSlcFromStamp(p);
                if (slaveSlc == null) {
                    SystemUtils.LOG.warning("CreateStack: slave '" + p.getName() + "' is a GSLC but " +
                            "its source SLC path is missing/unreadable — cannot estimate bias, " +
                            "stack alignment will be sub-pixel-off.");
                    continue;
                }
            } else {
                slaveSlc = p;
            }

            // Build a placeholder GSLC with bias=0. This invokes only the operator's
            // initialize() (sets up the target product schema, allocates bands) — no
            // pixels are computed. It gives CreateStack's downstream init code a
            // concrete product with the correct band layout so the target stack
            // schema can be built. We'll swap it for the bias-corrected GSLC during
            // doExecute and update sourceRasterMap accordingly.
            // CRITICAL: lock the slave grid to the master's pixel size + CRS so the
            // integer-pixel offsets in computeTargetSecondaryCoordinateOffsets_Geocoded
            // are exactly correct. Without this, the slave geocodes to its own natural
            // pixel size which may be sub-pixel different from the master's, producing
            // a fractional drift across the scene that destroys interferogram coherence.
            final Product placeholder;
            try {
                final java.util.Map<String, Object> params = new HashMap<>();
                params.put("outputFlattened", readMasterFlattenedState(masterGslc));
                params.put("alignToStandardGrid", true);
                params.put("standardGridOriginX", 0.0);
                params.put("standardGridOriginY", 0.0);
                params.put("rangeOffsetPixels", 0.0);
                params.put("azimuthOffsetPixels", 0.0);
                applyMasterGridLockParams(params, masterGslc);
                placeholder = createOperatorTargetProduct("GSLC-Terrain-Correction", params, slaveSlc);
            } catch (Throwable t) {
                throw new OperatorException(
                        "Auto-geocoding placeholder build failed for '" + p.getName() +
                                "': " + t.getMessage(), t);
            }
            sourceProduct[i] = placeholder;
            pendingBiasJobs.add(new PendingBiasJob(i, slaveSlc, slaveIsGslc, placeholder));
        }
    }

    /** Same as {@link #tryReloadMasterSlc} but used for slave GSLCs in the GUI workflow. */
    private static Product tryReloadSlcFromStamp(final Product gslcProduct) {
        return tryReloadMasterSlc(gslcProduct);
    }

    /**
     * Try to reload the master SLC from the file path stamped into the master GSLC's
     * metadata by {@code GSLCGeocodingOp}. Returns null if the stamp is missing, the
     * path is unreadable, or the load fails — in which case the caller falls back to
     * no-bias auto-geocoding.
     */
    private static Product tryReloadMasterSlc(final Product masterGslc) {
        final MetadataElement abs = AbstractMetadata.getAbstractedMetadata(masterGslc);
        // Path 1 — explicit stamp from GSLCGeocodingOp.
        if (abs != null) {
            final String pathStr = abs.getAttributeString("gslc_source_slc_path", null);
            if (pathStr != null && !pathStr.isEmpty() &&
                    !pathStr.equals(AbstractMetadata.NO_METADATA_STRING)) {
                final java.io.File f = new java.io.File(pathStr);
                if (f.isFile()) {
                    try {
                        return org.esa.snap.core.dataio.ProductIO.readProduct(f);
                    } catch (java.io.IOException e) {
                        SystemUtils.LOG.warning("CreateStack: failed to reload master SLC from '" + pathStr +
                                "': " + e.getMessage());
                    }
                }
            }
        }
        // Path 2 — name-based fallback. Useful when the master is a SUBSET of a GSLC
        // (subset strips the gslc_source_slc_path stamp) but the source SLC still sits
        // next to the GSLC on disk. Only consider candidates whose name has had the
        // _GSLC suffix stripped (a GSLC-named file is never the source SLC), and never
        // return the master's own file path.
        final java.io.File loc = masterGslc.getFileLocation();
        if (loc != null && loc.getParentFile() != null) {
            String baseName = loc.getName();
            // Strip trailing .dim
            final int dot = baseName.lastIndexOf('.');
            if (dot > 0) baseName = baseName.substring(0, dot);
            final java.util.List<String> candidates = new java.util.ArrayList<>();
            for (final String stripped : stripSubsetAndGslcSuffix(baseName)) {
                candidates.add(stripped + ".dim");
                candidates.add(stripped + ".N1"); // Envisat native
                candidates.add(stripped); // directory-style product
            }
            final String masterAbsPath = loc.getAbsolutePath();
            for (final String cand : candidates) {
                final java.io.File f = new java.io.File(loc.getParentFile(), cand);
                if (!f.exists()) continue;
                if (f.getAbsolutePath().equalsIgnoreCase(masterAbsPath)) {
                    // Sanity: candidate is the master itself → not a valid SLC
                    continue;
                }
                try {
                    SystemUtils.LOG.info("CreateStack: name-based fallback located source SLC '" +
                            f.getAbsolutePath() + "' for master '" + masterGslc.getName() + "'.");
                    return org.esa.snap.core.dataio.ProductIO.readProduct(f);
                } catch (java.io.IOException e) {
                    SystemUtils.LOG.warning("CreateStack: failed to read SLC candidate '" +
                            f.getAbsolutePath() + "': " + e.getMessage());
                }
            }
        }
        return null;
    }

    /**
     * Produce SLC candidate names from a GSLC's base name. Requires the input to have a
     * {@code _GSLC} suffix; only returns names with that suffix stripped (so we can
     * never return the input itself, and never return another GSLC file).
     */
    private static java.util.List<String> stripSubsetAndGslcSuffix(final String baseName) {
        final java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        String cur = baseName;
        // Optionally strip "subset_N_of_" prefix
        final java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^subset(_\\d+)?_of_").matcher(cur);
        if (m.find()) {
            cur = cur.substring(m.end());
        }
        // REQUIRE _GSLC suffix on the (possibly subset-stripped) name; strip it
        if (cur.length() >= 5 && cur.regionMatches(true, cur.length() - 5, "_GSLC", 0, 5)) {
            out.add(cur.substring(0, cur.length() - 5));
        }
        // Also try: directly strip _GSLC without stripping subset prefix (handles cases
        // where someone subsetted post-rename so the subset prefix isn't at position 0)
        if (baseName.length() >= 5 && baseName.regionMatches(true, baseName.length() - 5, "_GSLC", 0, 5)) {
            final String s = baseName.substring(0, baseName.length() - 5);
            if (!s.equals(baseName)) out.add(s);
        }
        return new java.util.ArrayList<>(out);
    }

    /**
     * Read the {@code gslc_output_flattened} metadata stamp from the master GSLC and
     * return its boolean value. The slave GSLC must be built with the SAME flattening
     * state — mixing flattened/non-flattened bands in the stack produces a meaningless
     * interferometric phase (noise). Defaults to {@code false} if the stamp is missing
     * (e.g. master was built by an older operator version).
     */
    private static boolean readMasterFlattenedState(final Product masterGslc) {
        if (masterGslc == null) return false;
        final MetadataElement abs = AbstractMetadata.getAbstractedMetadata(masterGslc);
        if (abs == null) return false;
        final String s = abs.getAttributeString("gslc_output_flattened", null);
        if (s == null) return false;
        return Boolean.parseBoolean(s);
    }

    private static void applyMasterGridLockParams(final java.util.Map<String, Object> params,
                                                  final Product masterGslc) {
        if (masterGslc == null) return;
        final org.esa.snap.core.datamodel.GeoCoding gc = masterGslc.getSceneGeoCoding();
        if (gc == null) return;
        try {
            // Master CRS — written as WKT so GSLCGeocodingOp can re-parse it
            if (gc instanceof org.esa.snap.core.datamodel.CrsGeoCoding) {
                final org.opengis.referencing.crs.CoordinateReferenceSystem crs =
                        ((org.esa.snap.core.datamodel.CrsGeoCoding) gc).getMapCRS();
                if (crs != null) {
                    params.put("mapProjection", crs.toWKT());
                }
            }
            // Pixel size — probe master geocoding at (W/2, H/2) vs (W/2+1, H/2+1).
            // For a CRS in degrees this gives degrees-per-pixel; for a metres CRS, metres.
            final org.esa.snap.core.datamodel.GeoPos g0 = new org.esa.snap.core.datamodel.GeoPos();
            final org.esa.snap.core.datamodel.GeoPos g1 = new org.esa.snap.core.datamodel.GeoPos();
            final double cx = masterGslc.getSceneRasterWidth() / 2.0;
            final double cy = masterGslc.getSceneRasterHeight() / 2.0;
            gc.getGeoPos(new org.esa.snap.core.datamodel.PixelPos(cx, cy), g0);
            gc.getGeoPos(new org.esa.snap.core.datamodel.PixelPos(cx + 1, cy + 1), g1);
            final double dLon = Math.abs(g1.lon - g0.lon);
            final double dLat = Math.abs(g1.lat - g0.lat);
            final double pixelSizeDeg = Math.max(dLon, dLat);
            if (pixelSizeDeg > 0 && Double.isFinite(pixelSizeDeg)) {
                params.put("pixelSpacingInDegree", pixelSizeDeg);
                // Convert to metres for the metre-based param as a safety net (some paths
                // in GSLCGeocodingOp read pixelSpacingInMeter regardless).
                final double latRad = Math.toRadians(g0.lat);
                final double mPerDeg = 111320.0 * Math.cos(latRad);
                final double pixelSizeM = pixelSizeDeg * mPerDeg;
                if (pixelSizeM > 0 && Double.isFinite(pixelSizeM)) {
                    params.put("pixelSpacingInMeter", pixelSizeM);
                }
                SystemUtils.LOG.info("CreateStack: locking slave grid to master — " +
                        "pixelSpacingInDegree=" + pixelSizeDeg + " (≈" + pixelSizeM + " m at lat " + g0.lat + ")");
            }
        } catch (Throwable t) {
            SystemUtils.LOG.warning("CreateStack: master grid-lock setup failed (slave will use its own grid): "
                    + t.getMessage());
        }
    }

    private static Product createOperatorTargetProduct(final String alias,
                                                       final java.util.Map<String, Object> params,
                                                       final Product... sources) {
        final org.esa.snap.core.gpf.OperatorSpi spi =
                org.esa.snap.core.gpf.GPF.getDefaultInstance()
                        .getOperatorSpiRegistry().getOperatorSpi(alias);
        if (spi == null) {
            throw new OperatorException("OperatorSpi not found for alias '" + alias + "'");
        }
        final org.esa.snap.core.gpf.Operator op = spi.createOperator();
        if (sources.length == 1) {
            op.setSourceProduct(sources[0]);
        } else {
            op.setSourceProducts(sources);
        }
        if (params != null) {
            for (final java.util.Map.Entry<String, Object> e : params.entrySet()) {
                op.setParameter(e.getKey(), e.getValue());
            }
        }
        return op.getTargetProduct();
    }

    /**
     * Render a 20-cell Unicode progress bar for a 0-100 percentage. Used in
     * {@code pm.setTaskName(...)} during long sub-tasks so the dialog label visually
     * advances even when the JProgressBar widget can't tick (its 1-unit resolution at
     * graph-node level can't show sub-unit progress).
     */
    /**
     * Pick a representative band of a product to use as a validity probe in
     * {@link #computeTile}. Prefer a complex i/q band (Unit.REAL); fall back to any band.
     * Returns null if the product has no bands. All bands of a SAR product typically
     * share the same valid-pixel footprint, so any single band works as a probe.
     */
    private static final java.util.Map<Product, Band> VALIDITY_PROBE_CACHE = new java.util.WeakHashMap<>();

    private static Band validityProbeBand(final Product product) {
        if (product == null) return null;
        synchronized (VALIDITY_PROBE_CACHE) {
            Band cached = VALIDITY_PROBE_CACHE.get(product);
            if (cached != null) return cached;
            for (final Band b : product.getBands()) {
                final String u = b.getUnit();
                if (u != null && (u.equals(Unit.REAL) || u.equals(Unit.INTENSITY))) {
                    VALIDITY_PROBE_CACHE.put(product, b);
                    return b;
                }
            }
            if (product.getNumBands() > 0) {
                final Band first = product.getBandAt(0);
                VALIDITY_PROBE_CACHE.put(product, first);
                return first;
            }
            return null;
        }
    }


    private static double[] estimateSlcBias(final Product masterSlc, final Product slaveSlc,
                                            final ProgressMonitor pm)
            throws Exception {
        INSIDE_BIAS_ESTIMATION.set(true);
        try {
            // TOPS pairs cannot use the stripmap nested-CreateStack + Cross-Correlation path
            // (cross-correlation on raw TOPS SLCs is invalid, and the nested CreateStack refuses
            // TOPS). Use Back-Geocoding + ESD to get the (range, azimuth) residual instead.
            if (isTopsSlc(masterSlc) && isTopsSlc(slaveSlc)) {
                return estimateTopsBias(masterSlc, slaveSlc, pm);
            }
            return estimateSlcBiasInner(masterSlc, slaveSlc, pm);
        } finally {
            INSIDE_BIAS_ESTIMATION.set(false);
        }
    }

    private static boolean isTopsSlc(final Product p) {
        try {
            final InputProductValidator v = new InputProductValidator(p);
            return v.isTOPSARProduct() && !v.isDebursted();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * TOPS-grade (range, azimuth) residual via SNAP's TOPS coregistration: Back-Geocoding to a
     * coregistered burst stack, then Enhanced-Spectral-Diversity (estimate only, no resampled
     * output) whose {@code Overall_Range_Azimuth_Shift} is the residual to feed GSLC's scalar
     * offset parameters. Invoked through the SPI registry (Back-Geocoding/ESD live in
     * sar-op-sentinel1, which depends on this module, so they can't be imported directly).
     * Falls back to zero bias (warned) when ESD cannot run (e.g. a single burst — no overlaps).
     */
    private static double[] estimateTopsBias(final Product masterSlc, final Product slaveSlc,
                                             final ProgressMonitor pm) {
        try {
            final java.util.Map<String, Object> bgParams = new HashMap<>();
            bgParams.put("demName", "Copernicus 30m Global DEM");
            bgParams.put("resamplingType", "BISINC_5_POINT_INTERPOLATION");
            final Product stack = createOperatorTargetProduct(
                    "Back-Geocoding", bgParams, masterSlc, slaveSlc);

            // ESD estimates the residual in doExecute() (SpectralDiversityOp's doNotWriteTargetBands
            // branch), which getTargetProduct() alone does NOT trigger. Instantiate the operator and
            // call execute() so the Overall_Range_Azimuth_Shift metadata is populated.
            final org.esa.snap.core.gpf.OperatorSpi esdSpi =
                    org.esa.snap.core.gpf.GPF.getDefaultInstance()
                            .getOperatorSpiRegistry().getOperatorSpi("Enhanced-Spectral-Diversity");
            if (esdSpi == null) {
                throw new OperatorException("OperatorSpi not found for 'Enhanced-Spectral-Diversity'");
            }
            final org.esa.snap.core.gpf.Operator esdOp = esdSpi.createOperator();
            esdOp.setSourceProduct(stack);
            esdOp.setParameter("doNotWriteTargetBands", true);
            esdOp.execute(com.bc.ceres.core.ProgressMonitor.NULL);
            final Product esd = esdOp.getTargetProduct();

            final double[] off = esdShiftToGslcOffset(readEsdOverallShift(esd));
            SystemUtils.LOG.info(String.format(
                    "CreateStack TOPS bias for slave '%s' — Δrange=%+.4f px, Δazimuth=%+.4f px (ESD)",
                    slaveSlc.getName(), off[0], off[1]));
            return off;
        } catch (Throwable t) {
            SystemUtils.LOG.warning("CreateStack: TOPS bias estimation failed for '" +
                    slaveSlc.getName() + "': " + t.getMessage() +
                    " — using zero bias (geometric coregistration only).");
            return new double[]{0.0, 0.0};
        }
    }

    /**
     * Read the ESD overall (range, azimuth) residual in pixels from an
     * Enhanced-Spectral-Diversity output's abstracted metadata:
     * {@code "ESD Measurement" -> <first pair> -> "Overall_Range_Azimuth_Shift" -> <first subswath>}
     * attributes {@code rangeShift} / {@code azimuthShift}. Throws if the element is absent.
     */
    static double[] readEsdOverallShift(final Product esd) {
        final MetadataElement abs = AbstractMetadata.getAbstractedMetadata(esd);
        final MetadataElement esdElem = abs == null ? null : abs.getElement("ESD Measurement");
        if (esdElem == null || esdElem.getNumElements() == 0) {
            throw new OperatorException("ESD output has no 'ESD Measurement' metadata.");
        }
        final MetadataElement pair = esdElem.getElementAt(0);
        final MetadataElement overall = pair.getElement("Overall_Range_Azimuth_Shift");
        if (overall == null || overall.getNumElements() == 0) {
            throw new OperatorException("ESD output has no 'Overall_Range_Azimuth_Shift' metadata.");
        }
        final MetadataElement sw = overall.getElementAt(0);
        return new double[]{ sw.getAttributeDouble("rangeShift", 0.0),
                             sw.getAttributeDouble("azimuthShift", 0.0) };
    }

    /**
     * Map an ESD (range, azimuth) residual to GSLC rangeOffsetPixels/azimuthOffsetPixels.
     * First increment: identity. Centralised so the sign is changed in one place if the
     * integration coherence A/B shows the bias reduces (rather than improves) coherence.
     */
    static double[] esdShiftToGslcOffset(final double[] esdRgAz) {
        return new double[]{ esdRgAz[0], esdRgAz[1] };
    }

    private static double[] estimateSlcBiasInner(final Product masterSlc, final Product slaveSlc,
                                                 final ProgressMonitor pm)
            throws Exception {
        final long t0 = System.currentTimeMillis();
        SystemUtils.LOG.fine("CreateStack: estimating bias for slave '" + slaveSlc.getName() +
                "' against master '" + masterSlc.getName() + "'");
        // Instantiate operators directly rather than via GPF.createProduct() — the SNAP
        // desktop registers a SnapAppGPFOperatorExecutor that touches the EDT during
        // createProduct, which deadlocks/throws when called from doExecute's worker thread.
        // Direct instantiation skips that wrapper.
        final CreateStackOp stackOp = new CreateStackOp();
        stackOp.setSourceProducts(masterSlc, slaveSlc);
        stackOp.setParameter("extent", MASTER_EXTENT);
        stackOp.setParameter("initialOffsetMethod", INITIAL_OFFSET_ORBIT);
        stackOp.setParameter("resamplingType", "NONE");
        stackOp.setParameter("autoCoregisterGSLC", false); // nested stack is SLC-on-SLC; nothing to coregister
        final Product stack = stackOp.getTargetProduct();

        final CrossCorrelationOp ccOp = new CrossCorrelationOp();
        ccOp.setSourceProduct(stack);
        ccOp.setParameter("numGCPtoGenerate", 200);
        ccOp.setParameter("coarseRegistrationWindowWidth", "64");
        ccOp.setParameter("coarseRegistrationWindowHeight", "64");
        ccOp.setParameter("applyFineRegistration", true);
        ccOp.setParameter("inSAROptimized", true);
        ccOp.setParameter("coherenceThreshold", 0.6);
        final Product cc = ccOp.getTargetProduct();

        Band masterBand = null;
        Band slaveBand  = null;
        for (final Band b : cc.getBands()) {
            if (b.getUnit() != null && b.getUnit().equals(Unit.REAL)) {
                if (masterBand == null) masterBand = b;
                else if (slaveBand == null) { slaveBand = b; break; }
            }
        }
        if (masterBand == null || slaveBand == null) {
            throw new OperatorException("Cross-Correlation output is missing master or slave band.");
        }

        // Force the matching loops to run by reading the slave band — GCPs are populated
        // as a side effect of computeTile.
        final int w = slaveBand.getRasterWidth();
        final int h = slaveBand.getRasterHeight();
        final int step = 256;
        final int nSteps = (h + step - 1) / step;
        // Drive CC's GCP selection by reading the slave band. We DON'T open our own
        // status-bar progress monitor here — CrossCorrelationOp already registers its own
        // ("Cross Correlating <slave>... N%") via StatusProgressMonitor. We still tick
        // the caller's pm so the GraphBuilder dialog's progress bar advances.
        pm.beginTask("Cross-correlating " + slaveSlc.getName(), nSteps);
        try {
            final float[] row = new float[w];
            for (int y = 0; y < h; y += step) {
                if (pm.isCanceled()) {
                    throw new OperatorException("Cancelled by user during cross-correlation.");
                }
                slaveBand.readPixels(0, y, w, 1, row, com.bc.ceres.core.ProgressMonitor.NULL);
                pm.worked(1);
            }
        } finally {
            pm.done();
        }
        SystemUtils.LOG.fine("CreateStack: bias CC scan complete in " +
                (System.currentTimeMillis() - t0) / 1000 + "s.");

        final ProductNodeGroup<org.esa.snap.core.datamodel.Placemark> mGcp =
                GCPManager.instance().getGcpGroup(masterBand);
        final ProductNodeGroup<org.esa.snap.core.datamodel.Placemark> sGcp =
                GCPManager.instance().getGcpGroup(slaveBand);
        final java.util.List<Double> dxs = new java.util.ArrayList<>();
        final java.util.List<Double> dys = new java.util.ArrayList<>();
        for (final org.esa.snap.core.datamodel.Placemark mp
                : mGcp.toArray(new org.esa.snap.core.datamodel.Placemark[0])) {
            final org.esa.snap.core.datamodel.Placemark sp = sGcp.get(mp.getName());
            if (sp == null) continue;
            dxs.add((double) (sp.getPixelPos().x - mp.getPixelPos().x));
            dys.add((double) (sp.getPixelPos().y - mp.getPixelPos().y));
        }
        if (dxs.isEmpty()) {
            throw new OperatorException("Cross-Correlation matched no GCPs between master and slave.");
        }
        java.util.Collections.sort(dxs);
        java.util.Collections.sort(dys);
        final int n = dxs.size();
        final double medDx = (n % 2 == 0) ? 0.5 * (dxs.get(n / 2 - 1) + dxs.get(n / 2)) : dxs.get(n / 2);
        final double medDy = (n % 2 == 0) ? 0.5 * (dys.get(n / 2 - 1) + dys.get(n / 2)) : dys.get(n / 2);
        return new double[]{medDx, medDy};
    }

    /**
     * Whether a product is in a geocoded (map-projected) frame rather than slant-range SLC.
     * The orbit-based offset method ({@link #computeTargetSecondaryCoordinateOffsets_Orbits()})
     * assumes slant-range geometry; for geocoded products we must use scene geocoding instead.
     */
    static boolean isGeocoded(final Product p) {
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(p);
        if (absRoot != null && absRoot.getAttributeInt(AbstractMetadata.is_terrain_corrected, 0) == 1) {
            return true;
        }
        // Fallback: products with a CrsGeoCoding are map-projected even if the flag is unset
        // (e.g. Reproject output that didn't go through a SAR-aware operator).
        return p.getSceneGeoCoding() instanceof org.esa.snap.core.datamodel.CrsGeoCoding;
    }

    private boolean anySourceIsGeocoded() {
        for (final Product p : sourceProduct) {
            if (isGeocoded(p)) return true;
        }
        return false;
    }

    private boolean allSourcesAreGeocoded() {
        for (final Product p : sourceProduct) {
            if (!isGeocoded(p)) return false;
        }
        return true;
    }

    /**
     * Geocoding-based pixel-offset computation for map-projected products (GSLC, RD-TC, etc.).
     * <p>
     * Picks an anchor {@code (lat, lon)} at the reference scene centre, then asks each secondary
     * product's geocoding "what pixel corresponds to this lat/lon?". The integer offset is
     * {@code (secondaryPixel - referencePixel)} at that anchor — same convention as the
     * orbit-based method, so {@link #computeTile} reads the correct slave pixel without any
     * change to its logic. Per-band offsets are written to the existing {@code Orbit_Offsets}
     * metadata element for traceability.
     * <p>
     * Caveat: this is an integer-pixel offset, valid exactly at the anchor and across the full
     * scene only when both grids share the same pixel size and parallel axes (e.g. master GSLC
     * with reference-locked slave GSLC, or two GSLCs of the same CRS and pixel spacing). For
     * other cases use {@code resamplingType != NONE} so the slave is resampled into the
     * reference grid.
     */
    private void computeTargetSecondaryCoordinateOffsets_Geocoded() throws Exception {
        final GeoCoding targGeoCoding = targetProduct.getSceneGeoCoding();
        if (targGeoCoding == null) {
            throw new OperatorException("Target product has no scene geocoding; cannot compute geocoded offset.");
        }
        final int tw = targetProduct.getSceneRasterWidth();
        final int th = targetProduct.getSceneRasterHeight();

        final PixelPos refAnchorPP = new PixelPos(tw / 2.0, th / 2.0);
        final GeoPos anchorGP = new GeoPos();
        targGeoCoding.getGeoPos(refAnchorPP, anchorGP);
        if (!anchorGP.isValid()) {
            throw new OperatorException(
                    "Could not derive a valid lat/lon at the reference centre " + refAnchorPP +
                    " — geocoding of '" + referenceProduct.getName() + "' is broken.");
        }
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(targetProduct);
        MetadataElement orbitOffsets = absRoot.getElement("Orbit_Offsets");
        if (orbitOffsets == null) {
            orbitOffsets = new MetadataElement("Orbit_Offsets");
            absRoot.addElement(orbitOffsets);
        }

        final PixelPos secPP = new PixelPos();
        for (final Product secProd : sourceProduct) {
            if (secProd == referenceProduct) {
                secondaryOffsetMap.put(secProd, new int[]{0, 0});
                continue;
            }
            final GeoCoding secGeoCoding = secProd.getSceneGeoCoding();
            if (secGeoCoding == null) {
                throw new OperatorException(
                        "Secondary product '" + secProd.getName() + "' has no scene geocoding.");
            }
            secGeoCoding.getPixelPos(anchorGP, secPP);
            if (!secPP.isValid()) {
                throw new OperatorException(
                        "Secondary '" + secProd.getName() + "' does not overlap the reference centre " +
                                anchorGP + ". Cannot determine geocoded pixel offset.");
            }
            final int offsetX = (int) Math.floor(secPP.x - refAnchorPP.x + 0.5);
            final int offsetY = (int) Math.floor(secPP.y - refAnchorPP.y + 0.5);
            SystemUtils.LOG.fine("CreateStack: geocoded offset for '" + secProd.getName() +
                    "' = (" + offsetX + ", " + offsetY + ") px " +
                    "(sub-pixel residual=" + (secPP.x - refAnchorPP.x - offsetX) + ", " +
                    (secPP.y - refAnchorPP.y - offsetY) + ")");

            final String timeStamp = StackUtils.createBandTimeStamp(secProd).substring(1);
            for (String bandName : targetProduct.getBandNames()) {
                final String[] parts = bandName.split("_");
                if (parts.length > 0 && parts[parts.length - 1].equals(timeStamp)) {
                    final MetadataElement bandElem = new MetadataElement(
                            "init_offsets" + StackUtils.getBandSuffix(bandName));
                    bandElem.setAttributeInt("init_offset_X", offsetX);
                    bandElem.setAttributeInt("init_offset_Y", offsetY);
                    orbitOffsets.addElement(bandElem);
                }
            }
            addOffset(secProd, offsetX, offsetY);
        }
    }

    private static boolean pixelPosValid(final GeoCoding geoCoding, final GeoPos geoPos, final PixelPos pixelPos,
                                         final int width, final int height) {
        geoCoding.getPixelPos(geoPos, pixelPos);
        return (pixelPos.isValid() && pixelPos.x >= 0 && pixelPos.x < width &&
                pixelPos.y >= 0 && pixelPos.y < height);
    }

    private static void getPixelPos(final double lat, final double lon, final GeoCoding srcGeoCoding, final PixelPos pixelPos) {
        srcGeoCoding.getPixelPos(new GeoPos(lat, lon), pixelPos);
    }

    private void addOffset(final Product secProd, final int offsetX, final int offsetY) {
        secondaryOffsetMap.put(secProd, new int[]{offsetX, offsetY});
    }

    @Override
    public void computeTile(final Band targetBand, final Tile targetTile, final ProgressMonitor pm) throws OperatorException {
        ensureBiasJobsRan();
        try {
            final Band sourceRaster = sourceRasterMap.get(targetBand);
            final Product srcProduct = sourceRaster.getProduct();
            final int srcImageWidth = srcProduct.getSceneRasterWidth();
            final int srcImageHeight = srcProduct.getSceneRasterHeight();

            if (!isResampling) { // without resampling

                final float noDataValue = (float) targetBand.getGeophysicalNoDataValue();
                final Rectangle targetRectangle = targetTile.getRectangle();
                final ProductData trgData = targetTile.getDataBuffer();
                final int tx0 = targetRectangle.x;
                final int ty0 = targetRectangle.y;
                final int tw = targetRectangle.width;
                final int th = targetRectangle.height;
                final int maxX = tx0 + tw;
                final int maxY = ty0 + th;

                final int[] offset = secondaryOffsetMap.get(srcProduct);
                final int sx0 = Math.min(Math.max(0, tx0 + offset[0]), srcImageWidth - 1);
                final int sy0 = Math.min(Math.max(0, ty0 + offset[1]), srcImageHeight - 1);
                final int sw = Math.min(sx0 + tw - 1, srcImageWidth - 1) - sx0 + 1;
                final int sh = Math.min(sy0 + th - 1, srcImageHeight - 1) - sy0 + 1;
                final Rectangle srcRectangle = new Rectangle(sx0, sy0, sw, sh);
                final Tile srcTile = getSourceTile(sourceRaster, srcRectangle);
                final ProductData srcData = srcTile.getDataBuffer();

                // Mutual no-data: target is no-data if EITHER master or slave is no-data
                // at the corresponding pixel. We always validate BOTH sides (the source
                // band IS the probe for its own side; we additionally read the other side's
                // probe band). Validity is "not NaN, not equal to the band's no-data value,
                // and not zero" — zero is treated as no-data because SAR i/q outside the
                // acquisition footprint is written as 0 by GSLCGeocodingOp regardless of
                // the band's declared no-data value.
                final Band masterProbe = validityProbeBand(referenceProduct);
                final Band slaveProbeBand;
                final int[] slaveOffset;
                final boolean targetIsMasterBand = (srcProduct == referenceProduct);
                if (targetIsMasterBand) {
                    Product foundSlave = null;
                    for (final Product p : sourceProduct) {
                        if (p != referenceProduct) { foundSlave = p; break; }
                    }
                    slaveProbeBand = validityProbeBand(foundSlave);
                    slaveOffset = foundSlave != null ? secondaryOffsetMap.get(foundSlave) : null;
                } else {
                    slaveProbeBand = validityProbeBand(srcProduct);
                    slaveOffset = offset;
                }

                // Use the source band itself to probe its own side; use the OTHER product's
                // probe to probe the other side. This avoids the buggy "skip probe when
                // it equals sourceRaster" path that previously left whole rows mis-flagged.
                final Band ownSideProbe = sourceRaster;
                final int[] ownSideOffset = offset; // sourceRaster lives in srcProduct's coords
                final double ownNoData = ownSideProbe.getNoDataValue();
                final boolean ownUsesNoData = ownSideProbe.isNoDataValueUsed();

                final Band otherSideProbe = targetIsMasterBand ? slaveProbeBand : masterProbe;
                final int[] otherSideOffset = targetIsMasterBand ? slaveOffset : new int[]{0, 0};
                final double otherNoData = otherSideProbe != null ? otherSideProbe.getNoDataValue() : Double.NaN;
                final boolean otherUsesNoData = otherSideProbe != null && otherSideProbe.isNoDataValueUsed();

                Tile otherProbeTile = null;
                ProductData otherProbeData = null;
                TileIndex otherProbeIndex = null;
                int otherProbeW = 0, otherProbeH = 0;
                boolean otherTileEntirelyOOB = false;
                if (otherSideProbe != null && otherSideOffset != null) {
                    otherProbeW = otherSideProbe.getRasterWidth();
                    otherProbeH = otherSideProbe.getRasterHeight();
                    // Does the requested target tile (shifted by the other-side offset)
                    // intersect the other product's raster at all?
                    final int otx0 = tx0 + otherSideOffset[0];
                    final int oty0 = ty0 + otherSideOffset[1];
                    if (otx0 + tw <= 0 || otx0 >= otherProbeW ||
                            oty0 + th <= 0 || oty0 >= otherProbeH) {
                        // Entire target tile is outside the other product's footprint —
                        // every pixel here must be no-data.
                        otherTileEntirelyOOB = true;
                    } else {
                        final int osx0 = Math.min(Math.max(0, otx0), otherProbeW - 1);
                        final int osy0 = Math.min(Math.max(0, oty0), otherProbeH - 1);
                        final int osw = Math.min(osx0 + tw - 1, otherProbeW - 1) - osx0 + 1;
                        final int osh = Math.min(osy0 + th - 1, otherProbeH - 1) - osy0 + 1;
                        if (osw > 0 && osh > 0) {
                            otherProbeTile = getSourceTile(otherSideProbe, new Rectangle(osx0, osy0, osw, osh));
                            otherProbeData = otherProbeTile.getDataBuffer();
                            otherProbeIndex = new TileIndex(otherProbeTile);
                        } else {
                            otherTileEntirelyOOB = true;
                        }
                    }
                }
                // Fast path: entire output tile is no-data because the other product
                // doesn't overlap it at all.
                if (otherTileEntirelyOOB) {
                    final TileIndex trgIdxFast = new TileIndex(targetTile);
                    for (int ty = ty0; ty < maxY; ++ty) {
                        final int trgOffset = trgIdxFast.calculateStride(ty);
                        for (int tx = tx0; tx < maxX; ++tx) {
                            trgData.setElemDoubleAt(tx - trgOffset, noDataValue);
                        }
                    }
                    return;
                }

                final TileIndex trgIndex = new TileIndex(targetTile);
                final TileIndex srcIndex = new TileIndex(srcTile);

                boolean isInt = false;
                final int trgDataType = trgData.getType();
                if (trgDataType == srcData.getType() &&
                        (trgDataType == ProductData.TYPE_INT16 || trgDataType == ProductData.TYPE_INT32)) {
                    isInt = true;
                }

                for (int ty = ty0; ty < maxY; ++ty) {
                    final int sy = ty + ownSideOffset[1];
                    final int trgOffset = trgIndex.calculateStride(ty);

                    // Other-side row setup
                    int otherRowStride = 0;
                    boolean otherRowAvail = false;
                    if (otherProbeData != null) {
                        final int oy = ty + otherSideOffset[1];
                        if (oy >= 0 && oy < otherProbeH) {
                            otherRowStride = otherProbeIndex.calculateStride(oy);
                            otherRowAvail = true;
                        }
                    }
                    // If the other-side probe row is out-of-bounds, the entire output row
                    // is no-data (other side has no coverage here).
                    final boolean otherRowOOB = (otherProbeData != null) && !otherRowAvail;

                    if (sy < 0 || sy >= srcImageHeight || otherRowOOB) {
                        for (int tx = tx0; tx < maxX; ++tx) {
                            trgData.setElemDoubleAt(tx - trgOffset, noDataValue);
                        }
                        continue;
                    }
                    final int srcOffset = srcIndex.calculateStride(sy);
                    for (int tx = tx0; tx < maxX; ++tx) {
                        final int sx = tx + ownSideOffset[0];
                        if (sx < 0 || sx >= srcImageWidth) {
                            trgData.setElemDoubleAt(tx - trgOffset, noDataValue);
                            continue;
                        }

                        // Validate own side via source pixel
                        final double ownVal = srcData.getElemDoubleAt(sx - srcOffset);
                        final boolean ownNoDataHere = Double.isNaN(ownVal) || ownVal == 0.0 ||
                                (ownUsesNoData && ownVal == ownNoData);

                        // Validate other side via other-side probe
                        boolean otherNoDataHere = false;
                        if (otherProbeData != null && otherRowAvail) {
                            final int ox = tx + otherSideOffset[0];
                            if (ox < 0 || ox >= otherProbeW) {
                                otherNoDataHere = true;
                            } else {
                                final double ov = otherProbeData.getElemDoubleAt(ox - otherRowStride);
                                otherNoDataHere = Double.isNaN(ov) || ov == 0.0 ||
                                        (otherUsesNoData && ov == otherNoData);
                            }
                        }

                        if (ownNoDataHere || otherNoDataHere) {
                            trgData.setElemDoubleAt(tx - trgOffset, noDataValue);
                        } else if (isInt) {
                            trgData.setElemIntAt(tx - trgOffset, srcData.getElemIntAt(sx - srcOffset));
                        } else {
                            trgData.setElemDoubleAt(tx - trgOffset, ownVal);
                        }
                    }
                }

            } else { // with resampling

                final Collocator col = new Collocator(this, srcProduct, targetProduct, targetTile.getRectangle());
                col.collocateSourceBand(sourceRaster, targetTile, selectedResampling);
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    public static void checkPixelSpacing(final Product[] sourceProducts) {
        double savedRangeSpacing = 0.0;
        double savedAzimuthSpacing = 0.0;
        for (final Product prod : sourceProducts) {
            final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(prod);
            if (absRoot == null) {
                throw new OperatorException(
                        MessageFormat.format("Product ''{0}'' has no abstract metadata.", prod.getName()));
            }

            final double rangeSpacing = absRoot.getAttributeDouble(AbstractMetadata.range_spacing, 0);
            final double azimuthSpacing = absRoot.getAttributeDouble(AbstractMetadata.azimuth_spacing, 0);
            if(rangeSpacing == 0 || azimuthSpacing == 0)
                return;
            if (savedRangeSpacing > 0.0 && savedAzimuthSpacing > 0.0 &&
                    (Math.abs(rangeSpacing - savedRangeSpacing) > 0.05 ||
                            Math.abs(azimuthSpacing - savedAzimuthSpacing) > 0.05)) {
                throw new OperatorException("Resampling type cannot be NONE because pixel spacings" +
                                                    " are different for reference and secondary products");
            } else {
                savedRangeSpacing = rangeSpacing;
                savedAzimuthSpacing = azimuthSpacing;
            }
        }
    }

    // for unit test
    protected void setTestParameters(final String ext, final String offsetMethod) {
        this.extent = ext;
        this.initialOffsetMethod = offsetMethod;
    }

    /**
     * Operator SPI.
     */
    public static class Spi extends OperatorSpi {

        public Spi() {
            super(CreateStackOp.class);
        }
    }
}
