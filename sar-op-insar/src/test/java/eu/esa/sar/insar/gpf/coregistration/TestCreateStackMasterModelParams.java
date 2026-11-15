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
package eu.esa.sar.insar.gpf.coregistration;

import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A secondary that CreateStack geocodes itself (GSLC path) must use the reference GSLC's DEM, solid
 * Earth tide and troposphere settings. They used to be left at the GSLC defaults.
 */
public class TestCreateStackMasterModelParams {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static MetadataElement addNode(final MetadataElement graph, final int idx, final String operator) {
        final MetadataElement node = new MetadataElement("node." + idx);
        node.setAttributeString("operator", operator);
        final MetadataElement params = new MetadataElement("parameters");
        node.addElement(params);
        graph.addElement(node);
        return params;
    }

    private static Product newReference() {
        final Product p = new Product("ref_GSLC", "GSLC", 8, 8);
        AbstractMetadata.addAbstractedMetadataHeader(p.getMetadataRoot());
        return p;
    }

    @Test
    public void testModelParamsForwardedFromProcessingGraph() {
        final Product ref = newReference();
        final MetadataElement graph = new MetadataElement("Processing_Graph");
        ref.getMetadataRoot().addElement(graph);
        addNode(graph, 0, "Read");
        final MetadataElement gslc = addNode(graph, 1, "GSLC-Terrain-Correction");
        gslc.setAttributeString("demName", "SRTM 1Sec HGT");
        gslc.setAttributeString("demResamplingMethod", "BICUBIC_INTERPOLATION");
        gslc.setAttributeString("applySolidEarthTide", "true");
        gslc.setAttributeString("applyTroposphericCorrection", "true");
        gslc.setAttributeString("outputFlattened", "true");
        addNode(graph, 2, "Write");

        final Map<String, Object> params = new HashMap<>();
        CreateStackOp.applyMasterModelParams(params, ref);

        assertEquals("SRTM 1Sec HGT", params.get("demName"));
        assertEquals("BICUBIC_INTERPOLATION", params.get("demResamplingMethod"));
        assertEquals(Boolean.TRUE, params.get("applySolidEarthTide"));
        assertEquals(Boolean.TRUE, params.get("applyTroposphericCorrection"));
        assertFalse("no external DEM in the reference", params.containsKey("externalDEMFile"));
        assertFalse("flattening is forwarded by its own stamp, not here", params.containsKey("outputFlattened"));
    }

    @Test
    public void testExternalDemForwardedWithNoDataAndEgm() throws Exception {
        final File demFile = tmp.newFile("lidar.tif");
        final Product ref = newReference();
        final MetadataElement graph = new MetadataElement("Processing_Graph");
        ref.getMetadataRoot().addElement(graph);
        final MetadataElement gslc = addNode(graph, 0, "GSLC-Terrain-Correction");
        gslc.setAttributeString("demName", "External DEM");
        gslc.setAttributeString("externalDEMFile", demFile.getPath());
        gslc.setAttributeString("externalDEMNoDataValue", "-32768.0");
        gslc.setAttributeString("externalDEMApplyEGM", "false");
        gslc.setAttributeString("applySolidEarthTide", "false");

        final Map<String, Object> params = new HashMap<>();
        CreateStackOp.applyMasterModelParams(params, ref);

        assertEquals("External DEM", params.get("demName"));
        assertEquals(demFile, params.get("externalDEMFile"));
        assertEquals(-32768.0, (Double) params.get("externalDEMNoDataValue"), 0.0);
        assertEquals(Boolean.FALSE, params.get("externalDEMApplyEGM"));
        assertEquals(Boolean.FALSE, params.get("applySolidEarthTide"));
    }

    /** GSLC reads externalDEMFile only when demName selects "External DEM"; otherwise it is not forwarded. */
    @Test
    public void testUnusedExternalDemFileIsNotForwarded() {
        final Product ref = newReference();
        final MetadataElement graph = new MetadataElement("Processing_Graph");
        ref.getMetadataRoot().addElement(graph);
        final MetadataElement gslc = addNode(graph, 0, "GSLC-Terrain-Correction");
        gslc.setAttributeString("demName", "Copernicus 30m Global DEM");
        gslc.setAttributeString("externalDEMFile", "C:\\nowhere\\unused.tif");

        final Map<String, Object> params = new HashMap<>();
        CreateStackOp.applyMasterModelParams(params, ref);
        assertEquals("Copernicus 30m Global DEM", params.get("demName"));
        assertFalse(params.containsKey("externalDEMFile"));
    }

    @Test
    public void testMissingExternalDemFromGraphIsAClearError() {
        final Product ref = newReference();
        final MetadataElement graph = new MetadataElement("Processing_Graph");
        ref.getMetadataRoot().addElement(graph);
        final MetadataElement gslc = addNode(graph, 0, "GSLC-Terrain-Correction");
        gslc.setAttributeString("demName", "External DEM");
        gslc.setAttributeString("externalDEMFile", new File(tmp.getRoot(), "missing.tif").getPath());
        try {
            CreateStackOp.applyMasterModelParams(new HashMap<>(), ref);
            fail("missing external DEM must be refused");
        } catch (OperatorException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("missing.tif"));
        }
    }

    @Test
    public void testFallbackToDemStampWithoutProcessingGraph() throws Exception {
        final Product ref = newReference();
        final MetadataElement abs = AbstractMetadata.getAbstractedMetadata(ref);
        abs.setAttributeString(AbstractMetadata.DEM, "SRTM 3Sec");
        Map<String, Object> params = new HashMap<>();
        CreateStackOp.applyMasterModelParams(params, ref);
        assertEquals("SRTM 3Sec", params.get("demName"));
        assertFalse(params.containsKey("applySolidEarthTide"));
        assertFalse(params.containsKey("externalDEMFile"));

        // an external DEM is stamped by its path: GSLC needs demName "External DEM" to read the file
        final File demFile = tmp.newFile("dem.tif");
        abs.setAttributeString(AbstractMetadata.DEM, demFile.getPath());
        params = new HashMap<>();
        CreateStackOp.applyMasterModelParams(params, ref);
        assertEquals("External DEM", params.get("demName"));
        assertEquals(demFile, params.get("externalDEMFile"));
    }

    /** A stamped external DEM path that does not exist here must not be passed on as a DEM name. */
    @Test
    public void testFallbackMissingExternalDemIsAClearError() {
        final Product ref = newReference();
        final MetadataElement abs = AbstractMetadata.getAbstractedMetadata(ref);
        final String missing = new File(tmp.getRoot(), "gone" + File.separator + "dem.tif").getPath();
        abs.setAttributeString(AbstractMetadata.DEM, missing);
        final Map<String, Object> params = new HashMap<>();
        try {
            CreateStackOp.applyMasterModelParams(params, ref);
            fail("missing external DEM must be refused");
        } catch (OperatorException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(missing));
        }
        assertFalse("path must never be forwarded as a DEM name", missing.equals(params.get("demName")));
    }

    @Test
    public void testNothingForwardedForUnstampedReference() {
        final Map<String, Object> params = new HashMap<>();
        CreateStackOp.applyMasterModelParams(params, newReference());
        CreateStackOp.applyMasterModelParams(params, null);
        assertTrue(params.isEmpty());
    }
}
