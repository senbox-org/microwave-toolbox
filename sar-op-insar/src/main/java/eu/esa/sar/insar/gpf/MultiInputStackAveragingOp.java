/*
 * Copyright (C) 2016 by Array Systems Computing Inc. http://www.array.ca
 * Copyright (C) 2026 by SkyWatch Space Applications Inc. https://www.skywatch.com
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

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.VirtualBand;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProducts;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.StackUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Multi-input stack averaging.
 *
 * Takes N independent {@link Product}s of identical raster dimensions, stacks
 * them in-memory (band names disambiguated with a per-product index suffix),
 * and emits a virtual band per (band-name-without-date) group with one of:
 * Mean Average, Minimum, Maximum, Standard Deviation, Coefficient of Variation.
 *
 * Complementary to {@link StackAveragingOp}, which performs the same
 * reduction over a single, already-coregistered stack product.
 *
 * Note: the Coefficient of Variation expression here is
 *   sqrt(mean(x^4) - mean(x^2)^2) / mean(x^2)
 * which is the CV of *intensity* (x^2), not of amplitude. This matches the
 * 2016 Array Systems definition and is the SAR-physical interpretation when
 * inputs are amplitudes. If you need CV of amplitude, divide stddev by mean
 * outside the operator.
 */
@OperatorMetadata(alias = "Multi-Input-Stack-Averaging",
        category = "Radar/Coregistration/Stack Tools",
        authors = "Jun Lu, Luis Veci",
        version = "1.0",
        copyright = "Copyright (C) 2016 by Array Systems Computing Inc.",
        description = "Aggregate multiple co-located products into a single statistic image (mean/min/max/stddev/CV).")
public class MultiInputStackAveragingOp extends Operator {

    @SourceProducts
    private Product[] sourceProducts;

    @TargetProduct
    private Product targetProduct;

    @Parameter(valueSet = {"Mean Average", "Minimum", "Maximum", "Standard Deviation", "Coefficient of Variation"},
            defaultValue = "Mean Average", label = "Statistic")
    private String statistic = "Mean Average";

    private BandInfo[] nameGroups;
    private Product sourceProduct = null;

