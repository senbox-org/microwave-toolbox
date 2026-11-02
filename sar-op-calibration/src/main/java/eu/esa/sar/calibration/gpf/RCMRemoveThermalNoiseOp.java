/*
 * Copyright (C) 2025 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.calibration.gpf;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.calibration.gpf.support.NoiseSubtraction;
import eu.esa.sar.calibration.gpf.support.RCMNoiseModel;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.TiePointGrid;
import org.esa.snap.core.datamodel.VirtualBand;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Removes thermal noise from RCM (RADARSAT Constellation Mission) products.
 * <p>
 * Noise levels are read from metadata/calibration/noiseLevels_POL.xml. The Beta Nought vectors are the
 * reference; ScanSAR products use the per-beam vectors (selected through grdBurstMap) and the per-beam
 * azimuth noise scaling. Noise is converted to the units of the input:
 * <ul>
 *     <li>DN / amplitude / complex input: noise in |DN|^2 via the lutBeta gains (RCMCalibrator convention)</li>
 *     <li>calibrated Beta0 / Sigma0 / Gamma0 input: beta noise x 1 / sin(theta) / tan(theta)</li>
 * </ul>
 * The noise is subtracted in the linear power domain; complex input produces an intensity band.
 */
@OperatorMetadata(alias = "RCM-Thermal-Noise-Removal",
        category = "Radar/Radiometric",
        authors = "Luis Veci",
        copyright = "Copyright (C) 2025 by SkyWatch Space Applications Inc.",
        version = "1.1",
        description = "Removes thermal noise from RCM products")
public class RCMRemoveThermalNoiseOp extends Operator {

    @SourceProduct
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(description = "The list of polarisations", label = "Polarisations")
    private String[] selectedPolarisations;

    @Parameter(description = "Remove thermal noise", defaultValue = "true", label = "Remove Thermal Noise")
    private Boolean removeThermalNoise = true;

    @Parameter(description = "Output noise", defaultValue = "false", label = "Output Noise")
    private Boolean outputNoise = false;

    @Parameter(description = "Re-introduce thermal noise", defaultValue = "false", label = "Re-Introduce Thermal Noise")
    private Boolean reIntroduceThermalNoise = false;

    @Parameter(description = "Clip negative values after noise removal (disable when data will be spatially or temporally averaged downstream)",
            defaultValue = "true", label = "Clip Negative Values")
    private Boolean clipNegativeValues = true;

    @Parameter(description = "Value assigned to pixels clipped after noise removal (0 = ESA MPC-0392 Eq-2)",
            defaultValue = "1.0E-5", label = "Clip Floor Value")
    private Double clipFloorValue = 1.0E-5;

    private enum InputType {DN, BETA0, SIGMA0, GAMMA0}

    private static final class BandInfo {
        final String pol;
        final String[] sourceBands;   // one band, or i/q pair
        final InputType inputType;
        final boolean isNoiseBand;

        BandInfo(String pol, String[] sourceBands, InputType inputType, boolean isNoiseBand) {
            this.pol = pol;
            this.sourceBands = sourceBands;
            this.inputType = inputType;
            this.isNoiseBand = isNoiseBand;
        }
    }

    private MetadataElement origMetadataRoot;
    private boolean isComplex;
    private boolean complexCalibration;   // |DN|^2 / A^2 convention (SLC, GRC, GCC), also after detection
    private boolean calibratedInput;
    private int subsetOffsetX;
    private int subsetOffsetY;
    private TiePointGrid incidenceAngle;
    private final Map<String, RCMNoiseModel> models = new HashMap<>();   // key: upper-case pol
    private final Map<String, BandInfo> targetBandInfo = new HashMap<>();

