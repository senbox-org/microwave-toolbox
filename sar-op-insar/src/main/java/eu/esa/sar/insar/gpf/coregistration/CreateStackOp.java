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
    /** Slave SLCs reloaded from disk; freed in {@link #dispose()}, not in doExecute (see there). */
    private final java.util.List<Product> deferredDisposeProducts = new java.util.ArrayList<>();
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
        // Slave SLCs reloaded from a gslc_source_slc_path stamp are held until here: the auto-built
        // secondary GSLC reads them lazily from computeTile, so they cannot be freed in doExecute.
        for (final Product p : deferredDisposeProducts) {
            try {
                p.dispose();
            } catch (Throwable t) {
                SystemUtils.LOG.fine("CreateStack: deferred dispose failed for '" +
                        p.getName() + "': " + t.getMessage());
            }
        }
        deferredDisposeProducts.clear();
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

                        if (!isResampling && extent.equals(MASTER_EXTENT)
                                && srcProduct.isCompatibleProduct(targetProduct, passThroughEps(srcProduct))) {
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

            // The offsets are only known now, AFTER the target bands were created — so any
            // pass-through wiring set up during band creation has to be re-examined here.
            revokePassThroughForShiftedSecondaries();

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
                double[] rangePoly = null;
                double[] azimuthPoly = null;
                if (skipBiasEstimation) {
                    SystemUtils.LOG.info("CreateStack: skipBiasEstimation=true — using bias=0 " +
                            "for slave '" + job.slaveSlc.getName() + "' (geometric coregistration only).");
                    pm.worked(1);
                } else if (reloadedMasterSlcForBias != null) {
                    try {
                        final SlcBiasEstimate bias = estimateSlcBias(reloadedMasterSlcForBias, job.slaveSlc,
                                com.bc.ceres.core.SubProgressMonitor.create(pm, 1));
                        dRangePixels = bias.dRange;
                        dAzimuthPixels = bias.dAzimuth;
                        rangePoly = bias.rangePoly;
                        azimuthPoly = bias.azimuthPoly;
                        SystemUtils.LOG.info(String.format(
                                "CreateStack: bias for slave '%s' — Δrange=%+.4f px, Δazimuth=%+.4f px%s",
                                job.slaveSlc.getName(), dRangePixels, dAzimuthPixels,
                                (rangePoly != null || azimuthPoly != null)
                                        ? " + spatially-varying offset field" : ""));
                    } catch (Throwable t) {
                        SystemUtils.LOG.warning("CreateStack: bias estimation failed for '" +
                                job.slaveSlc.getName() + "': " + t.getMessage() +
                                " — keeping placeholder (zero bias).");
                    }
                } else {
                    pm.worked(1);
                }

                if (pm.isCanceled()) throw new OperatorException("Cancelled by user.");

                // Step 2 — rebuild the slave GSLC with the bias applied. Skip when the
                // estimated correction never exceeds the estimator's own noise floor anywhere
                // in the scene: rebuilding for a few-millipixel correction buys nothing and
                // drags in the placeholder-swap machinery (a whole second geocoding pass).
                final int slaveW = job.slaveSlc.getSceneRasterWidth();
                final int slaveH = job.slaveSlc.getSceneRasterHeight();
                final double maxRg = maxAbsOffsetAtCorners(rangePoly, dRangePixels, slaveW, slaveH);
                final double maxAz = maxAbsOffsetAtCorners(azimuthPoly, dAzimuthPixels, slaveW, slaveH);
                if (maxRg < MIN_BIAS_PIXELS && maxAz < MIN_BIAS_PIXELS
                        && (maxRg != 0.0 || maxAz != 0.0)) {
                    SystemUtils.LOG.info(String.format(
                            "CreateStack: bias for '%s' (max |Δrg|=%.4f, |Δaz|=%.4f px anywhere in " +
                                    "the scene) is below %.2f px — keeping the unbiased geocoding.",
                            job.slaveSlc.getName(), maxRg, maxAz, MIN_BIAS_PIXELS));
                    dRangePixels = 0.0;
                    dAzimuthPixels = 0.0;
                    rangePoly = null;
                    azimuthPoly = null;
                }
                if (dRangePixels != 0.0 || dAzimuthPixels != 0.0
                        || rangePoly != null || azimuthPoly != null) {
                    pm.setSubTaskName("Re-geocoding '" + job.slaveSlc.getName() + "' with bias");
                    try {
                        final java.util.Map<String, Object> params = new HashMap<>();
                        params.put("outputFlattened", readMasterFlattenedState(referenceProduct));
                        params.put("outputAzimuthCarrier", readMasterAzimuthCarrierState(referenceProduct));
                        params.put("outputPhaseTerms", masterHasCarrierModelBand(referenceProduct));
                        final String refBurstTable = readMasterBurstValidTimes(referenceProduct);
                        if (refBurstTable != null) {
                            params.put("refBurstValidTimes", refBurstTable);
                        }
                        final String refKernel = readMasterImgResampling(referenceProduct);
                        if (refKernel != null) {
                            params.put("imgResamplingMethod", refKernel);
                        }
                        // Per axis: an accepted FIELD carries the whole correction (constant
                        // included) via the poly parameter; otherwise the scalar bias is applied
                        // the historical way (annotation shift). Never both on one axis.
                        if (rangePoly != null) {
                            params.put("rangeOffsetPoly", joinCoefficients(rangePoly));
                            params.put("rangeOffsetPixels", 0.0);
                        } else {
                            params.put("rangeOffsetPixels", dRangePixels);
                        }
                        if (azimuthPoly != null) {
                            params.put("azimuthOffsetPoly", joinCoefficients(azimuthPoly));
                            params.put("azimuthOffsetPixels", 0.0);
                        } else {
                            params.put("azimuthOffsetPixels", dAzimuthPixels);
                        }
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
                    // MUST NOT dispose here. Both the bias=0 placeholder and the bias-corrected
                    // rebuild are GSLC-Terrain-Correction targets that read this SLC *lazily* from
                    // computeTile, which runs after doExecute returns. Disposing it now leaves the
                    // secondary reading a dead product and the whole leg comes out as zeros — a
                    // silent, plausible-looking empty raster rather than an error.
                    //
                    // This only ever bit the all-GSLC path: the slave SLC is reloaded from the
                    // gslc_source_slc_path stamp (so disposeSlaveSlcAfter is true) only when the
                    // secondary is ITSELF already a GSLC. With a raw secondary, slaveSlc IS the
                    // source product, disposeSlaveSlcAfter is false, and nothing was ever freed —
                    // which is why reference-GSLC + raw-secondary always worked.
                    deferredDisposeProducts.add(job.slaveSlc);
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

        // A target band that was wired straight to the OLD product's image (the creation-time
        // pass-through) would keep reading the placeholder forever — the bias-corrected product
        // would be computed and then silently never used. Clear such wiring so the band goes
        // through computeTile, which reads from the re-keyed sourceRasterMap.
        for (final Band targetBand : updates.keySet()) {
            if (targetBand.isSourceImageSet()) {
                targetBand.setSourceImage(null);
                SystemUtils.LOG.info("CreateStack: cleared stale source-image pass-through on '" +
                        targetBand.getName() + "' after slave swap.");
            }
        }

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
                    // The GSLC azimuth-carrier MODEL band must ride the stack per leg: the
                    // interferogram subtracts the leg DIFFERENCE of the deramp models exactly —
                    // the deterministic ~70% of the cross-acquisition annotation mismatch that
                    // data-driven ramp fitting otherwise has to chase. It is a real (non-virtual)
                    // measurement band despite its PHASE unit, so exempt it from the phase-band
                    // skip below.
                    final boolean carrierModelBand = !(band instanceof VirtualBand)
                            && band.getName().startsWith(GSLC_CARRIER_MODEL_BAND);
                    if (bandUnit != null && bandUnit.equals(Unit.PHASE) && !carrierModelBand)
                        continue;
                    if (band instanceof VirtualBand && !(bandUnit != null && (bandUnit.equals(Unit.REAL) || bandUnit.equals(Unit.IMAGINARY))))
                        continue;
                    if (secProduct == referenceProduct && (band == referenceBands[0] || band == referenceBands[1] || appendToReference))
                        continue;

                    if(bandUnit == null || carrierModelBand) {
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

            // An already-geocoded secondary that is ALREADY on the master's lattice needs nothing
            // doing to it: stack it as-is.
            //
            // The rebuild machinery below exists to (a) geocode a raw secondary onto the master grid
            // and (b) re-geocode with a cross-correlation bias applied. Neither applies here. Both
            // legs of an all-GSLC stack were geocoded with the same standard-grid snapping, so they
            // are co-lattice by construction and the estimated bias is always below MIN_BIAS_PIXELS
            // (measured on a real S1 pair: Δrg −0.0391, Δaz +0.0047 px) — the rebuild is discarded
            // anyway. Skipping it removes an entire redundant geocoding pass, and avoids rebuilding
            // from the source SLC with a band set that need not match the secondary's own
            // (a BIOMASS quad-pol source re-geocodes to 8 bands where the product being replaced has
            // 2, which left the stack with no reference bands at all: "[bandNames] is an empty array").
            // Geometry alone is NOT sufficient to skip the rebuild. The rebuild also forces the
            // secondary onto the reference's PHASE conventions (outputFlattened,
            // outputAzimuthCarrier — see the params below). Skipping on a lattice match alone would
            // silently accept a flattened secondary against an unflattened reference, or a
            // carrier-restored leg against a carrier-free one, which this file's own javadoc
            // describes as yielding meaningless phase / tens of fringes per burst. Compare the
            // stamps too, and fall through to the rebuild when they disagree so the secondary is
            // brought onto the reference's conventions.
            if (isGeocoded(p) && isCoLatticeWith(masterGslc, p)) {
                final boolean flatMatches =
                        readMasterFlattenedState(masterGslc) == readMasterFlattenedState(p);
                final boolean carrierMatches =
                        readMasterAzimuthCarrierState(masterGslc) == readMasterAzimuthCarrierState(p);
                if (flatMatches && carrierMatches) {
                    SystemUtils.LOG.info("CreateStack: secondary '" + p.getName() + "' is already geocoded " +
                            "on the reference lattice with matching phase conventions — stacking as-is " +
                            "(no re-geocoding, no bias estimation).");
                    continue;
                }
                SystemUtils.LOG.warning("CreateStack: secondary '" + p.getName() + "' is co-lattice with " +
                        "the reference but its phase conventions differ (flattened match=" + flatMatches +
                        ", azimuth-carrier match=" + carrierMatches + ") — re-geocoding it onto the " +
                        "reference's conventions instead of stacking as-is.");
            }

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
                params.put("outputAzimuthCarrier", readMasterAzimuthCarrierState(masterGslc));
                // match the master's carrier-model-band contract so the interferogram can
                // subtract the leg difference of the deramp models exactly
                params.put("outputPhaseTerms", masterHasCarrierModelBand(masterGslc));
                // lock the secondary's burst-overlap boundaries to the master's so both legs
                // select the same burst at every map pixel (mixed-burst strips decorrelate)
                final String refBurstTable = readMasterBurstValidTimes(masterGslc);
                if (refBurstTable != null) {
                    params.put("refBurstValidTimes", refBurstTable);
                }
                final String refKernel = readMasterImgResampling(masterGslc);
                if (refKernel != null) {
                    params.put("imgResamplingMethod", refKernel);
                }
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

    /**
     * Whether the master GSLC carries the native TOPS azimuth carrier. An auto-built slave must
     * match this convention — a stack mixing carrier-restored and carrier-free legs contains an
     * uncancelled per-burst quadratic azimuth phase (~tens of fringes per burst on a
     * cross-platform pair). A missing stamp means a legacy product, which was always built with
     * the carrier restored — hence {@code true}, not {@code false}, as the fallback.
     * <p>
     * A third state, {@code "none"}, means the sensor has no beam-steering azimuth carrier at all
     * (NISAR and any other non-TOPS mission), for which the correct action is to add none. That
     * maps to {@code false} here, stated explicitly rather than left to
     * {@link Boolean#parseBoolean}'s "anything that isn't 'true'" default.
     */
    private static boolean readMasterAzimuthCarrierState(final Product masterGslc) {
        if (masterGslc == null) return true;
        final MetadataElement abs = AbstractMetadata.getAbstractedMetadata(masterGslc);
        if (abs == null) return true;
        final String s = abs.getAttributeString("gslc_azimuth_carrier", null);
        if (s == null) return true;
        if ("none".equalsIgnoreCase(s.trim()) || "n/a".equalsIgnoreCase(s.trim())) {
            return false;
        }
        return Boolean.parseBoolean(s);
    }

    /** The master GSLC's interpolation-kernel stamp, or null (legacy) — forwarded so an
     *  auto-built secondary resamples with the SAME kernel as the reference. */
    static String readMasterImgResampling(final Product masterGslc) {   // package-visible for tests
        if (masterGslc == null) return null;
        final MetadataElement abs = AbstractMetadata.getAbstractedMetadata(masterGslc);
        if (abs == null) return null;
        final String s = abs.getAttributeString("gslc_img_resampling", null);
        return (s == null || s.trim().isEmpty()) ? null : s;
    }

    /**
     * The master GSLC's {@code gslc_burst_valid_times} stamp, or null when absent (legacy or
     * non-TOPS master). Forwarded to an auto-built secondary as {@code refBurstValidTimes} so its
     * burst-overlap selection follows the master's boundaries — otherwise each leg splits the TOPS
     * burst overlap at its own midpoint, and in the strip between the two boundaries the stack
     * pairs looks from different bursts (~4 kHz apart in Doppler centroid), which no phase model
     * can make coherent.
     */
    private static String readMasterBurstValidTimes(final Product masterGslc) {
        if (masterGslc == null) return null;
        final MetadataElement abs = AbstractMetadata.getAbstractedMetadata(masterGslc);
        if (abs == null) return null;
        final String s = abs.getAttributeString("gslc_burst_valid_times", null);
        return (s == null || s.trim().isEmpty()) ? null : s;
    }

    /**
     * Whether the master GSLC carries the {@code azimuthCarrierPhase} model band (band presence is
     * the contract, not the recorded parameter): an internally-built secondary must match it so the
     * interferogram can subtract the leg difference of the deramp models exactly.
     */
    private static boolean masterHasCarrierModelBand(final Product masterGslc) {
        if (masterGslc == null) return false;
        for (final Band b : masterGslc.getBands()) {
            if (!(b instanceof VirtualBand) && b.getName().startsWith(GSLC_CARRIER_MODEL_BAND)) {
                return true;
            }
        }
        return false;
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
            // X and Y are locked SEPARATELY: a rectangular master (pixelSpacingInDegreeY set)
            // must produce a rectangular slave on the identical lattice — collapsing to a single
            // spacing here would put the slave on a different grid and fail the lattice guard.
            //
            // The step is taken from the affine image-to-map transform when available: it holds
            // the EXACT grid step as stored doubles. Deriving it by differencing geo positions
            // loses ~1e-10 relative to subtraction cancellation (ulp of a ~68 deg coordinate on a
            // ~1e-4 deg step), which puts the slave on a microscopically different lattice —
            // below the alignment guard's threshold, but avoidably imprecise.
            double dLon = Double.NaN, dLat = Double.NaN;
            if (gc instanceof org.esa.snap.core.datamodel.CrsGeoCoding) {
                final org.opengis.referencing.operation.MathTransform i2m =
                        ((org.esa.snap.core.datamodel.CrsGeoCoding) gc).getImageToMapTransform();
                if (i2m instanceof java.awt.geom.AffineTransform) {
                    final java.awt.geom.AffineTransform at = (java.awt.geom.AffineTransform) i2m;
                    // The affine scales are in MAP CRS UNITS, not necessarily degrees. Adopting them
                    // unconditionally turned a UTM master's ~14 m step into "14 degrees", which the
                    // metre conversion below then inflated to ~1000 km pixels. Only take them when the
                    // CRS axes are angular; for a projected CRS fall through to differencing GeoPos,
                    // which yields degrees for every CRS.
                    if (isAngularCrs(((org.esa.snap.core.datamodel.CrsGeoCoding) gc).getMapCRS())) {
                        dLon = Math.abs(at.getScaleX());
                        dLat = Math.abs(at.getScaleY());
                    }
                }
            }
            if (!(dLon > 0) || !(dLat > 0)) {
                dLon = Math.abs(g1.lon - g0.lon);
                dLat = Math.abs(g1.lat - g0.lat);
            }
            if (dLon > 0 && Double.isFinite(dLon) && dLat > 0 && Double.isFinite(dLat)) {
                params.put("pixelSpacingInDegree", dLon);
                params.put("pixelSpacingInDegreeY", dLat);
                // Convert to metres for the metre-based params as a safety net (some paths
                // in GSLCGeocodingOp read pixelSpacingInMeter regardless).
                final double latRad = Math.toRadians(g0.lat);
                final double pixelSizeMX = dLon * 111320.0 * Math.cos(latRad);
                final double pixelSizeMY = dLat * 111320.0;
                if (pixelSizeMX > 0 && Double.isFinite(pixelSizeMX)) {
                    params.put("pixelSpacingInMeter", pixelSizeMX);
                }
                if (pixelSizeMY > 0 && Double.isFinite(pixelSizeMY)) {
                    params.put("pixelSpacingInMeterY", pixelSizeMY);
                }
                SystemUtils.LOG.info("CreateStack: locking slave grid to master — " +
                        "pixelSpacingInDegree=" + dLon + " x " + dLat +
                        " (≈" + pixelSizeMX + " x " + pixelSizeMY + " m at lat " + g0.lat + ")");
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


    /**
     * Result of the slave-vs-master SLC bias estimation. {@code dRange}/{@code dAzimuth} are the
     * robust (median) constants — the historical scalar bias. {@code rangePoly}/{@code azimuthPoly}
     * are optional affine offset FIELDS "a0 + a1*x + a2*y" in source pixels (x = range sample,
     * y = azimuth line), fitted to the per-GCP offsets when they show a spatially coherent drift.
     * First seen on 1995 ERS-1/ERS-2 tandem VMP pairs, whose data-vs-annotation registration
     * drifts ~1.7 px in range across the swath and ~2 px in azimuth along the scene; a constant
     * bias leaves the drift in place and the retained range carrier turns it into hundreds of
     * radians of spurious smooth fringes in the GSLC interferogram (the classical chain absorbs
     * the same drift in its polynomial CC-warp). Null when the GCP set cannot support a stable
     * affine fit — callers then fall back to the scalar bias.
     */
    static final class SlcBiasEstimate {
        final double dRange, dAzimuth;
        final double[] rangePoly, azimuthPoly;

        SlcBiasEstimate(final double dRange, final double dAzimuth,
                        final double[] rangePoly, final double[] azimuthPoly) {
            this.dRange = dRange;
            this.dAzimuth = dAzimuth;
            this.rangePoly = rangePoly;
            this.azimuthPoly = azimuthPoly;
        }
    }

    private static SlcBiasEstimate estimateSlcBias(final Product masterSlc, final Product slaveSlc,
                                                   final ProgressMonitor pm)
            throws Exception {
        INSIDE_BIAS_ESTIMATION.set(true);
        try {
            // TOPS pairs cannot use the stripmap nested-CreateStack + Cross-Correlation path
            // (cross-correlation on raw TOPS SLCs is invalid, and the nested CreateStack refuses
            // TOPS). Use Back-Geocoding + ESD to get the (range, azimuth) residual instead.
            // TOPS gets constants only — its per-burst machinery handles the rest.
            if (isTopsSlc(masterSlc) && isTopsSlc(slaveSlc)) {
                final double[] off = estimateTopsBias(masterSlc, slaveSlc, pm);
                return new SlcBiasEstimate(off[0], off[1], null, null);
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

    private static SlcBiasEstimate estimateSlcBiasInner(final Product masterSlc, final Product slaveSlc,
                                                        final ProgressMonitor pm)
            throws Exception {
        // Primary estimator: a degree-2 offset field fitted from a dense, classical-chain-strength
        // GCP set (400 GCPs @ coherenceThreshold 0.4), converted to "need" against exact orbit
        // geometry. Root-cause fix for the ~130-rad smooth GSLC interferogram artifact on ERS
        // archive pairs: the classical chain's warp is a degree-2 polynomial fitted the same way;
        // our previous best (affine from 65 noisy FFT blocks) could not represent the same
        // spatially-varying need. estimateSlcBiasByGcpField cross-checks itself against the block
        // estimator and falls back to it outright on disagreement, so this is never a regression
        // versus the previous primary estimator.
        try {
            final SlcBiasEstimate est = estimateSlcBiasByGcpField(masterSlc, slaveSlc, pm);
            if (est != null) {
                return est;
            }
            SystemUtils.LOG.warning("CreateStack: GCP offset-field bias estimation yielded too few " +
                    "usable GCPs — falling back to the block-CC estimator.");
        } catch (Throwable t) {
            SystemUtils.LOG.warning("CreateStack: GCP offset-field bias estimation failed (" +
                    t.getMessage() + ") — falling back to the block-CC estimator.");
        }

        // Secondary estimator: block FFT cross-correlation against exact orbit geometry.
        // Measured need on the ERS-1/ERS-2 tandem VMP pair: the GCP-based affine fit
        // hallucinated a ~0.9 px range slope (33 coherence-gated GCPs, selection-biased)
        // where the true need is ~constant +0.5 px, and under-fitted the real ~1.8 px
        // azimuth drift 5x. The block method measures the needed correction directly:
        //   needed(az,rg) = (data offset from CC) - (orbit-geometric index difference)
        // in SLC pixels, unambiguously — validated against an independent python
        // measurement on the same pair before being adopted here.
        try {
            final SlcBiasEstimate est = estimateSlcBiasByBlocks(masterSlc, slaveSlc);
            if (est != null) {
                return est;
            }
            SystemUtils.LOG.warning("CreateStack: block-CC bias estimation yielded too few valid " +
                    "blocks — falling back to the GCP median estimator (constants only).");
        } catch (Throwable t) {
            SystemUtils.LOG.warning("CreateStack: block-CC bias estimation failed (" + t.getMessage() +
                    ") — falling back to the GCP median estimator (constants only).");
        }
        return estimateSlcBiasByGcps(masterSlc, slaveSlc, pm);
    }

    /**
     * Estimate the slave-vs-master coregistration need on a grid of image blocks:
     * for each block, the slave amplitude patch is pre-shifted by the orbit-geometric
     * index difference (jlinda zero-Doppler solves at the scene average height) and the
     * residual data offset is measured by FFT cross-correlation with parabolic sub-pixel
     * refinement. {@code needed = dataOffset - geometricDifference} is exactly the field
     * {@code GSLCGeocodingOp.rangeOffsetPoly/azimuthOffsetPoly} must apply (convention:
     * {@code rangeIndex += needed}; the sign is pinned by the file-gated ERS test).
     * Returns null when fewer than {@link #MIN_FIELD_BLOCKS} blocks correlate.
     */
    static SlcBiasEstimate estimateSlcBiasByBlocks(final Product masterSlc, final Product slaveSlc)
            throws Exception {
        final MetadataElement absM = AbstractMetadata.getAbstractedMetadata(masterSlc);
        final MetadataElement absS = AbstractMetadata.getAbstractedMetadata(slaveSlc);
        final org.jlinda.core.SLCImage mMeta = new org.jlinda.core.SLCImage(absM, masterSlc);
        final org.jlinda.core.SLCImage sMeta = new org.jlinda.core.SLCImage(absS, slaveSlc);
        final org.jlinda.core.Orbit mOrbit = new org.jlinda.core.Orbit(absM, 3);
        final org.jlinda.core.Orbit sOrbit = new org.jlinda.core.Orbit(absS, 3);
        final double avgHeight = absM.getAttributeDouble(AbstractMetadata.avg_scene_height, 0.0);

        final Band[] mIQ = findComplexPair(masterSlc);
        final Band[] sIQ = findComplexPair(slaveSlc);
        if (mIQ == null || sIQ == null) {
            SystemUtils.LOG.warning("CreateStack: block-CC needs complex (i/q) bands on both products.");
            return null;
        }

        final int W = masterSlc.getSceneRasterWidth();
        final int H = masterSlc.getSceneRasterHeight();
        final int SW = slaveSlc.getSceneRasterWidth();
        final int SH = slaveSlc.getSceneRasterHeight();
        final int BS = FIELD_BLOCK_SIZE;
        if (W < 2 * BS || H < 4 * BS) {
            return null;
        }

        final java.util.List<double[]> samples = new java.util.ArrayList<>(); // {rg, az, needRg, needAz}
        final int nRows = 14, nCols = 7;
        final int mgX = Math.max(BS / 2, 64), mgY = Math.max(BS, 512);
        for (int r = 0; r < nRows; r++) {
            final int y0 = mgY + (int) ((long) r * (H - 2 * mgY - BS) / Math.max(1, nRows - 1));
            for (int c = 0; c < nCols; c++) {
                final int x0 = mgX + (int) ((long) c * (W - 2 * mgX - BS) / Math.max(1, nCols - 1));
                try {
                    // orbit-geometric index difference at the block centre
                    final org.jlinda.core.Point xyz = mOrbit.lph2xyz(
                            y0 + BS / 2.0 + 1.0, x0 + BS / 2.0, avgHeight, mMeta);
                    final org.jlinda.core.Point tm = mOrbit.xyz2t(xyz, mMeta);
                    final org.jlinda.core.Point ts = sOrbit.xyz2t(xyz, sMeta);
                    // same-formula conversion on both legs so any index-origin convention cancels
                    final double dgeomAz = sMeta.ta2line(ts.y) - mMeta.ta2line(tm.y);
                    final double dgeomRg = sMeta.tr2pix(ts.x) - mMeta.tr2pix(tm.x);

                    final int sy = y0 + (int) Math.round(dgeomAz);
                    final int sx = x0 + (int) Math.round(dgeomRg);
                    if (sy < 0 || sx < 0 || sy + BS > SH || sx + BS > SW) {
                        continue;
                    }
                    final double[] ampM = readAmplitudeBlock(mIQ, x0, y0, BS);
                    final double[] ampS = readAmplitudeBlock(sIQ, sx, sy, BS);
                    if (ampM == null || ampS == null) {
                        continue;
                    }
                    final double[] d = blockCrossCorrelate(ampM, ampS, BS, FIELD_MAX_RESIDUAL_PX);
                    if (d == null) {
                        continue;
                    }
                    // cc peak d: m(x) ~ sBlock(x - d); feature at master x sits at slave
                    // raw x + intShift - d  =>  needed = (intShift - d) - dgeom
                    final double needAz = Math.round(dgeomAz) - d[0] - dgeomAz;
                    final double needRg = Math.round(dgeomRg) - d[1] - dgeomRg;
                    samples.add(new double[]{x0 + BS / 2.0, y0 + BS / 2.0, needRg, needAz});
                } catch (Throwable t) {
                    SystemUtils.LOG.fine("CreateStack: block (" + x0 + "," + y0 + ") skipped: "
                            + t.getMessage());
                }
            }
        }
        if (samples.size() < MIN_FIELD_BLOCKS) {
            return null;
        }
        final int n = samples.size();
        final double[] xs = new double[n], ys = new double[n], nRg = new double[n], nAz = new double[n];
        for (int i = 0; i < n; i++) {
            final double[] s = samples.get(i);
            xs[i] = s[0]; ys[i] = s[1]; nRg[i] = s[2]; nAz[i] = s[3];
        }
        final double medRg = median(nRg.clone());
        final double medAz = median(nAz.clone());
        // AFFINE ONLY for the auto-estimated field. Measured on the ERS tandem pair
        // (2026-08-07, v4 validation): a degree-3 fit to 65 blocks with ~±0.05 px CC noise
        // OVERFITS — its smooth wiggles injected ±0.05-0.1 px of spurious structure, i.e.
        // ±90-175 rad of phase through the retained carrier, making the interferogram WORSE
        // (residual-vs-classical concentration unchanged, ramp/profile estimators chasing the
        // injected surface). The affine field is what block-CC precision genuinely supports;
        // anything beyond it must come from the PHASE-side data-driven models
        // (subtractResidualRamp / residualRampRangeProfile), not the registration field.
        // The higher-degree machinery stays for the explicit rangeOffsetPoly/azimuthOffsetPoly
        // parameters (6/10-term expert use).
        final int wantDegree = 1;
        double[] rangePoly = null, azimuthPoly = null;
        int usedDegree = wantDegree;
        for (int dg = wantDegree; dg >= 1 && rangePoly == null; dg--) {
            rangePoly = fitPolyOffsetField(xs, ys, nRg, W, H, dg, MIN_FIELD_BLOCKS);
            usedDegree = dg;
        }
        for (int dg = Math.min(wantDegree, usedDegree); dg >= 1 && azimuthPoly == null; dg--) {
            azimuthPoly = fitPolyOffsetField(xs, ys, nAz, W, H, dg, MIN_FIELD_BLOCKS);
        }
        SystemUtils.LOG.info(String.format(
                "CreateStack: block-CC offset need for slave '%s' (%d blocks, field degree %d) — "
                        + "range %s px, azimuth %s px across the scene (medians %+.4f / %+.4f px).",
                slaveSlc.getName(), n, usedDegree,
                describeFieldRange(rangePoly, W, H, medRg),
                describeFieldRange(azimuthPoly, W, H, medAz), medRg, medAz));
        return new SlcBiasEstimate(medRg, medAz, rangePoly, azimuthPoly);
    }

    /** First (i, q) band pair by unit, or null. */
    private static Band[] findComplexPair(final Product p) {
        Band i = null, q = null;
        for (final Band b : p.getBands()) {
            if (b.getUnit() == null) continue;
            if (i == null && b.getUnit().equals(Unit.REAL)) i = b;
            else if (i != null && q == null && b.getUnit().equals(Unit.IMAGINARY)) q = b;
            if (i != null && q != null) break;
        }
        return (i != null && q != null) ? new Band[]{i, q} : null;
    }

    /** Amplitude block, or null when mostly empty (sea/fill). */
    private static double[] readAmplitudeBlock(final Band[] iq, final int x0, final int y0, final int bs)
            throws java.io.IOException {
        final float[] bi = new float[bs * bs];
        final float[] bq = new float[bs * bs];
        iq[0].readPixels(x0, y0, bs, bs, bi, ProgressMonitor.NULL);
        iq[1].readPixels(x0, y0, bs, bs, bq, ProgressMonitor.NULL);
        final double[] amp = new double[bs * bs];
        int nValid = 0;
        for (int k = 0; k < amp.length; k++) {
            amp[k] = Math.hypot(bi[k], bq[k]);
            if (amp[k] > 0) nValid++;
        }
        return (nValid > amp.length * 0.9) ? amp : null;
    }

    /**
     * FFT cross-correlation of two n x n amplitude blocks. Returns {dRow, dCol} — the shift
     * of the second block relative to the first ({@code a(x) ~ b(x - d)}) with parabolic
     * sub-pixel refinement — or null when the integer peak exceeds {@code maxShift} (no
     * plausible alignment). Package-visible for unit tests.
     */
    static double[] blockCrossCorrelate(final double[] ampA, final double[] ampB, final int n,
                                        final int maxShift) {
        double meanA = 0, meanB = 0;
        for (int k = 0; k < n * n; k++) { meanA += ampA[k]; meanB += ampB[k]; }
        meanA /= n * n; meanB /= n * n;

        final double[] a = new double[2 * n * n];
        final double[] b = new double[2 * n * n];
        for (int k = 0; k < n * n; k++) {
            a[2 * k] = ampA[k] - meanA;
            b[2 * k] = ampB[k] - meanB;
        }
        final edu.emory.mathcs.jtransforms.fft.DoubleFFT_2D fft =
                new edu.emory.mathcs.jtransforms.fft.DoubleFFT_2D(n, n);
        fft.complexForward(a);
        fft.complexForward(b);
        // X = A * conj(B), then inverse -> correlation surface
        for (int k = 0; k < n * n; k++) {
            final double ar = a[2 * k], ai = a[2 * k + 1];
            final double br = b[2 * k], bi = b[2 * k + 1];
            a[2 * k] = ar * br + ai * bi;
            a[2 * k + 1] = ai * br - ar * bi;
        }
        fft.complexInverse(a, true);

        int pkRow = 0, pkCol = 0;
        double pkVal = Double.NEGATIVE_INFINITY;
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                final double v = a[2 * (r * n + c)];
                if (v > pkVal) { pkVal = v; pkRow = r; pkCol = c; }
            }
        }
        final int dRow = pkRow <= n / 2 ? pkRow : pkRow - n;
        final int dCol = pkCol <= n / 2 ? pkCol : pkCol - n;
        if (Math.abs(dRow) > maxShift || Math.abs(dCol) > maxShift) {
            return null;
        }
        // parabolic sub-pixel on wrap-around neighbours
        final java.util.function.BiFunction<Integer, Integer, Double> cc = (r, c) ->
                a[2 * ((((r % n) + n) % n) * n + (((c % n) + n) % n))];
        final double subRow = parabolicPeakOffset(cc.apply(pkRow - 1, pkCol), pkVal, cc.apply(pkRow + 1, pkCol));
        final double subCol = parabolicPeakOffset(cc.apply(pkRow, pkCol - 1), pkVal, cc.apply(pkRow, pkCol + 1));
        return new double[]{dRow + subRow, dCol + subCol};
    }

    /** Vertex offset in [-0.5, 0.5] of the parabola through (-1,m), (0,c), (+1,p). */
    static double parabolicPeakOffset(final double m, final double c, final double p) {
        final double denom = m - 2 * c + p;
        if (!(Math.abs(denom) > 1e-30)) return 0.0;
        final double off = 0.5 * (m - p) / denom;
        return Math.max(-0.5, Math.min(0.5, off));
    }

    private static double median(final double[] v) {
        java.util.Arrays.sort(v);
        final int n = v.length;
        return (n % 2 == 0) ? 0.5 * (v[n / 2 - 1] + v[n / 2]) : v[n / 2];
    }

    /** Linear-interpolated percentile ({@code p} in [0,1]) of an ALREADY-SORTED-ASCENDING array. */
    private static double percentile(final double[] sortedAsc, final double p) {
        final int n = sortedAsc.length;
        if (n == 0) return Double.NaN;
        if (n == 1) return sortedAsc[0];
        final double idx = p * (n - 1);
        final int lo = (int) Math.floor(idx);
        final int hi = (int) Math.ceil(idx);
        if (lo == hi) return sortedAsc[lo];
        final double frac = idx - lo;
        return sortedAsc[lo] * (1 - frac) + sortedAsc[hi] * frac;
    }

    /**
     * Fix-round-2 diagnostic (GCP-field path only): log the raw-need distribution for one axis
     * BEFORE fitting/trimming — N, min, p10, median, p90, max, and the p10..p90 span. Compared
     * against the fitted field's total variation over the scene, this is what exposed the MAD
     * trim underfitting genuine smooth structure (see task-3B-report.md, Fix round 2).
     */
    private static void logNeedStats(final String axis, final double[] need, final String slaveName) {
        final double[] sorted = need.clone();
        java.util.Arrays.sort(sorted);
        final int n = sorted.length;
        if (n == 0) {
            SystemUtils.LOG.info("CreateStack: [GCP-field] " + axis + " raw-need stats for slave '" +
                    slaveName + "': N=0.");
            return;
        }
        final double p10 = percentile(sorted, 0.10);
        final double p50 = percentile(sorted, 0.50);
        final double p90 = percentile(sorted, 0.90);
        SystemUtils.LOG.info(String.format(
                "CreateStack: [GCP-field] %s raw-need stats for slave '%s': N=%d min=%+.4f p10=%+.4f "
                        + "median=%+.4f p90=%+.4f max=%+.4f px (p10..p90 span %.4f px).",
                axis, slaveName, n, sorted[0], p10, p50, p90, sorted[n - 1], p90 - p10));
    }

    /** Block size for the field estimator; large enough for robust amplitude CC on 1-look SLCs. */
    static final int FIELD_BLOCK_SIZE = 512;
    /** Largest credible residual (px) after geometric pre-alignment; beyond this = failed match. */
    static final int FIELD_MAX_RESIDUAL_PX = 8;
    /** Minimum correlated blocks for a usable estimate. */
    static final int MIN_FIELD_BLOCKS = 12;

    private static SlcBiasEstimate estimateSlcBiasByGcps(final Product masterSlc, final Product slaveSlc,
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
        // Constants only: the coherence-gated GCP set is selection-biased and CANNOT support
        // a slope fit (measured on the ERS tandem pair: it hallucinated a ~0.9 px range drift
        // where the true need is constant). Fields come from estimateSlcBiasByBlocks.
        java.util.Collections.sort(dxs);
        java.util.Collections.sort(dys);
        final int n = dxs.size();
        final double medDx = (n % 2 == 0) ? 0.5 * (dxs.get(n / 2 - 1) + dxs.get(n / 2)) : dxs.get(n / 2);
        final double medDy = (n % 2 == 0) ? 0.5 * (dys.get(n / 2 - 1) + dys.get(n / 2)) : dys.get(n / 2);
        return new SlcBiasEstimate(medDx, medDy, null, null);
    }

    /**
     * Read the nested stack's integer orbit-init offset for its (only) secondary band, written
     * by CreateStack's initial-offset step to the abstracted metadata: element
     * {@code Orbit_Offsets} → first child element → attributes {@code init_offset_X} /
     * {@code init_offset_Y} (ints). GCP positions coming out of the nested stack are in the
     * stack's own (master-extent) pixel frame; adding this offset converts them back to the raw
     * slave SLC's pixel frame. Best-effort — returns {@code {0, 0}} when the element is absent
     * or malformed (older/rebuilt stacks, or an orbit-init step that never ran).
     */
    private static double[] readNestedStackInitOffset(final Product nestedStack) {
        try {
            final MetadataElement abs = AbstractMetadata.getAbstractedMetadata(nestedStack);
            final MetadataElement orbitOffsets = abs == null ? null : abs.getElement("Orbit_Offsets");
            if (orbitOffsets == null || orbitOffsets.getNumElements() == 0) {
                return new double[]{0.0, 0.0};
            }
            final MetadataElement first = orbitOffsets.getElementAt(0);
            return new double[]{
                    first.getAttributeInt("init_offset_X", 0),
                    first.getAttributeInt("init_offset_Y", 0)};
        } catch (Throwable t) {
            return new double[]{0.0, 0.0};
        }
    }

    /**
     * Estimate the slave-vs-master coregistration need as a DEGREE-2 offset field fitted from a
     * dense, classical-chain-strength GCP set (400 GCPs, coherenceThreshold 0.4, fine
     * registration) — the measured root-cause fix for the ~130-rad smooth GSLC interferogram
     * artifact on ERS archive pairs (see task-3B-brief.md). The classical chain wins over the
     * historical affine/scalar estimators because its degree-2 CC-warp captures a spatially
     * coherent registration need that a plane (or a single number) cannot; this reuses the same
     * nested-CreateStack + Cross-Correlation machinery as {@link #estimateSlcBiasByGcps} but at
     * the settings that actually support a slope/curvature fit, converts each matched GCP into
     * an orbit-geometry-relative "need" using the same jlinda idiom as
     * {@link #estimateSlcBiasByBlocks}, and fits per-axis polynomials.
     * <p>
     * A REQUIRED cross-check against {@code estimateSlcBiasByBlocks} vetoes the field (falling
     * back to the block estimate outright) whenever the two disagree — drift-aware: per axis,
     * when the block estimator also fitted a field, both fields are sampled on the same 7x7
     * scene grid and compared by median |difference| (gate 0.15 px); when the block estimator
     * has constants only for that axis, the GCP field's own median over that grid is compared
     * against the block's scalar (gate 0.25 px). A single-point (e.g. scene-centre) comparison
     * against a spatially-varying block field is deliberately avoided — it produces a spurious
     * mismatch whenever the true field drifts across the scene (measured on the ERS pair: block
     * azimuth ranges +5.3..+7.4 px). When the block estimator is unavailable (null or throws),
     * the cross-check is skipped and the GCP field is accepted on its own guards.
     * <p>
     * Returns null when neither degree 2 nor degree 1 can be fitted for both axes.
     */
    static SlcBiasEstimate estimateSlcBiasByGcpField(final Product masterSlc, final Product slaveSlc,
                                                      final ProgressMonitor pm)
            throws Exception {
        final long t0 = System.currentTimeMillis();
        SystemUtils.LOG.fine("CreateStack: estimating GCP offset field for slave '" + slaveSlc.getName() +
                "' against master '" + masterSlc.getName() + "'");

        // Nested stack + Cross-Correlation, exactly as estimateSlcBiasByGcps but tuned to the
        // classical chain's settings (dense GCPs, looser coherence gate, fine registration) so
        // the resulting set can support a degree-2 fit rather than constants only.
        final CreateStackOp stackOp = new CreateStackOp();
        stackOp.setSourceProducts(masterSlc, slaveSlc);
        stackOp.setParameter("extent", MASTER_EXTENT);
        stackOp.setParameter("initialOffsetMethod", INITIAL_OFFSET_ORBIT);
        stackOp.setParameter("resamplingType", "NONE");
        stackOp.setParameter("autoCoregisterGSLC", false); // nested stack is SLC-on-SLC; nothing to coregister
        final Product stack = stackOp.getTargetProduct();

        final CrossCorrelationOp ccOp = new CrossCorrelationOp();
        ccOp.setSourceProduct(stack);
        ccOp.setParameter("numGCPtoGenerate", 400);
        ccOp.setParameter("coarseRegistrationWindowWidth", "64");
        ccOp.setParameter("coarseRegistrationWindowHeight", "64");
        ccOp.setParameter("applyFineRegistration", true);
        ccOp.setParameter("inSAROptimized", true);
        ccOp.setParameter("coherenceThreshold", 0.4);
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
        // as a side effect of computeTile. Driven exactly like estimateSlcBiasByGcps: a
        // row-stripe readPixels loop, ticking the caller's pm (CrossCorrelationOp prints its
        // own "Cross Correlating <slave>... N%" progress separately).
        final int w = slaveBand.getRasterWidth();
        final int h = slaveBand.getRasterHeight();
        final int step = 256;
        final int nSteps = (h + step - 1) / step;
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
        SystemUtils.LOG.fine("CreateStack: GCP field CC scan complete in " +
                (System.currentTimeMillis() - t0) / 1000 + "s.");

        final ProductNodeGroup<org.esa.snap.core.datamodel.Placemark> mGcp =
                GCPManager.instance().getGcpGroup(masterBand);
        final ProductNodeGroup<org.esa.snap.core.datamodel.Placemark> sGcp =
                GCPManager.instance().getGcpGroup(slaveBand);

        final double[] initOffset = readNestedStackInitOffset(stack);

        // jlinda geometric-difference idiom, built ONCE outside the per-GCP loop (verbatim from
        // estimateSlcBiasByBlocks): master frame == nested-stack frame here (extent=Master).
        final MetadataElement absM = AbstractMetadata.getAbstractedMetadata(masterSlc);
        final MetadataElement absS = AbstractMetadata.getAbstractedMetadata(slaveSlc);
        final org.jlinda.core.SLCImage mMeta = new org.jlinda.core.SLCImage(absM, masterSlc);
        final org.jlinda.core.SLCImage sMeta = new org.jlinda.core.SLCImage(absS, slaveSlc);
        final org.jlinda.core.Orbit mOrbit = new org.jlinda.core.Orbit(absM, 3);
        final org.jlinda.core.Orbit sOrbit = new org.jlinda.core.Orbit(absS, 3);
        final double avgHeight = absM.getAttributeDouble(AbstractMetadata.avg_scene_height, 0.0);

        final int slaveW = slaveSlc.getSceneRasterWidth();
        final int slaveH = slaveSlc.getSceneRasterHeight();

        final java.util.List<double[]> samples = new java.util.ArrayList<>(); // {px, py, needRg, needAz}
        for (final org.esa.snap.core.datamodel.Placemark mp
                : mGcp.toArray(new org.esa.snap.core.datamodel.Placemark[0])) {
            final org.esa.snap.core.datamodel.Placemark sp = sGcp.get(mp.getName());
            if (sp == null) continue;
            try {
                final double mx = mp.getPixelPos().x, my = mp.getPixelPos().y;
                final double sx = sp.getPixelPos().x, sy = sp.getPixelPos().y;
                // data offset in the nested-stack (master-extent) frame
                final double ox = sx - mx;
                final double oy = sy - my;
                // raw-slave-frame position for the fit (this is the frame GSLC-Terrain-Correction's
                // rangeOffsetPoly/azimuthOffsetPoly parameters are evaluated in downstream)
                final double px = sx + initOffset[0];
                final double py = sy + initOffset[1];

                // orbit-geometric index difference at the GCP position, exactly as
                // estimateSlcBiasByBlocks: (line, pixel) = (master y + 1, master x)
                final org.jlinda.core.Point xyz = mOrbit.lph2xyz(my + 1.0, mx, avgHeight, mMeta);
                final org.jlinda.core.Point tm = mOrbit.xyz2t(xyz, mMeta);
                final org.jlinda.core.Point ts = sOrbit.xyz2t(xyz, sMeta);
                final double dgeomAz = sMeta.ta2line(ts.y) - mMeta.ta2line(tm.y);
                final double dgeomRg = sMeta.tr2pix(ts.x) - mMeta.tr2pix(tm.x);

                final double needRg = ox + (initOffset[0] - dgeomRg);
                final double needAz = oy + (initOffset[1] - dgeomAz);
                samples.add(new double[]{px, py, needRg, needAz});
            } catch (Throwable t) {
                SystemUtils.LOG.fine("CreateStack: GCP '" + mp.getName() + "' skipped: " + t.getMessage());
            }
        }

        final int n = samples.size();
        if (n < 150) {
            SystemUtils.LOG.warning("CreateStack: GCP field estimation only has " + n +
                    " usable GCPs for slave '" + slaveSlc.getName() + "' (need >= 150 for a degree-2 fit).");
        }
        final double[] xs = new double[n], ys = new double[n], nRg = new double[n], nAz = new double[n];
        for (int i = 0; i < n; i++) {
            final double[] s = samples.get(i);
            xs[i] = s[0]; ys[i] = s[1]; nRg[i] = s[2]; nAz[i] = s[3];
        }

        // Fix-round-2 diagnostics: raw-need distribution BEFORE fitting/trimming, per axis. If
        // the fitted field's total variation ends up much smaller than the raw p10..p90 span,
        // the trim (not a genuinely flat need surface) is eating real structure.
        {
            double xMin = Double.POSITIVE_INFINITY, xMax = Double.NEGATIVE_INFINITY;
            double yMin = Double.POSITIVE_INFINITY, yMax = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < n; i++) {
                xMin = Math.min(xMin, xs[i]); xMax = Math.max(xMax, xs[i]);
                yMin = Math.min(yMin, ys[i]); yMax = Math.max(yMax, ys[i]);
            }
            SystemUtils.LOG.info(String.format(
                    "CreateStack: [GCP-field] matched-GCP spatial coverage for slave '%s': "
                            + "x=[%.1f..%.1f] of scene width %d (%.1f%%), y=[%.1f..%.1f] of scene height %d (%.1f%%).",
                    slaveSlc.getName(), xMin, xMax, slaveW, 100.0 * (xMax - xMin) / slaveW,
                    yMin, yMax, slaveH, 100.0 * (yMax - yMin) / slaveH));
        }
        logNeedStats("range", nRg, slaveSlc.getName());
        logNeedStats("azimuth", nAz, slaveSlc.getName());

        // Fix-round-2, step 4: neither the MAD trim nor a flat 7%-per-pass percentile trim on the
        // raw per-GCP needs reached the required field variation (see task-3B-report.md, Fix
        // round 2) — a fixed-cut/fixed-fraction trim cannot tell "genuine curvature at the scene
        // edges" from "a handful of gross correlation mismatches" once the residual scale shrinks
        // after the first pass. The classical chain's own answer to exactly this problem is CPM
        // (org.jlinda.core.coregistration.CPM) — the SAME InSAR-optimized, data-snooping (w-test)
        // degree-2 polynomial estimator WarpOp itself uses when inSAROptimized=true (see
        // WarpOp.getWarpData): it removes ONE statistically-significant outlier per iteration
        // against a calibrated critical value (95% confidence, matching WarpOp's rmsThreshold=0.05
        // default), not a blanket fraction, so it keeps genuine edge/corner structure that a
        // fixed-cut trim discards. WarpOp itself does NOT persist its fitted coefficients to
        // metadata (only per-GCP coordinates/RMS — confirmed by inspection: writeWarpDataToMetadata
        // writes "WarpData"/"GCP<i>" elements with ref_x/ref_y/sec_x/sec_y/rms, never the
        // polynomial coefficients), so there is nothing to read back from a WarpOp pass; instead
        // CPM is reused directly, in-process, on the SAME matched GCP groups (mGcp/sGcp) from our
        // own nested Cross-Correlation — no extra CC run needed. Its fitted warp polynomial is
        // then sampled on a 12x12 scene grid to build need(P) = warpPolynomial(P) - dgeom(P), and
        // OUR degree-2 field is fit to those exact (smooth, outlier-free) samples. Falls back to
        // the percentile-trim per-GCP fit (previous fix-round-2 attempt, still a genuine
        // improvement over plain MAD) if CPM is unavailable or fails.
        double[] rangePoly = null;
        double[] azimuthPoly = null;
        try {
            final int stackW = stack.getSceneRasterWidth();
            final int stackH = stack.getSceneRasterHeight();
            final org.jlinda.core.Window masterWindow = new org.jlinda.core.Window(0, stackH, 0, stackW);
            final org.jlinda.core.coregistration.CPM cpm = new org.jlinda.core.coregistration.CPM(
                    2, 20, 1.95996398454005f, masterWindow, mGcp, sGcp);
            if (!cpm.isValid()) {
                throw new OperatorException("CPM: not enough redundant GCPs for a degree-2 warp.");
            }
            cpm.computeCPM();
            cpm.computeEstimationStats();
            cpm.wrapJaiWarpPolynomial();
            final javax.media.jai.WarpPolynomial jaiWarp = cpm.getJAIWarp();
            if (jaiWarp == null) {
                throw new OperatorException("CPM: warp polynomial unavailable.");
            }
            final float[] xCoefF = jaiWarp.getXCoeffs();
            final float[] yCoefF = jaiWarp.getYCoeffs();
            final double[] warpXCoef = new double[xCoefF.length];
            final double[] warpYCoef = new double[yCoefF.length];
            for (int i = 0; i < xCoefF.length; i++) warpXCoef[i] = xCoefF[i];
            for (int i = 0; i < yCoefF.length; i++) warpYCoef[i] = yCoefF[i];
            final int warpDeg = offsetFieldDegreeOf(warpXCoef.length);

            SystemUtils.LOG.info(String.format(
                    "CreateStack: [GCP-field] CPM warp fit for slave '%s': %d of %d matched GCPs " +
                            "survived data-snooping (degree %d).",
                    slaveSlc.getName(), cpm.getNumObservations(), n, warpDeg));

            // need(P) = warpPolynomial(P) - dgeom(P), sampled on a 12x12 master-frame scene grid.
            final int GRID = 12;
            final double[] gx = new double[GRID * GRID], gy = new double[GRID * GRID];
            final double[] gNeedRg = new double[GRID * GRID], gNeedAz = new double[GRID * GRID];
            int k = 0;
            for (int a = 0; a < GRID; a++) {
                final double mx = a * (stackW - 1.0) / (GRID - 1);
                for (int b = 0; b < GRID; b++) {
                    final double my = b * (stackH - 1.0) / (GRID - 1);

                    // warp polynomial evaluated at MASTER (mx, my) predicts the corresponding
                    // SLAVE (stack-frame) position — same convention as offsetFieldTerms/evalOffsetField.
                    final double sxPred = evalOffsetField(warpXCoef, warpDeg, mx, my);
                    final double syPred = evalOffsetField(warpYCoef, warpDeg, mx, my);
                    final double ox = sxPred - mx;
                    final double oy = syPred - my;
                    final double px = sxPred + initOffset[0];
                    final double py = syPred + initOffset[1];

                    final org.jlinda.core.Point xyz = mOrbit.lph2xyz(my + 1.0, mx, avgHeight, mMeta);
                    final org.jlinda.core.Point tm = mOrbit.xyz2t(xyz, mMeta);
                    final org.jlinda.core.Point ts = sOrbit.xyz2t(xyz, sMeta);
                    final double dgeomAz = sMeta.ta2line(ts.y) - mMeta.ta2line(tm.y);
                    final double dgeomRg = sMeta.tr2pix(ts.x) - mMeta.tr2pix(tm.x);

                    gx[k] = px; gy[k] = py;
                    gNeedRg[k] = ox + (initOffset[0] - dgeomRg);
                    gNeedAz[k] = oy + (initOffset[1] - dgeomAz);
                    k++;
                }
            }

            // minPoints=100 (of the 144 GRID*GRID samples): these are smooth, noise-free synthetic
            // samples derived analytically from the already-fitted CPM warp polynomial and jlinda
            // geometry, not noisy independent measurements — MIN_GCPS's usual "enough independent
            // points to trust a robust fit" rationale doesn't apply here, so 100 is just a loose
            // sanity floor (comfortably above the 6-term degree-2 minimum) rather than a
            // statistically-derived threshold.
            rangePoly = fitPolyOffsetField(gx, gy, gNeedRg, slaveW, slaveH, 2, 100);
            azimuthPoly = fitPolyOffsetField(gx, gy, gNeedAz, slaveW, slaveH, 2, 100);
            if (rangePoly == null || azimuthPoly == null) {
                SystemUtils.LOG.warning("CreateStack: CPM-grid degree-2 fit failed for slave '" +
                        slaveSlc.getName() + "' — falling back to the per-GCP percentile-trim fit.");
                rangePoly = null;
                azimuthPoly = null;
            } else {
                SystemUtils.LOG.fine("CreateStack: [GCP-field] route=CPM-grid for slave '" +
                        slaveSlc.getName() + "'.");
            }
        } catch (Throwable t) {
            SystemUtils.LOG.warning("CreateStack: CPM warp estimation failed for slave '" +
                    slaveSlc.getName() + "' (" + t.getMessage() + ") — falling back to the per-GCP " +
                    "percentile-trim fit.");
        }

        // Fallback: the previous fix-round-2 attempt (7%-per-pass percentile trim of the raw,
        // noisy per-GCP needs) — still a genuine improvement over the original MAD trim even
        // though it did not by itself reach the required field variation. verbose=true logs each
        // pass's removed-point count.
        if (rangePoly == null) {
            rangePoly = fitPolyOffsetField(xs, ys, nRg, slaveW, slaveH, 2, 150, TrimMode.PERCENTILE, true);
            if (rangePoly == null) {
                rangePoly = fitPolyOffsetField(xs, ys, nRg, slaveW, slaveH, 1, 60, TrimMode.PERCENTILE, true);
            }
        }
        if (rangePoly == null) {
            SystemUtils.LOG.warning("CreateStack: GCP offset field (range) could not be fitted for " +
                    "slave '" + slaveSlc.getName() + "' at degree 2 or 1.");
            return null;
        }
        if (azimuthPoly == null) {
            azimuthPoly = fitPolyOffsetField(xs, ys, nAz, slaveW, slaveH, 2, 150, TrimMode.PERCENTILE, true);
            if (azimuthPoly == null) {
                azimuthPoly = fitPolyOffsetField(xs, ys, nAz, slaveW, slaveH, 1, 60, TrimMode.PERCENTILE, true);
            }
        }
        if (azimuthPoly == null) {
            SystemUtils.LOG.warning("CreateStack: GCP offset field (azimuth) could not be fitted for " +
                    "slave '" + slaveSlc.getName() + "' at degree 2 or 1.");
            return null;
        }

        final double medRg = median(nRg.clone());
        final double medAz = median(nAz.clone());

        // Cross-check against the independently-derived block-CC estimate — DRIFT-AWARE and
        // FRAME-AWARE.
        // Fix-round-1: the original gate compared the GCP field's CENTRE value against the
        // block estimator's spatial MEDIAN of a field that drifts ~+5.3..+7.4 px along
        // azimuth on the ERS pair; that is an apples-to-oranges comparison that guarantees a
        // spurious ~0.3 px "disagreement" even when both estimators agree everywhere. Instead,
        // per axis: when the block estimator fitted its own field, compare BOTH fields sampled
        // at the SAME 7x7 scene grid (median |difference|, gate 0.15 px) — the fair way to
        // compare two spatially-varying quantities; when the block estimator has constants
        // only for that axis, compare the GCP field's median over the same grid against the
        // block's scalar (gate 0.25 px, looser because a median-vs-scalar comparison already
        // discards spatial information on one side). Any exception from the block estimator,
        // or a null block result, is treated as "cross-check unavailable" — the GCP field is
        // then accepted on its own guards (fitPolyOffsetField's MAD trim / spread / drift
        // bounds), not vetoed.
        // Fix-round-3: the two fields live in DIFFERENT pixel frames. Verified by inspection —
        // estimateSlcBiasByBlocks samples at (x0 + BS/2, y0 + BS/2) where x0/y0 are laid out
        // directly over masterSlc's own raster (0..W-1, 0..H-1): its fields are fit in the
        // MASTER frame. estimateSlcBiasByGcpField samples at (px, py) = predicted-slave-position
        // + initOffset (raw-slave frame): its fields are fit in the RAW-SLAVE frame. The 7x7
        // grid below is laid out in MASTER-frame coordinates (matching the block field, which
        // needs no correction); the GCP field needs initOffset added before evaluation so both
        // sides are sampled at the same physical scene location. Dormant on the ERS gate fixture
        // only because its measured initOffset happens to be {0, 0}.
        SlcBiasEstimate blockEst = null;
        try {
            blockEst = estimateSlcBiasByBlocks(masterSlc, slaveSlc);
        } catch (Throwable t) {
            SystemUtils.LOG.fine("CreateStack: block-CC cross-check failed for slave '" +
                    slaveSlc.getName() + "': " + t.getMessage());
        }
        double dRange = medRg;
        double dAzimuth = medAz;
        if (blockEst == null) {
            SystemUtils.LOG.info("CreateStack: block-CC cross-check unavailable for slave '" +
                    slaveSlc.getName() + "' — accepting the GCP offset field on its own guards.");
        } else {
            final int W = masterSlc.getSceneRasterWidth();
            final int H = masterSlc.getSceneRasterHeight();

            final boolean rgFieldVsField = blockEst.rangePoly != null;
            final boolean azFieldVsField = blockEst.azimuthPoly != null;
            final double rgDiff = rgFieldVsField
                    ? medianFieldDiffOverScene(rangePoly, initOffset[0], initOffset[1],
                            blockEst.rangePoly, 0.0, 0.0, W, H)
                    : Math.abs(medianFieldOverScene(rangePoly, W, H, initOffset[0], initOffset[1])
                            - blockEst.dRange);
            final double azDiff = azFieldVsField
                    ? medianFieldDiffOverScene(azimuthPoly, initOffset[0], initOffset[1],
                            blockEst.azimuthPoly, 0.0, 0.0, W, H)
                    : Math.abs(medianFieldOverScene(azimuthPoly, W, H, initOffset[0], initOffset[1])
                            - blockEst.dAzimuth);
            final double rgGate = rgFieldVsField ? 0.15 : 0.25;
            final double azGate = azFieldVsField ? 0.15 : 0.25;

            SystemUtils.LOG.info(String.format(
                    "CreateStack: GCP-field cross-check for slave '%s' — range diff %.4f px " +
                            "(gate %.2f, %s), azimuth diff %.4f px (gate %.2f, %s).",
                    slaveSlc.getName(), rgDiff, rgGate, rgFieldVsField ? "field-vs-field" : "median-vs-median",
                    azDiff, azGate, azFieldVsField ? "field-vs-field" : "median-vs-median"));

            if (rgDiff > rgGate || azDiff > azGate) {
                SystemUtils.LOG.warning(String.format(
                        "CreateStack: GCP offset field for slave '%s' disagrees with the block-CC " +
                                "cross-check (range diff %.4f px, gate %.2f, %s; azimuth diff %.4f px, " +
                                "gate %.2f, %s) — using the block estimate instead.",
                        slaveSlc.getName(), rgDiff, rgGate, rgFieldVsField ? "field-vs-field" : "median-vs-median",
                        azDiff, azGate, azFieldVsField ? "field-vs-field" : "median-vs-median"));
                return blockEst;
            }
            dRange = blockEst.dRange;
            dAzimuth = blockEst.dAzimuth;
        }

        final int loggedDegree = Math.max(offsetFieldDegreeOf(rangePoly.length), offsetFieldDegreeOf(azimuthPoly.length));
        SystemUtils.LOG.info(String.format(
                "CreateStack: GCP offset field for slave '%s' (%d GCPs, degree %d) — "
                        + "range %s px, azimuth %s px across the scene (medians %+.4f / %+.4f px).",
                slaveSlc.getName(), n, loggedDegree,
                describeFieldRange(rangePoly, slaveW, slaveH, medRg),
                describeFieldRange(azimuthPoly, slaveW, slaveH, medAz), dRange, dAzimuth));

        return new SlcBiasEstimate(dRange, dAzimuth, rangePoly, azimuthPoly);
    }

    /**
     * Median of a non-null polynomial field's values sampled over the standard 7x7 scene grid
     * (its own fitted degree), each grid point (x, y) offset by ({@code offX}, {@code offY})
     * before evaluation. Used by the GCP-field cross-check's "median-vs-median" branch (block
     * estimator has constants only for that axis).
     * <p>
     * Fix-round-3: the grid (x, y) points are always laid out in the MASTER pixel frame (the
     * frame {@code estimateSlcBiasByBlocks}' own fields are fitted in). The GCP field, however,
     * is fitted in the RAW-SLAVE pixel frame ({@code estimateSlcBiasByGcpField} builds its fit
     * positions as nested-stack-frame position + {@code initOffset}). Evaluating the GCP field
     * at a master-frame grid point WITHOUT first adding {@code initOffset} silently compares it
     * at the wrong physical location — dormant on the ERS gate fixture only because that pair's
     * measured {@code initOffset} happens to be {0, 0}. Callers MUST pass {@code initOffset} (or
     * {@code (0, 0)} for a field that is already natively in the master frame, e.g. the block
     * field) so both sides of a comparison are evaluated at the same physical scene location.
     */
    static double medianFieldOverScene(final double[] poly, final int w, final int h,
                                       final double offX, final double offY) {
        final int degree = offsetFieldDegreeOf(poly.length);
        final double[] v = new double[49];
        int k = 0;
        for (int a = 0; a <= 6; a++) {
            for (int b = 0; b <= 6; b++) {
                v[k++] = evalOffsetField(poly, degree,
                        a * (w - 1.0) / 6 + offX, b * (h - 1.0) / 6 + offY);
            }
        }
        return median(v);
    }

    /**
     * Median of {@code |fieldA - fieldB|} sampled at the SAME 7x7 MASTER-frame scene grid points,
     * each field's own (x, y) offset by its own ({@code offAX}/{@code offAY},
     * {@code offBX}/{@code offBY}) before evaluation, each field evaluated at its own fitted
     * degree. Drift-aware alternative to comparing two spatially-varying fields at a single point
     * (e.g. the scene centre) or against each other's overall median, either of which is an
     * apples-to-oranges comparison for a field that drifts significantly across the scene
     * (measured on the ERS pair: block azimuth field ranges +5.3..+7.4 px). Used by the GCP-field
     * cross-check's "field-vs-field" branch.
     * <p>
     * Fix-round-3 (see {@link #medianFieldOverScene}'s note): the per-field offset lets each side
     * be evaluated in ITS OWN pixel frame while both are sampled at the same physical scene
     * location — e.g. the GCP field (raw-slave frame) needs {@code initOffset} added, the block
     * field (already master frame, matching the grid) needs {@code (0, 0)}.
     */
    static double medianFieldDiffOverScene(final double[] polyA, final double offAX, final double offAY,
                                           final double[] polyB, final double offBX, final double offBY,
                                           final int w, final int h) {
        final int degA = offsetFieldDegreeOf(polyA.length);
        final int degB = offsetFieldDegreeOf(polyB.length);
        final double[] diffs = new double[49];
        int k = 0;
        for (int a = 0; a <= 6; a++) {
            for (int b = 0; b <= 6; b++) {
                final double x = a * (w - 1.0) / 6, y = b * (h - 1.0) / 6;
                diffs[k++] = Math.abs(
                        evalOffsetField(polyA, degA, x + offAX, y + offAY)
                                - evalOffsetField(polyB, degB, x + offBX, y + offBY));
            }
        }
        return median(diffs);
    }

    /** "[min .. max]" of a polynomial field over a scene sample grid; "[c .. c]" for null. */
    private static String describeFieldRange(final double[] poly, final int w, final int h,
                                             final double constant) {
        if (poly == null) {
            return String.format("[%+.3f .. %+.3f]", constant, constant);
        }
        final int degree = offsetFieldDegreeOf(poly.length);
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (int a = 0; a <= 6; a++) {
            for (int b = 0; b <= 6; b++) {
                final double v = evalOffsetField(poly, degree, a * (w - 1.0) / 6, b * (h - 1.0) / 6);
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }
        return String.format("[%+.3f .. %+.3f]", min, max);
    }

    /** Largest |offset| a polynomial field (or scalar fallback) reaches over a 7x7 scene grid. */
    static double maxAbsOffsetAtCorners(final double[] poly, final double constant,
                                        final int w, final int h) {
        return maxAbsOffsetOverScene(poly, constant, w, h);
    }

    static double maxAbsOffsetOverScene(final double[] poly, final double constant,
                                        final int w, final int h) {
        if (poly == null) {
            return Math.abs(constant);
        }
        final int degree = offsetFieldDegreeOf(poly.length);
        double max = 0.0;
        for (int a = 0; a <= 6; a++) {
            for (int b = 0; b <= 6; b++) {
                max = Math.max(max, Math.abs(
                        evalOffsetField(poly, degree, a * (w - 1.0) / 6, b * (h - 1.0) / 6)));
            }
        }
        return max;
    }

    /**
     * Robust affine fit {@code d(x,y) = a0 + a1*x + a2*y} to per-GCP offsets, with two
     * MAD-based outlier-trim passes. Returns {@code [a0, a1, a2]} in absolute source pixels,
     * or null when the GCP set cannot support a stable plane: too few points, poor spatial
     * spread (slope would be extrapolation), or an implausibly steep fitted drift.
     * Package-visible for unit tests.
     */
    /**
     * Monomial exponents for the 2-D offset-field polynomial, constant first, degree-major:
     * [1, x, y, x², xy, y², x³, x²y, xy², y³] — 3/6/10 terms for degree 1/2/3. MUST stay in
     * sync with {@code GSLCGeocodingOp.evalOffsetPoly}'s convention (its degree-1 prefix is
     * the historical "a0,a1,a2" format). Pinned by tests on both sides.
     */
    static int[][] offsetFieldTerms(final int degree) {
        final java.util.List<int[]> t = new java.util.ArrayList<>();
        t.add(new int[]{0, 0});
        for (int dd = 1; dd <= degree; dd++) {
            for (int i = dd; i >= 0; i--) {
                t.add(new int[]{i, dd - i});
            }
        }
        return t.toArray(new int[0][]);
    }

    static double evalOffsetField(final double[] c, final int degree, final double x, final double y) {
        final int[][] terms = offsetFieldTerms(degree);
        double v = 0;
        for (int k = 0; k < terms.length && k < c.length; k++) {
            v += c[k] * Math.pow(x, terms[k][0]) * Math.pow(y, terms[k][1]);
        }
        return v;
    }

    static int offsetFieldDegreeOf(final int nTerms) {
        int d = 0, n = 1;
        while (n < nTerms) {
            d++;
            n += d + 1;
        }
        return d;
    }

    static double[] fitAffineOffsetField(final double[] x, final double[] y, final double[] d,
                                         final int width, final int height) {
        return fitPolyOffsetField(x, y, d, width, height, 1, 25);
    }

    static double[] fitAffineOffsetField(final double[] x, final double[] y, final double[] d,
                                         final int width, final int height, final int minPoints) {
        return fitPolyOffsetField(x, y, d, width, height, 1, minPoints);
    }

    /**
     * Outlier-trim strategy for {@link #fitPolyOffsetField}. {@code MAD} (the historical/default
     * behaviour, unchanged for every existing caller) cuts at {@code max(3*1.4826*MAD, 0.05)} px
     * — appropriate for the noisy 65-block FFT-CC set, but on a dense, low-noise GCP set (needs
     * RMS-about-fit ~0.1 px) it can misclassify genuine smooth structure (real extremes ~0.25 px
     * from the initial fit) as outliers across passes, underfitting the field. {@code PERCENTILE}
     * instead drops a fixed fraction (7%) of the currently-kept points by largest |residual| per
     * pass, with a hard floor of 150 surviving points — used ONLY by the GCP-field path
     * ({@link #estimateSlcBiasByGcpField}), which has enough points (150-400) to afford a
     * percentage-based trim and needs to preserve real sub-pixel structure the MAD cut would eat.
     */
    enum TrimMode { MAD, PERCENTILE }

    /**
     * Robust polynomial fit {@code d(x,y)} of the given degree (1..3) with two outlier-trim
     * passes ({@link TrimMode#MAD}, non-verbose) — the original signature, used by every
     * pre-existing caller (block path, {@code fitAffineOffsetField}, unit tests) with IDENTICAL
     * behaviour to before this diagnostic/percentile-trim addition.
     */
    static double[] fitPolyOffsetField(final double[] x, final double[] y, final double[] d,
                                       final int width, final int height, final int degree,
                                       final int minPoints) {
        return fitPolyOffsetField(x, y, d, width, height, degree, minPoints, TrimMode.MAD, false);
    }

    /**
     * Robust polynomial fit {@code d(x,y)} of the given degree (1..3) with two outlier-trim
     * passes; centred/normalised internally, coefficients returned in ABSOLUTE pixels in the
     * {@link #offsetFieldTerms} order. Null when the point set cannot support the fit.
     * {@code verbose} (GCP-field diagnostics only) logs each pass's removed-point count at INFO.
     */
    static double[] fitPolyOffsetField(final double[] x, final double[] y, final double[] d,
                                       final int width, final int height, final int degree,
                                       final int minPoints, final TrimMode trimMode,
                                       final boolean verbose) {
        final int MIN_GCPS = Math.max(minPoints, 3 * offsetFieldTerms(degree).length);
        final double MIN_SPAN_FRACTION = 0.35;
        final double MAX_DRIFT_PIXELS = 20.0;   // |a1|*W and |a2|*H sanity bound
        final int PERCENTILE_FLOOR = 150;
        if (x.length < MIN_GCPS || width <= 1 || height <= 1) {
            return null;
        }
        boolean[] keep = new boolean[x.length];
        java.util.Arrays.fill(keep, true);
        double[] coef = null;
        for (int pass = 0; pass < 3; pass++) {
            // spread check on the surviving points
            double xMin = Double.POSITIVE_INFINITY, xMax = Double.NEGATIVE_INFINITY;
            double yMin = Double.POSITIVE_INFINITY, yMax = Double.NEGATIVE_INFINITY;
            int n = 0;
            for (int i = 0; i < x.length; i++) {
                if (!keep[i]) continue;
                n++;
                xMin = Math.min(xMin, x[i]); xMax = Math.max(xMax, x[i]);
                yMin = Math.min(yMin, y[i]); yMax = Math.max(yMax, y[i]);
            }
            if (n < MIN_GCPS
                    || (xMax - xMin) < MIN_SPAN_FRACTION * width
                    || (yMax - yMin) < MIN_SPAN_FRACTION * height) {
                return null;
            }
            // centred/normalised LS for conditioning: monomials in u=(x-cx)/W, v=(y-cy)/H
            final double cx = 0.5 * (xMin + xMax), cy = 0.5 * (yMin + yMax);
            final int[][] terms = offsetFieldTerms(degree);
            final int nT = terms.length;
            final double[][] ata = new double[nT][nT];
            final double[] atb = new double[nT];
            final double[] row = new double[nT];
            for (int i = 0; i < x.length; i++) {
                if (!keep[i]) continue;
                final double u = (x[i] - cx) / width, v = (y[i] - cy) / height;
                for (int k = 0; k < nT; k++) {
                    row[k] = Math.pow(u, terms[k][0]) * Math.pow(v, terms[k][1]);
                }
                for (int a = 0; a < nT; a++) {
                    for (int b = 0; b < nT; b++) {
                        ata[a][b] += row[a] * row[b];
                    }
                    atb[a] += row[a] * d[i];
                }
            }
            final double[] cNorm = solveSymmetric(ata, atb);
            if (cNorm == null) {
                return null;
            }
            // expand normalised-centred monomials to absolute-pixel coefficients:
            // q*((x-cx)/W)^i*((y-cy)/H)^j = q/W^i/H^j * sum_{a<=i,b<=j} C(i,a)C(j,b)(-cx)^(i-a)(-cy)^(j-b) x^a y^b
            coef = new double[nT];
            for (int k = 0; k < nT; k++) {
                final int i = terms[k][0], j = terms[k][1];
                final double q = cNorm[k] / Math.pow(width, i) / Math.pow(height, j);
                for (int a = 0; a <= i; a++) {
                    for (int b = 0; b <= j; b++) {
                        final double f = binomial(i, a) * binomial(j, b)
                                * Math.pow(-cx, i - a) * Math.pow(-cy, j - b);
                        coef[termIndex(terms, a, b)] += q * f;
                    }
                }
            }

            if (pass == 2) break;

            // Outlier trim for the next pass. Residuals computed once against the currently-kept
            // points, then either the MAD cut or the percentile drop is applied to them.
            int nKept = 0;
            for (final boolean b : keep) if (b) nKept++;
            final int[] keptIdx = new int[nKept];
            final double[] keptResid = new double[nKept];
            {
                int p = 0;
                for (int i = 0; i < x.length; i++) {
                    if (!keep[i]) continue;
                    keptIdx[p] = i;
                    keptResid[p] = Math.abs(d[i] - evalOffsetField(coef, degree, x[i], y[i]));
                    p++;
                }
            }

            if (trimMode == TrimMode.PERCENTILE) {
                // Drop the worst 7% of currently-kept points by |residual|, never below the floor.
                final int nDrop = Math.min((int) Math.floor(0.07 * nKept), Math.max(0, nKept - PERCENTILE_FLOOR));
                if (nDrop > 0) {
                    final Integer[] order = new Integer[nKept];
                    for (int i = 0; i < nKept; i++) order[i] = i;
                    java.util.Arrays.sort(order, (a, b) -> Double.compare(keptResid[b], keptResid[a]));
                    for (int k = 0; k < nDrop; k++) {
                        keep[keptIdx[order[k]]] = false;
                    }
                }
                if (verbose) {
                    SystemUtils.LOG.info(String.format(
                            "CreateStack: [GCP-field] fitPolyOffsetField pass %d (percentile trim, degree %d): "
                                    + "removed %d of %d points (worst 7%%%s), %d remain.",
                            pass, degree, nDrop, nKept,
                            nDrop < (int) Math.floor(0.07 * nKept) ? ", floor reached" : "",
                            nKept - nDrop));
                }
            } else {
                final double[] sorted = keptResid.clone();
                java.util.Arrays.sort(sorted);
                final double mad = nKept > 0 ? sorted[nKept / 2] : 0.0;
                final double cut = Math.max(3.0 * 1.4826 * mad, 0.05);
                int removed = 0;
                for (int k = 0; k < nKept; k++) {
                    if (keptResid[k] > cut) {
                        keep[keptIdx[k]] = false;
                        removed++;
                    }
                }
                if (verbose) {
                    SystemUtils.LOG.info(String.format(
                            "CreateStack: [GCP-field] fitPolyOffsetField pass %d (MAD trim, degree %d): "
                                    + "mad=%.4f cut=%.4f removed %d of %d points.",
                            pass, degree, mad, cut, removed, nKept));
                }
            }
        }
        if (coef == null) {
            return null;
        }
        for (final double c : coef) {
            if (!Double.isFinite(c)) return null;
        }
        // sanity: the fitted field must stay bounded over the whole scene
        if (maxAbsOffsetOverScene(coef, 0.0, width, height) > MAX_DRIFT_PIXELS) {
            return null;
        }
        return coef;
    }

    static String joinCoefficients(final double[] c) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < c.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(c[i]);
        }
        return sb.toString();
    }

    private static double binomial(final int n, final int k) {
        double v = 1;
        for (int i = 0; i < k; i++) v = v * (n - i) / (i + 1);
        return v;
    }

    private static int termIndex(final int[][] terms, final int i, final int j) {
        for (int k = 0; k < terms.length; k++) {
            if (terms[k][0] == i && terms[k][1] == j) return k;
        }
        throw new IllegalStateException("no term x^" + i + " y^" + j);
    }

    /** Gaussian elimination with partial pivoting; null when singular. */
    private static double[] solveSymmetric(final double[][] ata, final double[] atb) {
        final int n = atb.length;
        final double[][] m = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(ata[i], 0, m[i], 0, n);
            m[i][n] = atb[i];
        }
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int rr = col + 1; rr < n; rr++) {
                if (Math.abs(m[rr][col]) > Math.abs(m[piv][col])) piv = rr;
            }
            final double[] tmp = m[col]; m[col] = m[piv]; m[piv] = tmp;
            final double dd = m[col][col];
            if (Math.abs(dd) < 1e-14) return null;
            for (int j = col; j < n + 1; j++) m[col][j] /= dd;
            for (int rr = 0; rr < n; rr++) {
                if (rr == col) continue;
                final double f = m[rr][col];
                for (int j = col; j < n + 1; j++) m[rr][j] -= f * m[col][j];
            }
        }
        final double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = m[i][n];
        return out;
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
    /**
     * Largest sub-pixel residual, in pixels, tolerated between two geocoded products before their
     * grids are considered incompatible. Correctly co-latticed products give exactly 0.
     */
    static final double MAX_GEOCODED_SUBPIXEL_RESIDUAL = 0.01;

    /** GSLC deramp-model band prefix (see GSLCGeocodingOp outputPhaseTerms); kept in the stack per
     *  leg so InterferogramOp can subtract the models' leg difference exactly. */
    static final String GSLC_CARRIER_MODEL_BAND = "azimuthCarrierPhase";

    /**
     * True when two geocoded products sit on the same lattice: same map CRS, same grid step, and an
     * origin offset that is a whole number of pixels (to within
     * {@link #MAX_GEOCODED_SUBPIXEL_RESIDUAL}). Such a pair can be stacked directly with an integer
     * shift — no resampling and no re-geocoding.
     * <p>
     * Conservative by design: anything it cannot prove (missing or non-affine geo-coding, rotated
     * grid, differing CRS) returns false and falls through to the existing rebuild path.
     */
    /**
     * True when the CRS's horizontal axes are angular (degrees), i.e. a geographic CRS. A projected
     * CRS reports linear units, and its image-to-map affine scales are metres — not interchangeable
     * with the pixelSpacingInDegree parameters.
     */
    private static boolean isAngularCrs(final org.opengis.referencing.crs.CoordinateReferenceSystem crs) {
        return crs instanceof org.opengis.referencing.crs.GeographicCRS;
    }

    private static boolean isCoLatticeWith(final Product reference, final Product secondary) {
        if (reference == null || secondary == null) {
            return false;
        }
        final GeoCoding rgc = reference.getSceneGeoCoding();
        final GeoCoding sgc = secondary.getSceneGeoCoding();
        if (!(rgc instanceof org.esa.snap.core.datamodel.CrsGeoCoding) || !(sgc instanceof org.esa.snap.core.datamodel.CrsGeoCoding)) {
            return false;
        }
        try {
            final org.esa.snap.core.datamodel.CrsGeoCoding rc = (org.esa.snap.core.datamodel.CrsGeoCoding) rgc, sc = (org.esa.snap.core.datamodel.CrsGeoCoding) sgc;
            if (!org.geotools.referencing.CRS.equalsIgnoreMetadata(rc.getMapCRS(), sc.getMapCRS())) {
                return false;
            }
            final java.awt.geom.AffineTransform ra = asAffine(rc), sa = asAffine(sc);
            if (ra == null || sa == null) {
                return false;
            }
            if (ra.getShearX() != 0 || ra.getShearY() != 0 || sa.getShearX() != 0 || sa.getShearY() != 0) {
                return false;
            }
            final double stepX = ra.getScaleX(), stepY = ra.getScaleY();
            if (stepX == 0 || stepY == 0) {
                return false;
            }
            // steps must match to a part in 1e-9 — coarser than the 1 mm spacing quantiser
            if (Math.abs(sa.getScaleX() - stepX) > Math.abs(stepX) * 1.0e-9
                    || Math.abs(sa.getScaleY() - stepY) > Math.abs(stepY) * 1.0e-9) {
                return false;
            }
            final double dx = (sa.getTranslateX() - ra.getTranslateX()) / stepX;
            final double dy = (sa.getTranslateY() - ra.getTranslateY()) / stepY;
            return Math.abs(dx - Math.rint(dx)) <= MAX_GEOCODED_SUBPIXEL_RESIDUAL
                    && Math.abs(dy - Math.rint(dy)) <= MAX_GEOCODED_SUBPIXEL_RESIDUAL;
        } catch (Throwable t) {
            return false;
        }
    }

    private static java.awt.geom.AffineTransform asAffine(final org.esa.snap.core.datamodel.CrsGeoCoding gc) {
        final org.opengis.referencing.operation.MathTransform i2m = gc.getImageToMapTransform();
        return (i2m instanceof java.awt.geom.AffineTransform) ? (java.awt.geom.AffineTransform) i2m : null;
    }

    /**
     * Smallest estimated coregistration bias (pixels) worth rebuilding a slave GSLC for.
     * ESD residuals on well-synchronized S1 pairs are a few millipixels (measured +0.004/−0.039 px
     * on a 7-day S1A/S1D pair); rebuilding the geocoding for those is pure cost.
     */
    static final double MIN_BIAS_PIXELS = 0.05;

    /**
     * Confirm that a secondary really does share the reference's lattice, i.e. that a single
     * <em>integer</em> pixel offset maps one onto the other everywhere — not just at the anchor
     * the offset was measured at.
     * <p>
     * The geocoded stacking path applies one integer offset per secondary. That is only valid if
     * the two grids have the same pixel size <em>and</em> origins separated by a whole number of
     * pixels. Products geocoded independently can violate both: GSLC snaps each output origin to
     * the global grid using its own derived step, so a minutely different step puts the two on
     * different lattices and leaves an arbitrary fractional offset. Measured on a real S1A/S1D
     * pair that residual was 0.219 px (3.06 m), which silently destroyed all interferometric
     * coherence while leaving amplitude and geolocation looking correct.
     * <p>
     * Checking four spread anchors rather than one catches a pixel-size mismatch too: if the
     * steps differ, the residual varies across the scene instead of staying constant.
     * <p>
     * Complex (i/q) stacks throw, because sub-pixel misalignment there is fatal to the phase.
     * Detected-amplitude stacks only warn — the same misalignment merely blurs slightly.
     */
    private void verifyGeocodedLatticeAlignment(final Product secProd, final GeoCoding secGeoCoding,
                                                final GeoCoding targGeoCoding, final int tw, final int th,
                                                final int offsetX, final int offsetY) {
        final double[][] anchors = {
                {tw * 0.25, th * 0.25}, {tw * 0.75, th * 0.25},
                {tw * 0.25, th * 0.75}, {tw * 0.75, th * 0.75}};

        final GeoPos gp = new GeoPos();
        final PixelPos refPP = new PixelPos();
        final PixelPos secPP2 = new PixelPos();
        double worst = 0.0, worstX = 0.0, worstY = 0.0;
        for (final double[] a : anchors) {
            refPP.setLocation(a[0], a[1]);
            targGeoCoding.getGeoPos(refPP, gp);
            if (!gp.isValid()) continue;
            secGeoCoding.getPixelPos(gp, secPP2);
            if (!secPP2.isValid()) continue;
            final double rx = secPP2.x - refPP.x - offsetX;
            final double ry = secPP2.y - refPP.y - offsetY;
            final double r = Math.max(Math.abs(rx), Math.abs(ry));
            if (r > worst) {
                worst = r;
                worstX = rx;
                worstY = ry;
            }
        }
        if (worst <= MAX_GEOCODED_SUBPIXEL_RESIDUAL) {
            return;
        }

        final String detail = String.format(
                "Geocoded stacking requires the secondary to sit on the reference's exact pixel "
                        + "lattice, but '%s' is off by (%.4f, %.4f) px — a whole-pixel offset cannot "
                        + "align them.%nThis happens when two products are geocoded independently: each "
                        + "snaps its origin to the global grid using its own derived pixel size, and even "
                        + "a ~1e-10 degree difference (typical between Sentinel-1 platforms) puts them on "
                        + "different lattices.%nFix: geocode only the reference and let CreateStack "
                        + "promote the secondary from its SLC (it locks the grid automatically), or re-run "
                        + "GSLC-Terrain-Correction on the secondary passing the reference's exact "
                        + "pixelSpacingInDegree.",
                secProd.getName(), worstX, worstY);

        if (hasComplexBands(secProd) || hasComplexBands(referenceProduct)) {
            throw new OperatorException(detail);
        }
        SystemUtils.LOG.warning("CreateStack: " + detail);
    }

    /**
     * Tolerance (degrees) for the corner-coordinate compatibility test that gates the
     * source-image pass-through. The historical constant 1.0e-3 deg is ~8 pixels for a
     * geocoded SAR product (~1.26e-4 deg/px) — wide enough to declare two products
     * "compatible" while they are offset by several whole pixels, which is exactly the
     * defect that silently destroyed GSLC interferometry. For geocoded inputs the
     * tolerance is therefore a quarter of a pixel, derived from the actual grid; radar
     * geometry products keep the historical value.
     */
    private float passThroughEps(final Product srcProduct) {
        if (!isGeocoded(srcProduct)) {
            return 1.0e-3f;
        }
        final GeoCoding gc = targetProduct.getSceneGeoCoding();
        if (gc == null) {
            return 1.0e-3f;
        }
        final GeoPos g0 = gc.getGeoPos(new PixelPos(0.5f, 0.5f), null);
        final GeoPos g1 = gc.getGeoPos(new PixelPos(1.5f, 1.5f), null);
        final double pixDeg = Math.max(Math.abs(g1.getLon() - g0.getLon()), Math.abs(g1.getLat() - g0.getLat()));
        return (pixDeg > 0 && Double.isFinite(pixDeg)) ? (float) (0.25 * pixDeg) : 1.0e-3f;
    }

    private static boolean hasComplexBands(final Product product) {
        if (product == null) return false;
        for (final Band b : product.getBands()) {
            final String u = b.getUnit();
            if (u != null && (u.contains(Unit.REAL) || u.contains(Unit.IMAGINARY))) {
                return true;
            }
        }
        return false;
    }

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

            verifyGeocodedLatticeAlignment(secProd, secGeoCoding, targGeoCoding, tw, th, offsetX, offsetY);

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

    /**
     * Undo the "wire the target band straight to the source image" shortcut for any secondary that
     * actually needs a non-zero integer pixel shift.
     * <p>
     * When {@code resamplingType=NONE} and {@code extent=Master}, band creation wires a secondary's
     * target band directly to the secondary's own source image whenever
     * {@link Product#isCompatibleProduct} says the two products match. That bypasses
     * {@link #computeTile}, which is the only place the integer offset in
     * {@code secondaryOffsetMap} is ever applied — so the shift is silently dropped and the
     * secondary is stacked unshifted.
     * <p>
     * The compatibility test compares corner lat/lon with a tolerance of <b>1.0e-3 degrees</b>.
     * For a geocoded SAR product with ~1.26e-4 deg pixels that is nearly <b>8 pixels</b>, so two
     * GSLCs of the same area essentially always pass it — while being offset by a few pixels.
     * On a real Sentinel-1 pair this left a (-4, +2) px = 56 m x 28 m misregistration (~16 range
     * resolution cells), which destroys interferometric coherence completely while leaving
     * amplitude and geolocation looking correct.
     * <p>
     * Offsets are only computed after the bands exist, so this runs afterwards and clears the
     * source image again for the affected bands, forcing them back through {@code computeTile}.
     */
    private void revokePassThroughForShiftedSecondaries() {
        for (final Map.Entry<Band, Band> entry : sourceRasterMap.entrySet()) {
            final Band targetBand = entry.getKey();
            final Product srcProduct = entry.getValue().getProduct();
            if (srcProduct == referenceProduct) {
                continue;
            }
            final int[] off = secondaryOffsetMap.get(srcProduct);
            if (off == null || (off[0] == 0 && off[1] == 0)) {
                continue;
            }
            if (targetBand.isSourceImageSet()) {
                targetBand.setSourceImage(null);
                SystemUtils.LOG.info(String.format(
                        "CreateStack: '%s' needs a (%+d, %+d) px shift — dropping the direct "
                                + "source-image pass-through so the offset is actually applied.",
                        targetBand.getName(), off[0], off[1]));
            }
        }
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
