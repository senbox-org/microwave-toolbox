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
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;

import java.awt.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Removes thermal noise from RCM (RADARSAT Constellation Mission) SAR products.
 *
 * Reads NESZ (Noise Equivalent Sigma Zero) data from the noiseLevels.xml
 * metadata embedded in the product. Noise values are stored as LUT vectors
 * indexed by range pixel (matching the calibration LUT structure).
 *
 * The noise is subtracted in the linear power domain:
 *   corrected_power = measured_power - NESZ_linear(range_pixel)
 */
@OperatorMetadata(alias = "RCM-Thermal-Noise-Removal",
        category = "Radar/Radiometric",
        authors = "Luis Veci",
        copyright = "Copyright (C) 2025 by SkyWatch Space Applications Inc.",
        version = "1.0",
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

    @Parameter(description = "Re-introduce thermal noise", defaultValue = "false", label = "Re-Introduce Thermal Noise")
    private Boolean reIntroduceThermalNoise = false;

    @Parameter(description = "Clip negative values after noise removal",
            defaultValue = "true", label = "Clip Negative Values")
    private Boolean clipNegativeValues = true;

    private MetadataElement absRoot = null;
    private MetadataElement origMetadataRoot = null;
    private boolean isComplex = false;
    private int subsetOffsetX = 0;
    private int subsetOffsetY = 0;

    // Per-polarization noise LUT, interpolated to full image width
    private final Map<String, NoiseLUT> noiseLutMap = new HashMap<>();
    private final Map<String, String[]> targetBandNameToSourceBandName = new HashMap<>();

    private static final float FLOOR_VALUE = 1e-5f;

    /**
     * Holds a noise LUT for a single polarization channel.
     * Structure matches the RCM calibration LUT pattern.
     */
    static class NoiseLUT {
        final int pixelFirstValue;
        final int stepSize;
        final double[] values;    // NESZ in linear power scale
        final int imageWidth;

        NoiseLUT(int pixelFirstValue, int stepSize, double[] values, int imageWidth) {
            this.pixelFirstValue = pixelFirstValue;
            this.stepSize = stepSize;
            this.values = values;
            this.imageWidth = imageWidth;
        }

        /**
         * Get the noise value at a given image pixel (range direction).
         * Uses the same integer-division indexing as the calibration LUT.
         */
        double getNoiseValue(int x) {
            int adjustedX = x - pixelFirstValue;
            if (adjustedX < 0) adjustedX = 0;
            int idx = adjustedX / stepSize;
            if (idx >= values.length) idx = values.length - 1;
            return values[idx];
        }
    }

    @Override
    public void initialize() throws OperatorException {
        try {
            absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);
            origMetadataRoot = AbstractMetadata.getOriginalProductMetadata(sourceProduct);

            final String mission = absRoot.getAttributeString(AbstractMetadata.MISSION);
            if (!mission.equalsIgnoreCase("RCM")) {
                throw new OperatorException("This operator only supports RCM products. Found: " + mission);
            }

            subsetOffsetX = absRoot.getAttributeInt(AbstractMetadata.subset_offset_x);
            subsetOffsetY = absRoot.getAttributeInt(AbstractMetadata.subset_offset_y);

            final String sampleType = absRoot.getAttributeString(AbstractMetadata.SAMPLE_TYPE);
            isComplex = sampleType.equalsIgnoreCase("COMPLEX");

            loadNoiseLuts();

            if (noiseLutMap.isEmpty()) {
                SystemUtils.LOG.warning("No RCM noise LUT data found. Noise removal will not be applied.");
            }

            createTargetProduct();

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    /**
     * Load noise LUT vectors from product metadata.
     * Parses the noiseLevels.xml content stored in Original_Product_Metadata.
     *
     * The structure follows the RCM PFD:
     *   noiseLevels/referenceNoiseLevel/sarCalibrationType = "Sigma Nought"
     *   noiseLevels/referenceNoiseLevel/noiseLevelValues/
     *     pixelFirstNoiseValue, stepSize, numberOfValues, noiseLevelValues (space-delimited dB values)
     */
    private void loadNoiseLuts() {
        final int imageWidth = sourceProduct.getSceneRasterWidth();

        // Try from noiseLevels element (added by reader)
        MetadataElement noiseLevelsRoot = origMetadataRoot.getElement("noiseLevels");

        // Also check calibration folder (may be stored there)
        if (noiseLevelsRoot == null) {
            final MetadataElement calibrationElem = origMetadataRoot.getElement("calibration");
            if (calibrationElem != null) {
                noiseLevelsRoot = findElement(calibrationElem, "noiseLevels");
            }
        }

        if (noiseLevelsRoot == null) {
            return;
        }

        // Parse noise levels - may be directly under noiseLevels or in referenceNoiseLevel sub-elements
        parseNoiseLevels(noiseLevelsRoot, imageWidth);
    }

    private void parseNoiseLevels(MetadataElement noiseLevelsElem, int imageWidth) {
        // Try referenceNoiseLevel elements
        for (MetadataElement child : noiseLevelsElem.getElements()) {
            final String childName = child.getName().toLowerCase();

            if (childName.contains("referencenoiseLevel") || childName.contains("referencenoislevel")
                    || childName.contains("noiselevel")) {
                parseReferenceNoiseLevel(child, imageWidth);
            } else if (childName.contains("noiselevelvalues") || childName.contains("noiselut")) {
                // Direct noise values element
                parseNoiseValues(child, null, imageWidth);
            } else {
                // Recurse one level deeper
                for (MetadataElement grandChild : child.getElements()) {
                    final String gcName = grandChild.getName().toLowerCase();
                    if (gcName.contains("referencenoislevel") || gcName.contains("referencenoiseLevel")
                            || gcName.contains("noiselevelvalues")) {
                        parseReferenceNoiseLevel(grandChild, imageWidth);
                    }
                }
            }
        }
    }

    private void parseReferenceNoiseLevel(MetadataElement refNoiseLevel, int imageWidth) {
        // Get calibration type (Sigma Nought, Beta Nought, etc.)
        String calType = refNoiseLevel.getAttributeString("sarCalibrationType", "");

        // Get the noise level values
        MetadataElement valuesElem = refNoiseLevel.getElement("noiseLevelValues");
        if (valuesElem == null) {
            // Values may be directly in this element
            valuesElem = refNoiseLevel;
        }

        // Determine polarization from element name or parent
        String pol = extractPolFromElement(refNoiseLevel);

        parseNoiseValues(valuesElem, pol, imageWidth);
    }

    private void parseNoiseValues(MetadataElement valuesElem, String pol, int imageWidth) {
        try {
            // Parse LUT structure (matching RCM calibration LUT format)
            int pixelFirst = 0;
            int stepSize = 1;

            if (valuesElem.containsAttribute("pixelFirstNoiseValue")) {
                pixelFirst = Integer.parseInt(valuesElem.getAttributeString("pixelFirstNoiseValue"));
            } else if (valuesElem.containsAttribute("pixelFirstLutValue")) {
                pixelFirst = Integer.parseInt(valuesElem.getAttributeString("pixelFirstLutValue"));
            }

            if (valuesElem.containsAttribute("stepSize")) {
                stepSize = Integer.parseInt(valuesElem.getAttributeString("stepSize"));
            }

            // Parse the noise values string
            String noiseLevelStr = null;
            if (valuesElem.containsAttribute("noiseLevelValues")) {
                noiseLevelStr = valuesElem.getAttributeString("noiseLevelValues");
            } else if (valuesElem.containsAttribute("noiseLut")) {
                noiseLevelStr = valuesElem.getAttributeString("noiseLut");
            } else if (valuesElem.containsAttribute("values")) {
                noiseLevelStr = valuesElem.getAttributeString("values");
            }

            // Check nested element
            if (noiseLevelStr == null) {
                MetadataElement nested = valuesElem.getElement("noiseLevelValues");
                if (nested != null && nested.containsAttribute("noiseLevelValues")) {
                    noiseLevelStr = nested.getAttributeString("noiseLevelValues");
                }
            }

            if (noiseLevelStr == null || noiseLevelStr.trim().isEmpty()) {
                return;
            }

            // Determine if values are in dB
            boolean isDB = true;
            String units = valuesElem.getAttributeString("units", "dB");
            if (units.toLowerCase().contains("linear") || units.toLowerCase().contains("power")) {
                isDB = false;
            }

            // Parse space-delimited values
            String[] tokens = noiseLevelStr.trim().split("[\\s,;]+");
            double[] noiseValues = new double[tokens.length];
            for (int i = 0; i < tokens.length; i++) {
                double val = Double.parseDouble(tokens[i]);
                if (isDB) {
                    // Convert dB to linear power: 10^(dB/10)
                    noiseValues[i] = Math.pow(10.0, val / 10.0);
                } else {
                    noiseValues[i] = val;
                }
            }

            if (pol == null) {
                pol = "default";
            }

            NoiseLUT lut = new NoiseLUT(pixelFirst, stepSize, noiseValues, imageWidth);
            noiseLutMap.put(pol.toLowerCase(), lut);
            SystemUtils.LOG.info("RCM: Loaded noise LUT for " + pol + " (" + noiseValues.length + " values, step=" + stepSize + ")");

        } catch (Exception e) {
            SystemUtils.LOG.warning("Error parsing RCM noise values: " + e.getMessage());
        }
    }

    private String extractPolFromElement(MetadataElement elem) {
        String name = elem.getName().toLowerCase();
        // Check parent name too
        MetadataElement parent = elem.getParentElement();
        String parentName = parent != null ? parent.getName().toLowerCase() : "";

        String combined = name + " " + parentName;
        if (combined.contains("_hh") || combined.contains("hh")) return "hh";
        if (combined.contains("_hv") || combined.contains("hv")) return "hv";
        if (combined.contains("_vh") || combined.contains("vh")) return "vh";
        if (combined.contains("_vv") || combined.contains("vv")) return "vv";
        if (combined.contains("_ch") || combined.contains("rch")) return "ch";
        if (combined.contains("_cv") || combined.contains("rcv")) return "cv";
        if (combined.contains("_xc")) return "xc";

        // Check attributes
        if (elem.containsAttribute("pole")) {
            return elem.getAttributeString("pole").toLowerCase();
        }
        if (elem.containsAttribute("polarization")) {
            return elem.getAttributeString("polarization").toLowerCase();
        }

        return null;
    }

    private MetadataElement findElement(MetadataElement parent, String nameContains) {
        for (MetadataElement child : parent.getElements()) {
            if (child.getName().toLowerCase().contains(nameContains.toLowerCase())) {
                return child;
            }
        }
        return null;
    }

    private void createTargetProduct() {
        targetProduct = new Product(sourceProduct.getName(),
                sourceProduct.getProductType(),
                sourceProduct.getSceneRasterWidth(),
                sourceProduct.getSceneRasterHeight());

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        for (Band srcBand : sourceProduct.getBands()) {
            final String unit = srcBand.getUnit();
            if (unit == null) continue;

            if (srcBand instanceof VirtualBand) {
                ProductUtils.copyVirtualBand(targetProduct, (VirtualBand) srcBand, srcBand.getName());
                continue;
            }

            if (unit.contains(Unit.REAL) || unit.contains(Unit.IMAGINARY) ||
                    unit.contains(Unit.AMPLITUDE) || unit.contains(Unit.INTENSITY)) {

                final Band targetBand = ProductUtils.copyBand(srcBand.getName(), sourceProduct, targetProduct, false);
                targetBand.setSourceImage(null);

                if (isComplex && unit.contains(Unit.REAL)) {
                    final String qBandName = srcBand.getName().replace("i_", "q_");
                    targetBandNameToSourceBandName.put(srcBand.getName(),
                            new String[]{srcBand.getName(), qBandName});
                } else if (isComplex && unit.contains(Unit.IMAGINARY)) {
                    final String iBandName = srcBand.getName().replace("q_", "i_");
                    targetBandNameToSourceBandName.put(srcBand.getName(),
                            new String[]{iBandName, srcBand.getName()});
                } else {
                    targetBandNameToSourceBandName.put(srcBand.getName(),
                            new String[]{srcBand.getName()});
                }
            }
        }

        final MetadataElement tgtAbsRoot = AbstractMetadata.getAbstractedMetadata(targetProduct);
        tgtAbsRoot.setAttributeString("thermal_noise_removed", "true");
    }

    /**
     * Get the noise LUT for a given polarization.
     * Falls back to "default" if no pol-specific LUT found.
     */
    private NoiseLUT getNoiseLut(String pol) {
        if (pol != null) {
            NoiseLUT lut = noiseLutMap.get(pol.toLowerCase());
            if (lut != null) return lut;
        }
        // Fallback to first available
        if (!noiseLutMap.isEmpty()) {
            return noiseLutMap.values().iterator().next();
        }
        return null;
    }

    @Override
    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) throws OperatorException {
        final Rectangle rect = targetTile.getRectangle();
        final int x0 = rect.x;
        final int y0 = rect.y;
        final int w = rect.width;
        final int h = rect.height;

        try {
            final String[] srcBandNames = targetBandNameToSourceBandName.get(targetBand.getName());
            if (srcBandNames == null) {
                final Band srcBand = sourceProduct.getBand(targetBand.getName());
                if (srcBand != null) {
                    targetTile.setRawSamples(getSourceTile(srcBand, rect).getRawSamples());
                }
                return;
            }

            final Band sourceBand1 = sourceProduct.getBand(srcBandNames[0]);
            final Tile srcTile1 = getSourceTile(sourceBand1, rect);
            final ProductData srcData1 = srcTile1.getDataBuffer();

            ProductData srcData2 = null;
            if (srcBandNames.length > 1) {
                final Band sourceBand2 = sourceProduct.getBand(srcBandNames[1]);
                if (sourceBand2 != null) {
                    srcData2 = getSourceTile(sourceBand2, rect).getDataBuffer();
                }
            }

            final Unit.UnitType bandUnit = Unit.getUnitType(sourceBand1);
            final String bandPol = OperatorUtils.getPolarizationFromBandName(targetBand.getName());
            final NoiseLUT noiseLut = getNoiseLut(bandPol);

            final ProductData tgtData = targetTile.getDataBuffer();
            final TileIndex srcIndex = new TileIndex(srcTile1);
            final TileIndex tgtIndex = new TileIndex(targetTile);

            final double noDataValue = sourceBand1.getNoDataValue();
            final int maxY = y0 + h;
            final int maxX = x0 + w;
            final int sx0 = subsetOffsetX + x0;

            for (int y = y0; y < maxY; ++y) {
                srcIndex.calculateStride(y);
                tgtIndex.calculateStride(y);

                for (int x = x0; x < maxX; ++x) {
                    final int srcIdx = srcIndex.getIndex(x);
                    final int tgtIdx = tgtIndex.getIndex(x);

                    double dn2;
                    if (bandUnit == Unit.UnitType.AMPLITUDE) {
                        double amp = srcData1.getElemDoubleAt(srcIdx);
                        dn2 = amp * amp;
                    } else if (bandUnit == Unit.UnitType.REAL) {
                        double i = srcData1.getElemDoubleAt(srcIdx);
                        double q = srcData2 != null ? srcData2.getElemDoubleAt(srcIdx) : 0;
                        dn2 = i * i + q * q;
                    } else if (bandUnit == Unit.UnitType.INTENSITY) {
                        dn2 = srcData1.getElemDoubleAt(srcIdx);
                    } else {
                        tgtData.setElemDoubleAt(tgtIdx, srcData1.getElemDoubleAt(srcIdx));
                        continue;
                    }

                    if (dn2 == noDataValue) {
                        tgtData.setElemDoubleAt(tgtIdx, noDataValue);
                        continue;
                    }

                    if (noiseLut == null) {
                        tgtData.setElemDoubleAt(tgtIdx, dn2);
                        continue;
                    }

                    final double noiseValue = noiseLut.getNoiseValue(sx0 + x);

                    double value;
                    if (removeThermalNoise) {
                        if (noiseValue == 0) {
                            // Zero noise = missing data, pass through
                            value = dn2;
                        } else {
                            value = dn2 - noiseValue;
                            if (clipNegativeValues && value < 0) {
                                value = FLOOR_VALUE;
                            }
                        }
                    } else if (reIntroduceThermalNoise) {
                        value = dn2 + noiseValue;
                    } else {
                        value = dn2;
                    }

                    // For complex data, scale the component to preserve phase
                    if (isComplex && bandUnit == Unit.UnitType.REAL && dn2 > 0) {
                        double scale = Math.sqrt(Math.max(value, 0) / dn2);
                        tgtData.setElemDoubleAt(tgtIdx, srcData1.getElemDoubleAt(srcIdx) * scale);
                    } else if (isComplex && bandUnit == Unit.UnitType.IMAGINARY) {
                        // Imaginary component — noise already handled via real component power
                        tgtData.setElemDoubleAt(tgtIdx, srcData1.getElemDoubleAt(srcIdx));
                    } else {
                        tgtData.setElemDoubleAt(tgtIdx, value);
                    }
                }
            }
        } catch (Throwable e) {
            throw new OperatorException(e.getMessage());
        }
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(RCMRemoveThermalNoiseOp.class);
        }
    }
}
