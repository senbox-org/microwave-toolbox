/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.io.nisar.subreaders;

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.StxFactory;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import ucar.nc2.Attribute;
import ucar.nc2.Group;
import ucar.nc2.Variable;

import java.util.ArrayList;
import java.util.List;

public class NisarGSLCProductReader extends NisarSubReader {

    private static final String[] pols = {"HH", "HV", "VH", "VV"};

    public NisarGSLCProductReader() {
        productType = "GSLC";
    }

    /**
     * Stamp the {@code gslc_source_slc_path} and {@code gslc_output_flattened} metadata
     * attributes so {@link eu.esa.sar.insar.gpf.coregistration.CreateStackOp}'s
     * GSLC auto-coregister path recognises this product. NISAR GSLC files reference
     * the parent RSLC via {@code metadata/sourceData/referenceTerrainHeight} and
     * sibling metadata; the path itself isn't always present in the HDF5, so we
     * derive it from the product filename when needed.
     * <p>
     * NISAR GSLCs preserve the SLC carrier (no phase flattening), so
     * {@code gslc_output_flattened = "false"}.
     */
    @Override
    protected void addAbstractedMetadataHeader(final MetadataElement root) throws Exception {
        super.addAbstractedMetadataHeader(root);

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);

        // Phase-flattening state: NISAR GSLCs retain the natural SLC carrier (per the
        // NISAR Algorithm Theoretical Basis Document — geocoding restores the phase after
        // resampling). Mark accordingly so CreateStack's auto-coregister builds the slave
        // GSLC with a matching state.
        AbstractMetadata.addAbstractedAttribute(absRoot, "gslc_output_flattened",
                ProductData.TYPE_ASCII, "flag",
                "true if topographic + ellipsoidal phase has been subtracted from the GSLC carrier");
        AbstractMetadata.setAttribute(absRoot, "gslc_output_flattened", "false");

        // Source-SLC reference: try HDF5 metadata first, then derive from the product
        // filename by swapping GSLC → RSLC. The CreateStack auto-coregister has a
        // name-based fallback that also looks for the source SLC next to the product
        // on disk, so this stamp is best-effort.
        final String sourceSlcPath = resolveSourceRslcPath();
        if (sourceSlcPath != null) {
            AbstractMetadata.addAbstractedAttribute(absRoot, "gslc_source_slc_path",
                    ProductData.TYPE_ASCII, "path",
                    "File path of the source SLC this GSLC was geocoded from");
            AbstractMetadata.setAttribute(absRoot, "gslc_source_slc_path", sourceSlcPath);
        }
    }

    /**
     * Try to find a usable RSLC path for the GSLC's source. Strategy:
     * <ol>
     *   <li>HDF5 metadata: {@code /science/{LSAR|SSAR}/GSLC/metadata/sourceData/referenceRslc}
     *       (if present).</li>
     *   <li>Filename swap: replace {@code _GSLC_} with {@code _RSLC_} and check whether
     *       a file with that name sits next to the GSLC.</li>
     * </ol>
     * Returns {@code null} when nothing can be resolved — that's not an error, just
     * a signal to CreateStack to skip the bias-estimation pass.
     */
    private String resolveSourceRslcPath() {
        // Strategy 1: HDF5 attribute on the sourceData group.
        try {
            final String sarBand = netcdfFile.findGroup("/science/LSAR") != null ? "LSAR" : "SSAR";
            final Group sourceData = netcdfFile.findGroup(
                    "/science/" + sarBand + "/" + productType + "/metadata/sourceData");
            if (sourceData != null) {
                for (final String candidate : new String[]{"referenceRslc", "referenceRslcFile", "sourceFile"}) {
                    final Variable v = sourceData.findVariable(candidate);
                    if (v != null) {
                        final String path = v.readScalarString();
                        if (path != null && !path.isEmpty()) return path;
                    }
                }
            }
        } catch (Exception e) {
            SystemUtils.LOG.fine("NISAR GSLC: no sourceData path attribute (" + e.getMessage() + ")");
        }

        // Strategy 2: name-based fallback. NISAR file naming includes the product type
        // as a fixed token (e.g. NISAR_L2_PR_GSLC_… ↔ NISAR_L1_PR_RSLC_…).
        if (product != null && product.getFileLocation() != null) {
            final java.io.File self = product.getFileLocation();
            final String name = self.getName();
            String candidate = name.replace("_L2_", "_L1_").replace("_GSLC_", "_RSLC_");
            if (!candidate.equals(name)) {
                final java.io.File sibling = new java.io.File(self.getParentFile(), candidate);
                if (sibling.isFile()) return sibling.getAbsolutePath();
            }
        }
        return null;
    }

    @Override
    protected Group getFrequencyAGroup(final Group groupLSAR) {
        final Group groupProductType = groupLSAR.findGroup(productType);
        final Group groupGrids = groupProductType.findGroup("grids");
        return groupGrids.findGroup("frequencyA");
    }

    @Override
    protected Group getFrequencyBGroup(final Group groupLSAR) {
        final Group groupProductType = groupLSAR.findGroup(productType);
        final Group groupGrids = groupProductType.findGroup("grids");
        return groupGrids.findGroup("frequencyB");
    }

    @Override
    protected Variable[] getRasterVariables(final Group groupFrequency) {
        List<Variable> rasterVariables = new ArrayList<>();
        for (String pol : pols) {
            Variable v = groupFrequency.findVariable(pol);
            if (v != null) {
                rasterVariables.add(v);
            }
        }

        return rasterVariables.toArray(new Variable[0]);
    }

    @Override
    protected void addBandsForFrequency(Group groupFrequency, String suffix) {
        for (String pol : pols) {
            Variable variable = groupFrequency.findVariable(pol);
            if (variable != null) {

                Band i = newBand(variable, "i_" + pol, "real", Unit.REAL, 0);
                Band q = newBand(variable, "q_" + pol, "imag", Unit.IMAGINARY, 0);
                ReaderUtils.createVirtualIntensityBand(product, i, q, pol);
                ReaderUtils.createVirtualPhaseBand(product, i, q, pol);
            }
        }
    }

    private Band newBand(Variable var, String bandName, String cpxType, String bandUnit, float nodatavalue) {
        int rasterHeight = var.getDimension(0).getLength();
        int rasterWidth = var.getDimension(1).getLength();
        Band band = createBand(bandName, rasterWidth, rasterHeight, bandUnit, var);
        band.setNoDataValue(nodatavalue);
        band.setNoDataValueUsed(true);

        Attribute minAt = var.attributes().findAttribute("min_" + cpxType + "_value");
        Attribute maxAt = var.attributes().findAttribute("max_" + cpxType + "_value");
        if (minAt == null) minAt = var.attributes().findAttribute("min_value_" + cpxType);
        if (maxAt == null) maxAt = var.attributes().findAttribute("max_value_" + cpxType);
        if (minAt != null && maxAt != null) {
            try {
                double min = minAt.getNumericValue().doubleValue();
                double max = maxAt.getNumericValue().doubleValue();
                band.setStx(new StxFactory().withMinimum(min).withMaximum(max)
                        .withIntHistogram(false).withHistogramBins(new int[512]).create());
            } catch (Exception e) {
                // ignore
            }
        }
        return band;
    }
}
