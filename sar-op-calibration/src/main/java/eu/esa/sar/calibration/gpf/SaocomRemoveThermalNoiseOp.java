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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Removes thermal noise from SAOCOM SAR products.
 *
 * Reads NESZ (Noise Equivalent Sigma Zero) vectors from the product's
 * noiseLut XML files and subtracts the noise power from each pixel.
 * Operates in linear power domain.
 */
@OperatorMetadata(alias = "SAOCOM-Thermal-Noise-Removal",
        category = "Radar/Radiometric",
        authors = "Luis Veci",
        copyright = "Copyright (C) 2025 by SkyWatch Space Applications Inc.",
        version = "1.0",
        description = "Removes thermal noise from SAOCOM products")
public class SaocomRemoveThermalNoiseOp extends Operator {

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
    private boolean isComplex = false;
    private int imageWidth = 0;

    // Per-polarization noise LUT vectors, interpolated to image width
    private final Map<String, double[]> noiseLuts = new HashMap<>();
    private final Map<String, String[]> targetBandNameToSourceBandName = new HashMap<>();

    private static final float FLOOR_VALUE = 1e-5f;

    /** Abstracted-metadata flag written by this operator: "true" after removal, "false" after re-introduction. */
    static final String NOISE_REMOVED_FLAG = "thermal_noise_removed";

