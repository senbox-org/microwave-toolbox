/*
 * Copyright (C) 2024 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
import eu.esa.sar.cloud.json.JSON;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProducts;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.jlinda.core.Orbit;
import org.jlinda.core.SLCImage;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * InSAR summary and optimal reference selection
 */
@SuppressWarnings("unchecked")
@OperatorMetadata(alias = "InSAR-Overview",
        description = "InSAR summary and optimal reference selection.",
        authors = "Jun Lu, Luis Veci",
        version = "1.0",
        copyright = "Copyright (C) 2024 by SkyWatch Space Applications Inc.",
        autoWriteDisabled = true,
        category = "Radar/Coregistration/Stack Tools")
public class InSAROverviewOp extends Operator {

    @TargetProduct
    private Product targetProduct;

    @SourceProducts(alias = "source", description = "The list source products.")
    private Product[] sourceProducts;

    @Parameter(description = "The output json file.")
    private File overviewJSONFile;

    @Override
    public void initialize() throws OperatorException {
        try {
            if(sourceProducts == null) {
                throw new OperatorException("Please add a list of source products of two or more products");
            }
            if(sourceProducts.length < 2) {
                throw new OperatorException("Please add a list of source products of two or more products");
            }
            for(Product srcProduct : sourceProducts) {
                final InputProductValidator validator = new InputProductValidator(srcProduct);
                validator.checkIfSARProduct();
                validator.checkIfSLC();
            }

            if(overviewJSONFile == null) {
                throw new OperatorException("Please add an output json file path");
            }

            targetProduct = sourceProducts[0];

            JSONObject json = produceInSAROverview(sourceProducts);

            JSON.write(json, overviewJSONFile);
        } catch (Throwable t) {
            throw new OperatorException(t);
        }
    }

    static JSONObject produceInSAROverview(final Product[] srcProducts) throws Exception {
        final SLCImage[] slcImage = getSLCImages(srcProducts);
        final Orbit[] orbits = getOrbits(srcProducts);

        final InSARStackOverview dataStack = new InSARStackOverview();
        dataStack.setInput(slcImage, orbits);

        final InSARStackOverview.IfgStack[] ifgStack = dataStack.getCoherenceScores(ProgressMonitor.NULL);

        int referenceIndex = dataStack.findOptimalMaster(ifgStack);
        InSARStackOverview.IfgStack refStack = ifgStack[referenceIndex];
        InSARStackOverview.IfgPair[] secondaryList = refStack.getMasterSlave();

        return toJSON(refStack, secondaryList);
    }

    private static SLCImage[] getSLCImages(final Product[] inputProducts) throws IOException {
        final java.util.List<SLCImage> imgList = new ArrayList<>();

        for (Product product : inputProducts) {
            final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
            final SLCImage img = new SLCImage(absRoot, product);
            imgList.add(img);
        }
        return imgList.toArray(new SLCImage[0]);
    }

    private static Orbit[] getOrbits(final Product[] inputProducts) {
        final List<Orbit> orbList = new ArrayList<>();
        for (Product product : inputProducts) {
            final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
            final Orbit orb = new Orbit(absRoot, 3);
            orbList.add(orb);
        }
        return orbList.toArray(new Orbit[0]);
    }

    private static JSONObject toJSON(final InSARStackOverview.IfgStack refStack,
                                     final InSARStackOverview.IfgPair[] secondaryList) {
        JSONObject json = new JSONObject();

        MetadataElement refMeta = refStack.getMasterSlave()[0].getMasterMetadata().getAbstractedMetadata();

        JSONObject reference = new JSONObject();
        json.put("reference", reference);

        writeProductDetails(reference, refMeta.getProduct(), refMeta);

        JSONArray secondaries = new JSONArray();
        json.put("secondary", secondaries);
        for(InSARStackOverview.IfgPair ifgPair : secondaryList) {
            if(ifgPair.getCoherence() != 1.0) {
                JSONObject secondary = new JSONObject();
                secondaries.add(secondary);

                MetadataElement secMeta = ifgPair.getMasterMetadata().getAbstractedMetadata();
                writeProductDetails(secondary, secMeta.getProduct(), secMeta);

                JSONObject overview = new JSONObject();
                secondary.put("insar_overview", overview);

                overview.put("coherence", ifgPair.getCoherence());
                overview.put("perpendicular_baseline_m", ifgPair.getPerpendicularBaseline());
                overview.put("temporal_baseline_days", ifgPair.getTemporalBaseline());
                overview.put("height_of_ambiguity_m", ifgPair.getHeightAmb());
                overview.put("doppler_difference_hz", ifgPair.getDopplerDifference());
            }
        }

        return json;
    }

    private static void writeProductDetails(final JSONObject json, final Product product, final MetadataElement meta) {
        json.put("product_name", product.getName());
        json.put("product_type", product.getProductType());
        json.put("product_width", product.getSceneRasterWidth());
        json.put("product_height", product.getSceneRasterHeight());
        json.put("start_time", product.getStartTime().toString());
        json.put("end_time", product.getEndTime().toString());
        json.put("file", product.getFileLocation().getPath());

        json.put("track", meta.getAttributeInt(AbstractMetadata.REL_ORBIT));
        json.put("orbit", meta.getAttributeInt(AbstractMetadata.ABS_ORBIT));
        json.put("mode", meta.getAttributeString(AbstractMetadata.ACQUISITION_MODE));
        json.put("prf", meta.getAttributeDouble(AbstractMetadata.pulse_repetition_frequency));
        json.put("pass", meta.getAttributeString(AbstractMetadata.PASS));
        json.put("orbit_state_vector_file", meta.getAttributeString(AbstractMetadata.orbit_state_vector_file));
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(InSAROverviewOp.class);
        }
    }
}