    @Override
    public void initialize() throws OperatorException {

        try {
            validateSourceProducts();

            createSourceProductStack();

            targetProduct = new Product(sourceProduct.getName(),
                    sourceProduct.getProductType(),
                    sourceProduct.getSceneRasterWidth(),
                    sourceProduct.getSceneRasterHeight());

            ProductUtils.copyProductNodes(sourceProduct, targetProduct);

            nameGroups = getBandGroupNames();

            for (BandInfo bandInfo : nameGroups) {
                if (bandInfo.isVirtual) {
                    addOriginalVirtualBands(bandInfo.name);
                } else {
                    final String namePrefix = bandInfo.name;
                    final Band[] sourceBands = getSourceBands(namePrefix);
                    final String unit = sourceBands[0].getUnit();
                    final double noDataValue = sourceBands[0].getNoDataValue();

                    switch (statistic) {
                        case "Mean Average":
                            addVirtualBand("average", namePrefix, mean(sourceBands), unit, noDataValue);
                            break;
                        case "Minimum":
                            addVirtualBand("min", namePrefix, min(sourceBands), unit, noDataValue);
                            break;
                        case "Maximum":
                            addVirtualBand("max", namePrefix, max(sourceBands), unit, noDataValue);
                            break;
                        case "Standard Deviation":
                            addVirtualBand("stddev", namePrefix, stddev(sourceBands), unit, noDataValue);
                            break;
                        case "Coefficient of Variation":
                            addVirtualBand("coefVar", namePrefix, coefVar(sourceBands), unit, noDataValue);
                            break;
                        default:
                            throw new OperatorException("Unknown statistic: " + statistic);
                    }
                }
            }

            updateMetadata(targetProduct);
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    @Override
    public void dispose() {
        if (nameGroups == null || sourceProduct == null) {
            return;
        }
        for (BandInfo bandInfo : nameGroups) {
            final Band srcBand = sourceProduct.getBand(bandInfo.name);
            if (srcBand != null) {
                sourceProduct.removeBand(srcBand);
            }
        }
        sourceProduct.setModified(false);
    }

    private void validateSourceProducts() {

        if (sourceProducts == null || sourceProducts.length < 2) {
            throw new OperatorException("Please select at least two source products");
        }

        final int width = sourceProducts[0].getSceneRasterWidth();
        final int height = sourceProducts[0].getSceneRasterHeight();

        for (int i = 1; i < sourceProducts.length; i++) {
            if (sourceProducts[i].getSceneRasterWidth() != width ||
                    sourceProducts[i].getSceneRasterHeight() != height) {
                throw new OperatorException("Please select source products of the same dimension");
            }
        }
    }

    private void createSourceProductStack() {

        sourceProduct = new Product(sourceProducts[0].getName(),
                sourceProducts[0].getProductType(),
                sourceProducts[0].getSceneRasterWidth(),
                sourceProducts[0].getSceneRasterHeight());

        ProductUtils.copyProductNodes(sourceProducts[0], sourceProduct);

        int prodIdx = 0;
        for (Product product : sourceProducts) {
            final Band[] sourceBands = product.getBands();
            for (Band band : sourceBands) {
                final String srcBandName = band.getName();
                final String srcBandNameWithTimeStamp = srcBandName + "_" + prodIdx;
                ProductUtils.copyBand(srcBandName, product, srcBandNameWithTimeStamp, sourceProduct, true);
            }
            prodIdx++;
        }
    }

    private static void updateMetadata(final Product targetProduct) {
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(targetProduct);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.coregistered_stack, 0);
    }

    private BandInfo[] getBandGroupNames() {

        final Band[] bands = sourceProduct.getBands();
        final Set<String> nameSet = new LinkedHashSet<>();
        final List<BandInfo> bandGroup = new ArrayList<>();
        for (Band band : bands) {
            final String name = StackUtils.getBandNameWithoutDate(band.getName());
            if (!nameSet.contains(name)) {
                nameSet.add(name);
                bandGroup.add(new BandInfo(band, name));
            }
        }
        return bandGroup.toArray(new BandInfo[0]);
    }

    private Band[] getSourceBands(final String namePrefix) {
        final Band[] bands = sourceProduct.getBands();
        final List<Band> bandList = new ArrayList<>();
        for (Band band : bands) {
            if (!(band instanceof VirtualBand) && band.getName().startsWith(namePrefix)) {
                bandList.add(band);
            }
        }
        return bandList.toArray(new Band[0]);
    }

    private void addVirtualBand(final String operation, final String namePrefix, final String expression,
                                final String unit, final double noDataValue) {
        final VirtualBand virtBand = new VirtualBand(namePrefix,
                ProductData.TYPE_FLOAT32,
                sourceProduct.getSceneRasterWidth(),
                sourceProduct.getSceneRasterHeight(),
                expression);
        virtBand.setUnit(unit);
        virtBand.setDescription(namePrefix + ' ' + operation + ' ' + unit);
        virtBand.setNoDataValueUsed(true);
        virtBand.setNoDataValue(noDataValue);

        final Band srcBand = sourceProduct.getBand(virtBand.getName());
        if (srcBand != null) {
            sourceProduct.removeBand(srcBand);
        }
        sourceProduct.addBand(virtBand);

        ProductUtils.copyBand(namePrefix, sourceProduct, targetProduct, true);
    }

    private void addOriginalVirtualBands(final String trgBandName) {
        final Band[] srcBands = sourceProduct.getBands();
        Band virtSrcBand = null;
        for (Band band : srcBands) {
            if (band.getName().startsWith(trgBandName) && band instanceof VirtualBand) {
                virtSrcBand = band;
                break;
            }
        }
        if (virtSrcBand == null) {
            return;
        }

        final VirtualBand srcBand = (VirtualBand) virtSrcBand;
        String expression = srcBand.getExpression();

        for (Band b : srcBands) {
            final String bName = b.getName();
            if (expression.contains(bName) && !nameGroupContains(bName)) {
                final String newName = StackUtils.getBandNameWithoutDate(bName);
                expression = expression.replaceAll(bName, newName);
            }
        }

        final VirtualBand virtBand = new VirtualBand(trgBandName,
                srcBand.getDataType(),
                srcBand.getRasterWidth(),
                srcBand.getRasterHeight(),
                expression);
        virtBand.setUnit(srcBand.getUnit());
        virtBand.setDescription(srcBand.getDescription());
        virtBand.setNoDataValue(srcBand.getNoDataValue());
        virtBand.setNoDataValueUsed(srcBand.isNoDataValueUsed());
        targetProduct.addBand(virtBand);
    }

    private boolean nameGroupContains(final String name) {
        for (BandInfo b : nameGroups) {
            if (name.equals(b.name)) {
                return true;
            }
        }
        return false;
    }

    private static String mean(final Band[] sourceBands) {
        final StringBuilder expression = new StringBuilder("( ");
        int cnt = 0;
        for (Band band : sourceBands) {
            if (cnt > 0) {
                expression.append(" + ");
            }
            expression.append(band.getName());
            ++cnt;
        }
        expression.append(") / ");
        expression.append(sourceBands.length);
        return expression.toString();
    }

    private static String min(final Band[] sourceBands) {
        final StringBuilder expression = new StringBuilder("min( ");
        int cnt = 0;
        for (Band band : sourceBands) {
            if (cnt > 0) {
                expression.append(", ");
                if (cnt < sourceBands.length - 1) {
                    expression.append("min( ");
                }
            }
            expression.append(band.getName());
            ++cnt;
        }
        for (int i = 0; i < sourceBands.length - 1; ++i) {
            expression.append(")");
        }
        return expression.toString();
    }

    private static String max(final Band[] sourceBands) {
        final StringBuilder expression = new StringBuilder("max( ");
        int cnt = 0;
        for (Band band : sourceBands) {
            if (cnt > 0) {
                expression.append(", ");
                if (cnt < sourceBands.length - 1) {
                    expression.append("max( ");
                }
            }
            expression.append(band.getName());
            ++cnt;
        }
        for (int i = 0; i < sourceBands.length - 1; ++i) {
            expression.append(")");
        }
        return expression.toString();
    }

    private static String mean2(final Band[] sourceBands) {
        final StringBuilder expression = new StringBuilder("( ");
        int cnt = 0;
        for (Band band : sourceBands) {
            if (cnt > 0) {
                expression.append(" + ");
            }
            expression.append("sqr(").append(band.getName()).append(")");
            ++cnt;
        }
        expression.append(") / ");
        expression.append(sourceBands.length);
        return expression.toString();
    }

    private static String mean4(final Band[] sourceBands) {
        final StringBuilder expression = new StringBuilder("( ");
        int cnt = 0;
        for (Band band : sourceBands) {
            if (cnt > 0) {
                expression.append(" + ");
            }
            expression.append("pow(").append(band.getName()).append(", 4)");
            ++cnt;
        }
        expression.append(") / ");
        expression.append(sourceBands.length);
        return expression.toString();
    }

    private static String stddev(final Band[] sourceBands) {
        return "sqrt( " + mean2(sourceBands) + " - " + "sqr(" + mean(sourceBands) + "))";
    }

    private static String coefVar(final Band[] sourceBands) {
        final String m2 = mean2(sourceBands);
        return "sqrt( " + mean4(sourceBands) + " - " + "sqr(" + m2 + ")) / " + m2;
    }

    private static class BandInfo {
        final String name;
        final boolean isVirtual;

        BandInfo(final Band band, final String name) {
            this.name = name;
            this.isVirtual = band instanceof VirtualBand;
        }
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(MultiInputStackAveragingOp.class);
        }
    }
}