    @Override
    public void initialize() throws OperatorException {
        try {
            final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);
            origMetadataRoot = AbstractMetadata.getOriginalProductMetadata(sourceProduct);

            final String mission = absRoot.getAttributeString(AbstractMetadata.MISSION);
            if (!mission.equalsIgnoreCase("RCM")) {
                throw new OperatorException("This operator only supports RCM products. Found: " + mission);
            }
            if (removeThermalNoise && reIntroduceThermalNoise) {
                throw new OperatorException("Select either remove or re-introduce thermal noise");
            }
            final boolean noiseRemoved = readNoiseSubtractionFlag();
            if (removeThermalNoise && noiseRemoved) {
                throw new OperatorException("Thermal noise has already been removed");
            }
            if (reIntroduceThermalNoise && !noiseRemoved) {
                throw new OperatorException("Thermal noise has not been removed");
            }

            if (isMLC(absRoot)) {
                throw new OperatorException("RCM MLC products are not supported");
            }

            new InputProductValidator(sourceProduct).checkIfMapProjected(false);
            checkRasterNotResampled(absRoot);
            checkNotSubsampled(sourceProduct.getMetadataRoot().getElement(Product.HISTORY_ROOT_NAME));

            subsetOffsetX = absRoot.getAttributeInt(AbstractMetadata.subset_offset_x);
            subsetOffsetY = absRoot.getAttributeInt(AbstractMetadata.subset_offset_y);
            isComplex = absRoot.getAttributeString(AbstractMetadata.SAMPLE_TYPE).equalsIgnoreCase("COMPLEX");
            // an SLC/GRC detected by this operator keeps the complex calibration convention (RCMCalibrator agrees)
            complexCalibration = isComplex || RCMNoiseModel.isComplexProductType(sourceProduct.getProductType());
            calibratedInput = absRoot.getAttributeInt(AbstractMetadata.abs_calibration_flag, 0) != 0;
            if (isComplex && calibratedInput) {
                throw new OperatorException("RCM thermal noise removal does not support calibrated complex input; " +
                        "apply it before Calibration");
            }
            if (clipFloorValue == null || clipFloorValue.isNaN() || clipFloorValue.isInfinite() || clipFloorValue < 0) {
                throw new OperatorException("Clip floor value must be a finite, non-negative number");
            }

            final Map<String, BandInfo> imageBands = findImageBands();
            loadModels(imageBands);
            createTargetProduct(imageBands);
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private boolean readNoiseSubtractionFlag() {
        final MetadataElement spi = getSarProcessingInformation(origMetadataRoot);
        return spi != null && Boolean.parseBoolean(spi.getAttributeString("noiseSubtractionPerformed", "false"));
    }

    /**
     * The noise and calibration LUTs are given on the delivered raster; a multilooked or resampled product
     * changes the pixel spacing recorded in the abstracted metadata.
     */
    private void checkRasterNotResampled(final MetadataElement absRoot) {
        final MetadataElement product = origMetadataRoot == null ? null : origMetadataRoot.getElement("product");
        final MetadataElement reference = product == null ? null : product.getElement("imageReferenceAttributes");
        final MetadataElement raster = reference == null ? null : reference.getElement("rasterAttributes");
        final double pixelSpacing = readValue(raster, "sampledPixelSpacing");
        final double lineSpacing = readValue(raster, "sampledLineSpacing");
        if (differs(absRoot.getAttributeDouble(AbstractMetadata.range_spacing, 0), pixelSpacing) ||
                differs(absRoot.getAttributeDouble(AbstractMetadata.azimuth_spacing, 0), lineSpacing)) {
            throw new OperatorException("Thermal noise removal must be applied before multilooking or resampling");
        }
    }

    /** A product.xml value: a child element (leaf with a units attribute) or a plain attribute; 0 when absent. */
    private static double readValue(final MetadataElement parent, final String name) {
        if (parent == null) {
            return 0;
        }
        final MetadataElement child = parent.getElement(name);
        if (child != null && child.containsAttribute(name)) {
            return child.getAttributeDouble(name);
        }
        return parent.containsAttribute(name) ? parent.getAttributeDouble(name) : 0;
    }

    /**
     * A subsampled subset keeps the abstracted pixel spacing and subset offsets of the full-resolution raster, so
     * the spacing check cannot see it; the subset builder records the subsampling in the history (SubsetInfo,
     * with earlier subsets nested inside).
     */
    private static void checkNotSubsampled(final MetadataElement elem) {
        if (elem == null) {
            return;
        }
        if (elem.getAttributeInt("SubSampling.x", 1) != 1 || elem.getAttributeInt("SubSampling.y", 1) != 1) {
            throw new OperatorException("Thermal noise removal must be applied before subsampling; " +
                    "use a subset without subsampling");
        }
        for (MetadataElement child : elem.getElements()) {
            checkNotSubsampled(child);
        }
    }

    /** MLC covariance products (C11, C12_real, C12_imag, C22) have no per-channel noise model here. */
    private boolean isMLC(final MetadataElement absRoot) {
        if ("MLC".equalsIgnoreCase(sourceProduct.getProductType()) ||
                "MLC".equalsIgnoreCase(absRoot.getAttributeString(AbstractMetadata.PRODUCT_TYPE, "").trim())) {
            return true;
        }
        for (String name : sourceProduct.getBandNames()) {
            if (name.matches("C\\d\\d(_.*)?")) {
                return true;
            }
        }
        return false;
    }

    private static boolean differs(final double a, final double b) {
        return a > 0 && b > 0 && Math.abs(a - b) > 1e-3 * b;
    }

    private static MetadataElement getSarProcessingInformation(final MetadataElement origRoot) {
        final MetadataElement product = origRoot == null ? null : origRoot.getElement("product");
        final MetadataElement igp = product == null ? null : product.getElement("imageGenerationParameters");
        return igp == null ? null : igp.getElement("sarProcessingInformation");
    }

    private static String getPol(final String bandName) {
        return bandName.substring(bandName.lastIndexOf('_') + 1).toUpperCase();
    }

    /** Target band name -> description of the source band(s), for the selected polarisations. */
    private Map<String, BandInfo> findImageBands() {
        final List<String> selected = selectedPolarisations == null || selectedPolarisations.length == 0 ? null :
                Arrays.stream(selectedPolarisations).map(String::toUpperCase).toList();
        final Map<String, BandInfo> result = new LinkedHashMap<>();
        for (Band srcBand : sourceProduct.getBands()) {
            if (srcBand instanceof VirtualBand || srcBand.getUnit() == null) {
                continue;
            }
            final String name = srcBand.getName();
            final String unit = srcBand.getUnit();
            final String pol = getPol(name);
            if (selected != null && !selected.contains(pol)) {
                continue;
            }
            if (unit.contains(Unit.REAL)) {
                final Band qBand = findComplexPartner(pol);
                result.put("Intensity_" + pol, new BandInfo(pol, new String[]{name, qBand.getName()}, InputType.DN, false));
            } else if (!unit.contains(Unit.DB) && (unit.contains(Unit.AMPLITUDE) || unit.contains(Unit.INTENSITY))) {
                final InputType type = getCalibratedType(name);
                if (type == InputType.DN && calibratedInput) {
                    throw new OperatorException("Band " + name + " of a calibrated product is not named Sigma0, " +
                            "Gamma0 or Beta0; its calibration type cannot be determined");
                }
                final String targetName = type == InputType.DN ? "Intensity_" + pol : name;
                result.put(targetName, new BandInfo(pol, new String[]{name}, type, false));
            }
        }
        if (result.isEmpty()) {
            throw new OperatorException("No amplitude, intensity or complex bands found");
        }
        return result;
    }

    private Band findComplexPartner(final String pol) {
        for (Band b : sourceProduct.getBands()) {
            if (b.getUnit() != null && b.getUnit().contains(Unit.IMAGINARY) && getPol(b.getName()).equals(pol)) {
                return b;
            }
        }
        throw new OperatorException("No imaginary band found for polarisation " + pol);
    }

    private static InputType getCalibratedType(final String bandName) {
        if (bandName.startsWith("Sigma0")) return InputType.SIGMA0;
        if (bandName.startsWith("Gamma0")) return InputType.GAMMA0;
        if (bandName.startsWith("Beta0")) return InputType.BETA0;
        return InputType.DN;
    }

    private void loadModels(final Map<String, BandInfo> imageBands) {
        final MetadataElement cal = origMetadataRoot == null ? null : origMetadataRoot.getElement("calibration");
        final MetadataElement product = origMetadataRoot == null ? null : origMetadataRoot.getElement("product");
        final MetadataElement burstMap = product == null || complexCalibration ? null : product.getElement("grdBurstMap");
        for (BandInfo info : imageBands.values()) {
            if (models.containsKey(info.pol)) {
                continue;
            }
            // compact-pol bands are named RCH/RCV, their calibration files CH/CV
            final String filePol = info.pol.startsWith("RC") ? info.pol.substring(1) : info.pol;
            final MetadataElement noise = cal == null ? null : cal.getElement("noiseLevels_" + filePol);
            final MetadataElement lut = cal == null ? null : cal.getElement("lutBeta_" + filePol);
            if (noise == null || lut == null) {
                final boolean legacy = cal != null && cal.getElement("noiseLevels") != null;
                throw new OperatorException("No noise levels for polarisation " + info.pol +
                        (legacy ? "; the product was saved with an older RCM reader, re-open the original RCM product" : ""));
            }
            if (isComplex && noise.getElement("referenceNoiseLevel") == null) {
                throw new OperatorException("RCM ScanSAR SLC noise removal is not supported");
            }
            models.put(info.pol, RCMNoiseModel.parse(noise, lut, burstMap, complexCalibration));
        }
        for (BandInfo info : imageBands.values()) {
            if (info.inputType == InputType.SIGMA0 || info.inputType == InputType.GAMMA0) {
                incidenceAngle = OperatorUtils.getIncidenceAngle(sourceProduct);
                if (incidenceAngle == null) {
                    throw new OperatorException("Incidence angle tie-point grid is required for calibrated input");
                }
                break;
            }
        }
    }

    private void createTargetProduct(final Map<String, BandInfo> imageBands) {
        targetProduct = new Product(sourceProduct.getName(), sourceProduct.getProductType(),
                sourceProduct.getSceneRasterWidth(), sourceProduct.getSceneRasterHeight());

        for (Map.Entry<String, BandInfo> e : imageBands.entrySet()) {
            final BandInfo info = e.getValue();
            final Band srcBand = sourceProduct.getBand(info.sourceBands[0]);
            addTargetBand(e.getKey(), info, Unit.INTENSITY, srcBand);
            // clipped pixels must not be taken for no-data (clip floor 0 with no-data 0, ESA Eq-2)
            if (clipNegativeValues && clipFloorValue == srcBand.getNoDataValue()) {
                targetProduct.getBand(e.getKey()).setNoDataValue(Double.NaN);
            }
            targetBandInfo.put(e.getKey(), info);

            if (outputNoise) {
                final String noiseName = noiseBandPrefix(info.inputType) + "_" + info.pol;
                final BandInfo noiseInfo = new BandInfo(info.pol, info.sourceBands, info.inputType, true);
                addTargetBand(noiseName, noiseInfo, Unit.DB, srcBand);
                // the noise band has no source no-data; 0 dB is a valid noise level
                targetProduct.getBand(noiseName).setNoDataValue(Double.NaN);
                targetBandInfo.put(noiseName, noiseInfo);
            }
        }

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        if (isComplex) {
            // complex input is written as intensity
            AbstractMetadata.getAbstractedMetadata(targetProduct).setAttributeString(AbstractMetadata.SAMPLE_TYPE, "DETECTED");
        }

        final MetadataElement spi = getSarProcessingInformation(AbstractMetadata.getOriginalProductMetadata(targetProduct));
        if (spi != null && (removeThermalNoise || reIntroduceThermalNoise)) {
            spi.setAttributeString("noiseSubtractionPerformed", String.valueOf(removeThermalNoise));
        }
    }

    private void addTargetBand(final String name, final BandInfo info, final String unit, final Band srcBand) {
        final Band band = new Band(name, ProductData.TYPE_FLOAT32, srcBand.getRasterWidth(), srcBand.getRasterHeight());
        band.setUnit(unit);
        band.setDescription(srcBand.getDescription());
        band.setNoDataValue(srcBand.getNoDataValue());
        band.setNoDataValueUsed(true);
        targetProduct.addBand(band);
    }

    private static String noiseBandPrefix(final InputType type) {
        switch (type) {
            case BETA0:
                return Sentinel1RemoveThermalNoiseOp.NOISE_EQUIVALENT_BETA_ZERO;
            case SIGMA0:
                return Sentinel1RemoveThermalNoiseOp.NOISE_EQUIVALENT_SIGMA_ZERO;
            case GAMMA0:
                return Sentinel1RemoveThermalNoiseOp.NOISE_EQUIVALENT_GAMMA_ZERO;
            default:
                return Sentinel1RemoveThermalNoiseOp.NOISE_EQUIVALENT_POWER;
        }
    }

    @Override
    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) throws OperatorException {
        final Rectangle rect = targetTile.getRectangle();
        final int x0 = rect.x;
        final int y0 = rect.y;
        final int maxX = x0 + rect.width;
        final int maxY = y0 + rect.height;

        try {
            final BandInfo info = targetBandInfo.get(targetBand.getName());
            final RCMNoiseModel model = models.get(info.pol);
            final double tgtNoData = targetBand.getNoDataValue();
            final ProductData tgtData = targetTile.getDataBuffer();
            final TileIndex tgtIndex = new TileIndex(targetTile);

            if (info.isNoiseBand) {
                // the noise depends on position only: no source I/O
                for (int y = y0; y < maxY; ++y) {
                    tgtIndex.calculateStride(y);
                    for (int x = x0; x < maxX; ++x) {
                        final double noise = noiseAt(info.inputType, model, x, y);
                        tgtData.setElemDoubleAt(tgtIndex.getIndex(x), noise > 0 ? 10.0 * Math.log10(noise) : tgtNoData);
                    }
                }
                return;
            }

            final Band sourceBand1 = sourceProduct.getBand(info.sourceBands[0]);
            final Tile srcTile1 = getSourceTile(sourceBand1, rect);
            final ProductData srcData1 = srcTile1.getDataBuffer();
            final ProductData srcData2 = info.sourceBands.length > 1 ?
                    getSourceTile(sourceProduct.getBand(info.sourceBands[1]), rect).getDataBuffer() : null;
            final Unit.UnitType unitType = Unit.getUnitType(sourceBand1);
            final double srcNoData = sourceBand1.getNoDataValue();
            final boolean srcNoDataUsed = sourceBand1.isNoDataValueUsed();
            final TileIndex srcIndex = new TileIndex(srcTile1);

            for (int y = y0; y < maxY; ++y) {
                srcIndex.calculateStride(y);
                tgtIndex.calculateStride(y);
                for (int x = x0; x < maxX; ++x) {
                    final int tgtIdx = tgtIndex.getIndex(x);
                    final int srcIdx = srcIndex.getIndex(x);
                    final double power = power(srcData1, srcData2, srcIdx, unitType);
                    if (Double.isNaN(power) || isNoData(srcData1, srcData2, srcIdx, srcNoDataUsed, srcNoData)) {
                        tgtData.setElemDoubleAt(tgtIdx, tgtNoData);
                        continue;
                    }
                    if (!removeThermalNoise && !reIntroduceThermalNoise) {
                        tgtData.setElemDoubleAt(tgtIdx, power);
                        continue;
                    }
                    final double noise = noiseAt(info.inputType, model, x, y);
                    tgtData.setElemDoubleAt(tgtIdx, NoiseSubtraction.apply(power,
                            reIntroduceThermalNoise ? -noise : noise, clipNegativeValues, clipFloorValue));
                }
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    /** Noise at target pixel (x, y) in the units of the input; the model is indexed on the full image. */
    private double noiseAt(final InputType type, final RCMNoiseModel model, final int x, final int y) {
        final int xf = subsetOffsetX + x;
        final int yf = subsetOffsetY + y;
        return type == InputType.DN ? model.noiseDN2(xf, yf) : model.betaNoise(xf, yf) * geometryFactor(type, x, y);
    }

    /** No-data only when every component equals the no-data value (I == 0 alone is a valid complex sample). */
    private static boolean isNoData(final ProductData d1, final ProductData d2, final int idx, final boolean used,
                                    final double noData) {
        if (!used) {
            return false;
        }
        return d1.getElemDoubleAt(idx) == noData && (d2 == null || d2.getElemDoubleAt(idx) == noData);
    }

    private double geometryFactor(final InputType type, final int x, final int y) {
        if (type == InputType.BETA0) {
            return 1.0;
        }
        final double theta = incidenceAngle.getPixelDouble(x + 0.5, y + 0.5) * Constants.DTOR;
        return type == InputType.SIGMA0 ? Math.sin(theta) : Math.tan(theta);
    }

    private static double power(final ProductData d1, final ProductData d2, final int idx, final Unit.UnitType unit) {
        if (unit == Unit.UnitType.AMPLITUDE) {
            final double a = d1.getElemDoubleAt(idx);
            return a * a;
        } else if (unit == Unit.UnitType.REAL) {
            final double i = d1.getElemDoubleAt(idx);
            final double q = d2.getElemDoubleAt(idx);
            return i * i + q * q;
        } else if (unit == Unit.UnitType.INTENSITY) {
            return d1.getElemDoubleAt(idx);
        }
        throw new OperatorException("Unhandled unit " + unit);
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(RCMRemoveThermalNoiseOp.class);
        }
    }
}
