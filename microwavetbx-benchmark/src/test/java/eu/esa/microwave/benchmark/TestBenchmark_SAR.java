/*
 * Copyright (C) 2021 SkyWatch Space Applications Inc. https://www.skywatch.com
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
package eu.esa.microwave.benchmark;

import eu.esa.sar.calibration.gpf.CalibrationOp;
import eu.esa.sar.sar.gpf.MultilookOp;
import eu.esa.sar.sar.gpf.geometric.EllipsoidCorrectionRDOp;
import eu.esa.sar.sar.gpf.geometric.RangeDopplerGeocodingOp;
import eu.esa.sar.sar.gpf.geometric.TerrainFlatteningOp;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.raster.gpf.texture.GLCMOp;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;

public class TestBenchmark_SAR extends BaseBenchmarks {

    public TestBenchmark_SAR() {
        super("SAR");
    }

    @Test
    public void testGRD_multilook() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        multilook(grdFile, WriteMode.PRODUCT_IO);
    }

    @Test
    public void testGRD_terraincorrect() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        terrain_correct(grdFile, WriteMode.PRODUCT_IO);
    }

    @Test
    public void testGRD_ellipsoidcorrect() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        ellipsoid_correct(grdFile, WriteMode.PRODUCT_IO);
    }

    @Test
    public void testGRD_terrainflatten() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        terrain_flatten(grdFile, WriteMode.PRODUCT_IO);
    }

    @Test
    @Ignore
    public void testGRD_glcm() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        glcm(grdFile, WriteMode.PRODUCT_IO);
    }


    private void multilook(final File srcFile, final WriteMode mode) throws Exception {
        Benchmark b = new Benchmark(groupName, testName) {
            @Override
            protected void execute() throws Exception {
                switch (mode) {
                    case PRODUCT_IO:
                    case GPF:
                        final Product srcProduct = subset(srcFile, rect);

                        MultilookOp op = new MultilookOp();
                        op.setSourceProduct(srcProduct);
                        Product trgProduct = op.getTargetProduct();

                        write(trgProduct, outputFolder, mode);

                        trgProduct.dispose();
                        srcProduct.dispose();
                        break;
                    case GRAPH:
                        //processGraph(srcFile, outputFolder);
                        break;
                }
            }
        };
        b.run();
    }

    private void terrain_correct(final File srcFile, final WriteMode mode) throws Exception {
        Benchmark b = new Benchmark(groupName, testName) {
            @Override
            protected void execute() throws Exception {
                switch (mode) {
                    case PRODUCT_IO:
                    case GPF:
                        final Product srcProduct = subset(srcFile, rect);

                        RangeDopplerGeocodingOp op = new RangeDopplerGeocodingOp();
                        op.setSourceProduct(srcProduct);
                        Product trgProduct = op.getTargetProduct();

                        write(trgProduct, outputFolder, mode);

                        trgProduct.dispose();
                        srcProduct.dispose();
                        break;
                    case GRAPH:
                        //processGraph(srcFile, outputFolder);
                        break;
                }
            }
        };
        b.run();
    }

    private void ellipsoid_correct(final File srcFile, final WriteMode mode) throws Exception {
        Benchmark b = new Benchmark(groupName, testName) {
            @Override
            protected void execute() throws Exception {
                switch (mode) {
                    case PRODUCT_IO:
                    case GPF:
                        final Product srcProduct = subset(srcFile, rect);

                        EllipsoidCorrectionRDOp op = new EllipsoidCorrectionRDOp();
                        op.setSourceProduct(srcProduct);
                        Product trgProduct = op.getTargetProduct();

                        write(trgProduct, outputFolder, mode);

                        trgProduct.dispose();
                        srcProduct.dispose();
                        break;
                    case GRAPH:
                        //processGraph(srcFile, outputFolder);
                        break;
                }
            }
        };
        b.run();
    }

    private void terrain_flatten(final File srcFile, final WriteMode mode) throws Exception {
        Benchmark b = new Benchmark(groupName, testName) {
            @Override
            protected void execute() throws Exception {
                switch (mode) {
                    case PRODUCT_IO:
                    case GPF:
                        final Product srcProduct = subset(srcFile, rect);

                        CalibrationOp cal = new CalibrationOp();
                        cal.setSourceProduct(srcProduct);
                        cal.setParameter("outputBetaBand", true);
                        Product calProduct = cal.getTargetProduct();

                        TerrainFlatteningOp op = new TerrainFlatteningOp();
                        op.setSourceProduct(calProduct);
                        Product trgProduct = op.getTargetProduct();

                        write(trgProduct, outputFolder, mode);

                        trgProduct.dispose();
                        srcProduct.dispose();
                        break;
                    case GRAPH:
                        //processGraph(srcFile, outputFolder);
                        break;
                }
            }
        };
        b.run();
    }

    private void glcm(final File srcFile, final WriteMode mode) throws Exception {
        Benchmark b = new Benchmark(groupName, testName) {
            @Override
            protected void execute() throws Exception {
                switch (mode) {
                    case PRODUCT_IO:
                    case GPF:
                        final Product srcProduct = subset(srcFile, rect);

                        GLCMOp op = new GLCMOp();
                        op.setSourceProduct(srcProduct);
                        op.setParameter("quantizationLevelsStr", "16");
                        Product trgProduct = op.getTargetProduct();

                        write(trgProduct, outputFolder, mode);

                        trgProduct.dispose();
                        srcProduct.dispose();
                        break;
                    case GRAPH:
                        //processGraph(srcFile, outputFolder);
                        break;
                }
            }
        };
        b.run();
    }
}
