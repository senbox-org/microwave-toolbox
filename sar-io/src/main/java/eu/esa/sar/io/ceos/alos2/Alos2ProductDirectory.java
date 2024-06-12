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
package eu.esa.sar.io.ceos.alos2;

import com.bc.ceres.core.VirtualDir;
import eu.esa.sar.io.ceos.alos.AlosPalsarConstants;
import eu.esa.sar.io.ceos.alos.AlosPalsarTrailerFile;
import eu.esa.sar.io.ceos.alos.AlosPalsarImageFile;
import eu.esa.sar.io.ceos.alos.AlosPalsarProductDirectory;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * This class represents a product directory.
 * <p/>
 * <p>This class is public for the benefit of the implementation of another (internal) class and its API may
 * change in future releases of the software.</p>
 */
public class Alos2ProductDirectory extends AlosPalsarProductDirectory {

    public Alos2ProductDirectory(final VirtualDir dir) {
        super(dir);

        constants = new Alos2Constants();
        productDir = dir;
    }

    private static final String[] excludeExt = new String[] {".jpg",".hdr"};

    private static boolean isValid(final String name) {
        for(String ext : excludeExt) {
            if(name.endsWith(ext))
                return false;
        }
        return true;
    }

    @Override
    protected void readProductDirectory() throws IOException {
        readVolumeDirectoryFileStream();

        updateProductType();

        leaderFile = new Alos2LeaderFile(getCEOSFile(constants.getLeaderFilePrefix())[0].imgInputStream);
        final CeosFile[] trlFile = getCEOSFile(constants.getTrailerFilePrefix());
        if (trlFile != null) {
            trailerFile = new AlosPalsarTrailerFile(trlFile[0].imgInputStream);
        }

        final CeosFile[] ceosFiles = getCEOSFile(constants.getImageFilePrefix());
        final List<AlosPalsarImageFile> imgArray = new ArrayList<>(ceosFiles.length);
        for (CeosFile imageFile : ceosFiles) {
            if(!isValid(imageFile.fileName)) {
                continue;
            }
            try {
                final AlosPalsarImageFile imgFile = new AlosPalsarImageFile(imageFile.imgInputStream,
                        getProductLevel(), imageFile.fileName);
                imgArray.add(imgFile);
            } catch (Exception e) {
                e.printStackTrace();
                // continue
            }
        }
        imageFiles = imgArray.toArray(new AlosPalsarImageFile[imgArray.size()]);

        sceneWidth = imageFiles[0].getRasterWidth();
        sceneHeight = imageFiles[0].getRasterHeight();
        for (final AlosPalsarImageFile imageFile : imageFiles) {
            if (sceneWidth != imageFile.getRasterWidth() || sceneHeight != imageFile.getRasterHeight()) {
                //throw new IOException("ALOS2 ScanSAR products are not currently supported.");
            }
        }

        if (leaderFile.getProductLevel() == AlosPalsarConstants.LEVEL1_0 ||
                leaderFile.getProductLevel() == AlosPalsarConstants.LEVEL1_1) {
            isProductSLC = true;
        }
    }

    public boolean isALOS2() throws IOException {
        final String volumeId = getVolumeId().toUpperCase();
        final String logicalVolumeId = getLogicalVolumeId().toUpperCase();
        return (volumeId.contains("ALOS2") || logicalVolumeId.contains("ALOS2"));
    }

    protected String getMission() {
        return "ALOS2";
    }

    protected String getProductDescription() {
        return Alos2Constants.PRODUCT_DESCRIPTION_PREFIX + leaderFile.getProductLevel();
    }
    @Override
    public Product createProduct() throws IOException{
        Product product = super.createProduct();
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.bistatic_correction_applied, 1);

        return product;
    }
}
