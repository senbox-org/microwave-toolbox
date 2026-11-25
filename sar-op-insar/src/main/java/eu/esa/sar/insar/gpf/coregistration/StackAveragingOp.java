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

import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
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
 * Averaging multi-temporal images
 */
@OperatorMetadata(alias = "Stack-Averaging",
        category = "Radar/Coregistration/Stack Tools",
        authors = "Jun Lu, Luis Veci",
        version = "1.0",
        copyright = "Copyright (C) 2014 by Array Systems Computing Inc.",
        description = "Averaging multi-temporal images")
public class StackAveragingOp extends Operator {

    @SourceProduct
    private Product sourceProduct;
    @TargetProduct
    private Product targetProduct;

    @Parameter(valueSet = {"Mean Average", "Minimum", "Maximum", "Standard Deviation", "Coefficient of Variation"},
            defaultValue = "Mean Average", label = "Statistic")
    private String statistic = "Mean Average";

    @Parameter(description = "Minimum number of valid (not no-data) dates a pixel needs; fewer gives no-data. " +
            "Standard Deviation and Coefficient of Variation always need at least 2.",
            defaultValue = "1", interval = "[1,*)", label = "Minimum valid dates")
    private int minValidDates = 1;

    private BandInfo[] nameGroups;
    // virtual bands this operator added to the source product, removed again in dispose()
    private final List<Band> addedSourceBands = new ArrayList<>();
    private boolean sourceWasModified;

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
            targetProduct = new Product(sourceProduct.getName(),
                    sourceProduct.getProductType(),
                    sourceProduct.getSceneRasterWidth(),
                    sourceProduct.getSceneRasterHeight());

            ProductUtils.copyProductNodes(sourceProduct, targetProduct);

            sourceWasModified = sourceProduct.isModified();
            nameGroups = getBandGroupNames();

            for (BandInfo bandInfo : nameGroups) {
                if (bandInfo.isVirtual) {
                    // add virtual intensity bands
                    addOriginalVirtualBands(bandInfo.name);
                } else {
                    final String name_prefix = bandInfo.name;
                    final Band[] sourceBands = getSourceBands(name_prefix);
                    final String unit = sourceBands[0].getUnit();
                    // the statistic is float: NaN marks too few valid dates, without masking a real 0
                    // (a constant pixel has a standard deviation of 0)
                    final double nodatavalue = Double.NaN;

                    switch (statistic) {
                        case "Mean Average":
                            addVirtualBand("average", name_prefix, mean(sourceBands, minValidDates), unit, nodatavalue);
                            break;
                        case "Minimum":
                            addVirtualBand("min", name_prefix, min(sourceBands, minValidDates), unit, nodatavalue);
                            break;
                        case "Maximum":
                            addVirtualBand("max", name_prefix, max(sourceBands, minValidDates), unit, nodatavalue);
                            break;
                        case "Standard Deviation":
                            addVirtualBand("stddev", name_prefix, stddev(sourceBands, minValidDates), unit, nodatavalue);
                            break;
                        case "Coefficient of Variation":
                            addVirtualBand("coefVar", name_prefix, coefVar(sourceBands, minValidDates), unit, nodatavalue);
                            break;
                    }
                }
            }

            // bands that are not part of the stack (no date in the name, e.g. an added elevation band)
            for (Band band : sourceProduct.getBands()) {
                if (!isStackBand(band.getName()) && !addedSourceBands.contains(band) &&
                        targetProduct.getBand(band.getName()) == null) {
                    ProductUtils.copyBand(band.getName(), sourceProduct, targetProduct, true);
                }
            }

            updateMetadata(targetProduct);
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    @Override
    public void dispose() {
        // only the bands added here: the source product keeps all of its own bands
        for (Band band : addedSourceBands) {
            sourceProduct.removeBand(band);
        }
        if (!addedSourceBands.isEmpty()) {
            addedSourceBands.clear();
            sourceProduct.setModified(sourceWasModified);
        }
    }

