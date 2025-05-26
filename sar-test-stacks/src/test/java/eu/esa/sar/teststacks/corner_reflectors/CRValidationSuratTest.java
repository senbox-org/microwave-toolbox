/*
 * Copyright (C) 2025 SkyWatch. https://www.skywatch.com
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
package eu.esa.sar.teststacks.corner_reflectors;

import com.bc.ceres.multilevel.MultiLevelImage;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.internal.TileImpl;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.gpf.TileIndex;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.awt.*;
import java.awt.image.Raster;
import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.junit.Assume.assumeTrue;

public class CRValidationSuratTest extends BaseCRTest {

//    private final static File S1_GRD_Surat = new File(TestData.inputSAR + "S1/corner_reflectors/GA/Surat/S1A_IW_GRDH_1SDV_20250413T192217_20250413T192242_058742_0746BD_0527.SAFE.zip");
    private final static File S1_GRD_Surat = new File("D:/Output/Aus-Geo-Location/S1A_IW_GRDH_1SSV_20231225T083316_20231225T083341_051808_064216_FBB8.zip");
    private final static String Surat_CSV = "/eu/esa/sar/teststacks/corner_reflectors/GA/surat_basin_queensland_calibration_targets.csv";

    public CRValidationSuratTest() {
        super("Surat");
    }

    @Before
    public void setUp() {
        // If any of the file does not exist: the test will be ignored
        assumeTrue(S1_GRD_Surat + " not found", S1_GRD_Surat.exists());
    }

    @Test
    public void testGA() throws IOException {
        setName(new Throwable().getStackTrace()[0].getMethodName());

        Product product = ProductIO.readProduct(S1_GRD_Surat);
        Assert.assertNotNull(product);

        addCornerReflectorPins(product);

        computeCRGeoLocationError(product);

        write(product);
    }

    private void addCornerReflectorPins(Product trgProduct) throws IOException {
        final List<String[]> csv = readCSVFile(Surat_CSV);

        for (String[] line : csv) {
            String id = line[0];
            // skip the header
            if (id.contains("ID")) {
                continue;
            }

            String type = line[1];
            String description = line[2];
            double lat = Double.parseDouble(line[3]);
            double lon = Double.parseDouble(line[4]);
            double alt = Double.parseDouble(line[5]);

            // add a placemark at each corner reflector
            addPin(trgProduct, id, lat, lon);
        }
    }

    private void computeCRGeoLocationError(Product trgProduct) throws IOException {

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(trgProduct);
        final double rgSpacing = absRoot.getAttributeDouble(AbstractMetadata.range_spacing);
        final double azSpacing = absRoot.getAttributeDouble(AbstractMetadata.azimuth_spacing);
        final GeoCoding geoCoding = trgProduct.getSceneGeoCoding();

        final List<String[]> csv = readCSVFile(Surat_CSV);
        PixelPos expCRPos = new PixelPos();

        for (String[] line : csv) {
            String id = line[0];
            // skip the header
            if (id.contains("ID")) {
                continue;
            }

            String type = line[1];
            String description = line[2];
            double lat = Double.parseDouble(line[3]);
            double lon = Double.parseDouble(line[4]);
            double alt = Double.parseDouble(line[5]);

            // find peak position in image in the neighbourhood of true CR position
            geoCoding.getPixelPos(new GeoPos(lat, lon), expCRPos);
            final PixelPos imgCRPos = findCRPosition(expCRPos, trgProduct);
            if (imgCRPos == null){
                continue;
            }

            // compute x and y shift in meters
            final double xShift = (imgCRPos.x - expCRPos.x) * rgSpacing;
            final double yShift = (imgCRPos.y - expCRPos.y) * azSpacing;
            System.out.println(id + ": CR_x = " + imgCRPos.x + ", CR_y = " + imgCRPos.y + ", exp_CR_x = " + expCRPos.x
            + ", exp_CR_y = " + expCRPos.y + ", xShift = " + xShift + ", yShift = " + yShift);
        }
    }

    private static PixelPos findCRPosition(final PixelPos expCRPos, final Product product) {

        final int maxShift = 16;
        final int xc = (int)expCRPos.x;
        final int yc = (int)expCRPos.y;
        final int x0 = xc - maxShift;
        final int y0 = yc - maxShift;
        final int xMax = xc + maxShift;
        final int yMax = yc + maxShift;
        if (x0 < 0 || y0 < 0 || xMax >= product.getSceneRasterWidth() || yMax >= product.getSceneRasterHeight()) {
            return null;
        }

        final Rectangle sourceRectangle = new Rectangle(x0, y0, xMax - x0 + 1,  yMax - y0 + 1);
        final Tile srcTile = getSourceTile(product.getBandAt(0), sourceRectangle);
        final ProductData srcData = srcTile.getDataBuffer();
        final TileIndex srcIndex = new TileIndex(srcTile);

        double maxPixelValue = 0.0;
        int maxPixelValueX = -1;
        int maxPixelValueY = -1;
        for (int y = y0; y <= yMax; ++y) {
            srcIndex.calculateStride(y);
            for (int x = x0; x <= xMax; ++x) {
                final int srcIdx = srcIndex.getIndex(x);
                final double v = srcData.getElemDoubleAt(srcIdx);
                if (v > maxPixelValue) {
                    maxPixelValue = v;
                    maxPixelValueX = x;
                    maxPixelValueY = y;
                }
            }
        }

        if (maxPixelValueX == -1 || maxPixelValueY == -1) {
            return null;
        } else {
            return new PixelPos(maxPixelValueX, maxPixelValueY);
        }
    }

    private static Tile getSourceTile(RasterDataNode rasterDataNode, Rectangle region) {
        MultiLevelImage image = rasterDataNode.getSourceImage();
        Raster awtRaster = image.getData(region);
        return new TileImpl(rasterDataNode, awtRaster);
    }
}