    @Override
    public void initialize() throws OperatorException {
        try {
            absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);

            final String mission = absRoot.getAttributeString(AbstractMetadata.MISSION);
            if (!mission.equalsIgnoreCase("SAOCOM")) {
                throw new OperatorException("This operator only supports SAOCOM products. Found: " + mission);
            }
            if (removeThermalNoise && reIntroduceThermalNoise) {
                throw new OperatorException("Select either remove or re-introduce thermal noise");
            }
            final boolean noiseRemoved = Boolean.parseBoolean(absRoot.getAttributeString(NOISE_REMOVED_FLAG, "false"));
            if (removeThermalNoise && noiseRemoved) {
                throw new OperatorException("Thermal noise has already been removed");
            }
            if (reIntroduceThermalNoise && !noiseRemoved) {
                throw new OperatorException("Thermal noise has not been removed");
            }

            imageWidth = sourceProduct.getSceneRasterWidth();

            final String sampleType = absRoot.getAttributeString(AbstractMetadata.SAMPLE_TYPE);
            isComplex = sampleType.equalsIgnoreCase("COMPLEX");

            loadNoiseLuts();

            if (noiseLuts.isEmpty()) {
                SystemUtils.LOG.warning("No SAOCOM noise LUT data found. Noise removal will not be applied.");
            }

            createTargetProduct();

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    /**
     * Load noise LUT vectors from product metadata.
     * Parses noiseLut XML content stored in Abstracted_Metadata/noise/.
     */
    private void loadNoiseLuts() {
        final MetadataElement noiseElem = absRoot.getElement("noise");
        if (noiseElem == null) return;

        for (MetadataElement noiseLutFile : noiseElem.getElements()) {
            try {
                final String pol = extractPolFromName(noiseLutFile.getName());
                if (pol == null) continue;

                final float[] neszVector = parseNoiseVector(noiseLutFile);
                if (neszVector != null && neszVector.length > 0) {
                    // Interpolate to full image width
                    double[] interpolatedNoise = interpolateToImageWidth(neszVector, imageWidth);
                    noiseLuts.put(pol.toUpperCase(), interpolatedNoise);
                    SystemUtils.LOG.info("SAOCOM: Loaded noise LUT for " + pol + " (" + neszVector.length + " samples)");
                }
            } catch (Exception e) {
                SystemUtils.LOG.warning("Error parsing SAOCOM noise LUT: " + noiseLutFile.getName() + " - " + e.getMessage());
            }
        }
    }

    /**
     * Parse noise vector from metadata element.
     * Tries multiple XML structures.
     */
    private float[] parseNoiseVector(MetadataElement elem) {
        // Try direct attribute names
        float[] values = tryParseVector(elem, "noiseLut");
        if (values == null) values = tryParseVector(elem, "NoiseLut");
        if (values == null) values = tryParseVector(elem, "nesz");
        if (values == null) values = tryParseVector(elem, "NESZ");
        if (values == null) values = tryParseVector(elem, "noiseRangeVector");
        if (values == null) values = tryParseVector(elem, "values");

        // Try nested elements
        if (values == null) {
            for (MetadataElement child : elem.getElements()) {
                values = tryParseVector(child, "noiseLut");
                if (values == null) values = tryParseVector(child, "NoiseLut");
                if (values == null) values = tryParseVector(child, "nesz");
                if (values == null) values = tryParseVector(child, "values");
                if (values != null) break;
            }
        }

        return values;
    }

    private float[] tryParseVector(MetadataElement elem, String attrName) {
        if (elem.containsAttribute(attrName)) {
            return parseFloatString(elem.getAttributeString(attrName, ""));
        }
        MetadataElement subElem = elem.getElement(attrName);
        if (subElem != null && subElem.containsAttribute(attrName)) {
            return parseFloatString(subElem.getAttributeString(attrName, ""));
        }
        return null;
    }

    private float[] parseFloatString(String str) {
        if (str == null || str.trim().isEmpty()) return null;
        try {
            String[] tokens = str.trim().split("[\\s,;]+");
            if (tokens.length < 2) return null;
            float[] values = new float[tokens.length];
            for (int i = 0; i < tokens.length; i++) {
                values[i] = Float.parseFloat(tokens[i]);
            }
            return values;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Linearly interpolate a noise vector to the full image width.
     */
    private double[] interpolateToImageWidth(float[] vector, int width) {
        double[] result = new double[width];
        if (vector.length == 1) {
            java.util.Arrays.fill(result, vector[0]);
            return result;
        }
        for (int x = 0; x < width; x++) {
            double lutIdx = (double) x / (width - 1) * (vector.length - 1);
            int idx0 = Math.min((int) lutIdx, vector.length - 2);
            double frac = lutIdx - idx0;
            result[x] = (1.0 - frac) * vector[idx0] + frac * vector[idx0 + 1];
        }
        return result;
    }

    private String extractPolFromName(String filename) {
        String upper = filename.toUpperCase();
        for (String pol : new String[]{"HH", "HV", "VH", "VV", "RCH", "RCV"}) {
            if (upper.contains(pol)) return pol;
        }
        return null;
    }

    private void createTargetProduct() {
        targetProduct = new Product(sourceProduct.getName(),
                sourceProduct.getProductType(),
                sourceProduct.getSceneRasterWidth(),
                sourceProduct.getSceneRasterHeight());

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        final List<String> selected = selectedPolarisations == null || selectedPolarisations.length == 0 ? null :
                Arrays.stream(selectedPolarisations).map(String::toUpperCase).toList();
        boolean noiseApplied = false;

        for (Band srcBand : sourceProduct.getBands()) {
            final String unit = srcBand.getUnit();
            if (unit == null) continue;

            final String pol = OperatorUtils.getPolarizationFromBandName(srcBand.getName());
            if (selected != null && (pol == null || !selected.contains(pol.toUpperCase()))) {
                continue;
            }

            if (srcBand instanceof VirtualBand) {
                ProductUtils.copyVirtualBand(targetProduct, (VirtualBand) srcBand, srcBand.getName());
                continue;
            }

            if (unit.contains(Unit.REAL) || unit.contains(Unit.IMAGINARY) ||
                    unit.contains(Unit.AMPLITUDE) || unit.contains(Unit.INTENSITY)) {

                final Band targetBand = ProductUtils.copyBand(srcBand.getName(), sourceProduct, targetProduct, false);
                targetBand.setSourceImage(null);
                if (pol != null && noiseLuts.containsKey(pol.toUpperCase())) {
                    noiseApplied = true;
                }

                if (isComplex) {
                    // Complex bands come in I/Q pairs
                    if (unit.contains(Unit.REAL)) {
                        // Find matching Q band
                        final String qBandName = srcBand.getName().replace("i_", "q_");
                        final Band qBand = sourceProduct.getBand(qBandName);
                        if (qBand != null) {
                            targetBandNameToSourceBandName.put(srcBand.getName(),
                                    new String[]{srcBand.getName(), qBandName});
                        } else {
                            targetBandNameToSourceBandName.put(srcBand.getName(),
                                    new String[]{srcBand.getName()});
                        }
                    } else if (unit.contains(Unit.IMAGINARY)) {
                        // Q band handled via I band entry
                        final String iBandName = srcBand.getName().replace("q_", "i_");
                        targetBandNameToSourceBandName.put(srcBand.getName(),
                                new String[]{iBandName, srcBand.getName()});
                    }
                } else {
                    targetBandNameToSourceBandName.put(srcBand.getName(),
                            new String[]{srcBand.getName()});
                }
            }
        }

        if (selected != null && targetBandNameToSourceBandName.isEmpty()) {
            throw new OperatorException("No bands found for the selected polarisations " + selected);
        }

        // Record the noise state only when a noise LUT was actually applied
        if (noiseApplied && (removeThermalNoise || reIntroduceThermalNoise)) {
            final MetadataElement tgtAbsRoot = AbstractMetadata.getAbstractedMetadata(targetProduct);
            tgtAbsRoot.setAttributeString(NOISE_REMOVED_FLAG, String.valueOf(removeThermalNoise));
        }
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
                // Non-data band (e.g., virtual), copy directly
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
                srcData2 = getSourceTile(sourceBand2, rect).getDataBuffer();
            }

            // Unit of the band being written: a q target band reads {i, q} but must write q
            final Unit.UnitType bandUnit = Unit.getUnitType(targetBand);
            final boolean complexComponent = bandUnit == Unit.UnitType.REAL || bandUnit == Unit.UnitType.IMAGINARY;
            // i, q and amplitude are amplitude-domain samples: the noise is handled in power, and the sample
            // is then scaled by sqrt(P'/P) so the band keeps its unit (amplitude in, amplitude out)
            final boolean scaledComponent = complexComponent || bandUnit == Unit.UnitType.AMPLITUDE;
            final String bandPol = OperatorUtils.getPolarizationFromBandName(targetBand.getName());
            final double[] noiseLut = bandPol != null ? noiseLuts.get(bandPol.toUpperCase()) : null;

            final ProductData tgtData = targetTile.getDataBuffer();
            final TileIndex srcIndex = new TileIndex(srcTile1);
            final TileIndex tgtIndex = new TileIndex(targetTile);

            final double noDataValue = sourceBand1.getNoDataValue();
            final int maxY = y0 + h;
            final int maxX = x0 + w;

            for (int y = y0; y < maxY; ++y) {
                srcIndex.calculateStride(y);
                tgtIndex.calculateStride(y);

                for (int x = x0; x < maxX; ++x) {
                    final int srcIdx = srcIndex.getIndex(x);
                    final int tgtIdx = tgtIndex.getIndex(x);

                    double dn2;
                    double component = 0;   // the i, q or amplitude sample of this target band
                    // The source sample itself (amplitude, i or intensity) is tested against no-data, never the
                    // derived power: a fill of -9999 has amp^2 = 99980001 and would otherwise be processed as data
                    final double sample = srcData1.getElemDoubleAt(srcIdx);

                    if (bandUnit == Unit.UnitType.AMPLITUDE) {
                        component = sample;
                        dn2 = component * component;
                    } else if (complexComponent) {
                        double i = sample;
                        double q = srcData2 != null ? srcData2.getElemDoubleAt(srcIdx) : 0;
                        component = bandUnit == Unit.UnitType.IMAGINARY ? q : i;
                        dn2 = i * i + q * q;
                    } else if (bandUnit == Unit.UnitType.INTENSITY) {
                        dn2 = sample;
                    } else {
                        tgtData.setElemDoubleAt(tgtIdx, sample);
                        continue;
                    }

                    if (sample == noDataValue) {
                        tgtData.setElemDoubleAt(tgtIdx, noDataValue);
                        continue;
                    }

                    if (noiseLut == null || noiseLut.length == 0) {
                        // No noise data available — pass through
                        tgtData.setElemDoubleAt(tgtIdx, scaledComponent ? component : dn2);
                        continue;
                    }

                    final double noiseValue = noiseLut[Math.min(x, noiseLut.length - 1)];

                    double value;
                    if (removeThermalNoise) {
                        value = dn2 - noiseValue;
                        if (clipNegativeValues && value < 0) {
                            value = FLOOR_VALUE;
                        }
                    } else if (reIntroduceThermalNoise) {
                        value = dn2 + noiseValue;
                    } else {
                        value = dn2;
                    }

                    // For complex data, preserve the phase: i and q are both scaled by the same
                    // real factor sqrt(|z'|^2 / |z|^2), so only the amplitude changes.
                    // For amplitude bands the same factor gives amp' = sqrt(max(P', 0)), with the
                    // clip floor already applied in power; amp = 0 gives 0.
                    if (scaledComponent) {
                        final double scale = dn2 > 0 ? Math.sqrt(Math.max(value, 0) / dn2) : 0;
                        tgtData.setElemDoubleAt(tgtIdx, component * scale);
                    } else {
                        tgtData.setElemDoubleAt(tgtIdx, value);
                    }
                }
            }
        } catch (Throwable e) {
            throw new OperatorException(e.getMessage());
        }
    }

    /**
     * The SPI is used to register this operator in the graph processing framework
     * via the SPI configuration file
     * {@code META-INF/services/org.esa.snap.core.gpf.OperatorSpi}.
     */
    public static class Spi extends OperatorSpi {
        public Spi() {
            super(SaocomRemoveThermalNoiseOp.class);
        }
    }
}