    /**
     * A band of the stack: its name carries a reference/secondary marker or ends in a date (_01Jan2020).
     * Other bands (elevation, incidence angle, ...) are not averaged but copied.
     */
    static boolean isStackBand(final String name) {
        if (StackUtils.getBandNameWithoutDate(name).equals(name)) {
            return false;
        }
        if (name.contains(StackUtils.REF) || name.contains(StackUtils.SEC) ||
                name.contains(StackUtils.MST) || name.contains(StackUtils.SLV)) {
            return true;
        }
        return name.substring(name.lastIndexOf('_') + 1).matches("\\d{1,2}[A-Za-z]{3}\\d{4}");
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
            if (!isStackBand(band.getName())) {
                continue;
            }
            final String name = StackUtils.getBandNameWithoutDate(band.getName());
            if (!nameSet.contains(name)) {
                nameSet.add(name);
                bandGroup.add(new BandInfo(band, name));
            }
        }
        return bandGroup.toArray(new BandInfo[0]);
    }

    private Band[] getSourceBands(final String name_prefix) {
        final Band[] bands = sourceProduct.getBands();
        final List<Band> bandList = new ArrayList<>();
        for (Band band : bands) {
            // the same group only: Sigma0_VV must not take in Sigma0_VV_db_... bands
            if (!(band instanceof VirtualBand) && isStackBand(band.getName()) &&
                    StackUtils.getBandNameWithoutDate(band.getName()).equals(name_prefix)) {
                bandList.add(band);
            }
        }
        return bandList.toArray(new Band[0]);
    }

    private void addVirtualBand(final String operation, final String name_prefix, final String expression,
                                final String unit, final double nodatavalue) {
        // The expression is evaluated in the source product, under a name no source band has, so it can
        // neither refer to itself nor replace a band of the source.
        String tmpName = name_prefix + "_stack_" + operation;
        for (int i = 2; sourceProduct.containsRasterDataNode(tmpName); i++) {
            tmpName = name_prefix + "_stack_" + operation + i;
        }
        final VirtualBand virtBand = new VirtualBand(tmpName,
                ProductData.TYPE_FLOAT32,
                sourceProduct.getSceneRasterWidth(),
                sourceProduct.getSceneRasterHeight(),
                expression);
        virtBand.setUnit(unit);
        virtBand.setDescription(name_prefix + ' ' + operation + ' ' + unit);
        virtBand.setNoDataValueUsed(true);
        virtBand.setNoDataValue(nodatavalue);

        sourceProduct.addBand(virtBand);
        addedSourceBands.add(virtBand);

        final Band targetBand = ProductUtils.copyBand(tmpName, sourceProduct, targetProduct, true);
        targetBand.setName(name_prefix);
    }

    private void addOriginalVirtualBands(final String trgBandName) {
        final Band[] srcBands = sourceProduct.getBands();
        Band virtSrcBand = null;
        for (Band band : srcBands) {
            if (band instanceof VirtualBand && StackUtils.getBandNameWithoutDate(band.getName()).equals(trgBandName)) {
                virtSrcBand = band;
                break;
            }
        }
        if (virtSrcBand == null)
            return;

        final VirtualBand srcBand = (VirtualBand) virtSrcBand;
        String expression = srcBand.getExpression();

        for (Band b : srcBands) {
            final String bName = b.getName();
            if (expression.contains(bName) && !nameGroupContains(bName)) {
                final String newName = StackUtils.getBandNameWithoutDate(bName);
                expression = expression.replace(bName, newName);     // a band name is not a regex
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
        for(BandInfo b : nameGroups) {
            if(name.equals(b.name))
                return true;
        }
        return false;
    }

    /*
     * The statistics count only valid samples: a date where a band is NaN or equals its own no-data value
     * (0-filled outside a Secondary's footprint) is left out of the sum and of the divisor, instead of being
     * averaged in as a real 0. A pixel with fewer than the minimum number of valid dates gives NaN, which the
     * virtual band writes as its no-data value.
     */

    /** True where the band holds a valid sample. */
    static String isValid(final Band band) {
        final String name = band.getName();
        // The band symbol evaluates to the geophysical value, widened from float when the geophysical type is
        // float32: float32 bands and also scaled integer bands, whose no-data must be rounded the same way.
        double noData = band.getGeophysicalNoDataValue();
        if (band.getGeophysicalDataType() == ProductData.TYPE_FLOAT32) {
            noData = (float) noData;
        }
        if (!band.isNoDataValueUsed() || Double.isNaN(noData)) {
            return "!nan(" + name + ")";
        }
        if (Double.isInfinite(noData)) {
            // "Infinity" is not an expression literal
            return "(!nan(" + name + ") && !inf(" + name + "))";
        }
        return "(!nan(" + name + ") && " + name + " != " + noData + ")";
    }

    /** Sum over the bands of {@code term} (with {@code %s} standing for the band) where valid, else {@code other}. */
    private static String sumValid(final Band[] sourceBands, final String term, final String other) {
        final StringBuilder expression = new StringBuilder("(");
        for (int i = 0; i < sourceBands.length; ++i) {
            if (i > 0)
                expression.append(" + ");
            expression.append('(').append(isValid(sourceBands[i])).append(" ? ")
                    .append(String.format(term, sourceBands[i].getName())).append(" : ").append(other).append(')');
        }
        return expression.append(')').toString();
    }

    private static String count(final Band[] sourceBands) {
        return sumValid(sourceBands, "1", "0");
    }

    private static String enoughValid(final Band[] sourceBands, final int minValid) {
        return count(sourceBands) + " >= " + Math.max(1, minValid);
    }

    private static String mean(final Band[] sourceBands, final int minValid) {
        return "(" + enoughValid(sourceBands, minValid) + " ? " + sumValid(sourceBands, "%s", "0") + " / " +
                count(sourceBands) + " : NaN)";
    }

    /** Nested binary min/max over the valid samples; invalid samples are replaced by {@code neutral}. */
    private static String extreme(final String function, final String neutral, final Band[] sourceBands,
                                  final int minValid) {
        String expression = "(" + isValid(sourceBands[0]) + " ? " + sourceBands[0].getName() + " : " + neutral + ")";
        for (int i = 1; i < sourceBands.length; ++i) {
            expression = function + "(" + expression + ", (" + isValid(sourceBands[i]) + " ? " +
                    sourceBands[i].getName() + " : " + neutral + "))";
        }
        return "(" + enoughValid(sourceBands, minValid) + " ? " + expression + " : NaN)";
    }

    private static String min(final Band[] sourceBands, final int minValid) {
        return extreme("min", String.valueOf(Float.MAX_VALUE), sourceBands, minValid);
    }

    private static String max(final Band[] sourceBands, final int minValid) {
        return extreme("max", String.valueOf(-Float.MAX_VALUE), sourceBands, minValid);
    }

    /** Population standard deviation of the valid samples; max(0, ..) absorbs rounding below zero. */
    private static String populationStd(final Band[] sourceBands) {
        final String n = count(sourceBands);
        final String mean = "(" + sumValid(sourceBands, "%s", "0") + " / " + n + ")";
        final String mean2 = "(" + sumValid(sourceBands, "sq(%s)", "0") + " / " + n + ")";
        return "sqrt(max(0, " + mean2 + " - sq(" + mean + ")))";
    }

    private static String stddev(final Band[] sourceBands, final int minValid) {
        return "(" + enoughValid(sourceBands, Math.max(2, minValid)) + " ? " + populationStd(sourceBands) + " : NaN)";
    }

    /**
     * Coefficient of variation of the valid samples: population standard deviation / mean.
     * (It used to be computed on the squared samples, and the unparenthesised divisor also
     * divided by N a second time.)
     */
    private static String coefVar(final Band[] sourceBands, final int minValid) {
        final String mean = "(" + sumValid(sourceBands, "%s", "0") + " / " + count(sourceBands) + ")";
        return "(" + enoughValid(sourceBands, Math.max(2, minValid)) + " ? " + populationStd(sourceBands) +
                " / " + mean + " : NaN)";
    }

    private static class BandInfo {
        final String name;
        final boolean isVirtual;

        public BandInfo(final Band band, final String name) {
            this.name = name;
            this.isVirtual = band instanceof VirtualBand;
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
            super(StackAveragingOp.class);
        }
    }
}
