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
package eu.esa.sar.io.ceos.alos4;

import com.bc.ceres.core.VirtualDir;
import eu.esa.sar.io.ceos.alos.AlosPalsarConstants;
import eu.esa.sar.io.ceos.alos.AlosPalsarImageFile;
import eu.esa.sar.io.ceos.alos.AlosPalsarTrailerFile;
import eu.esa.sar.io.ceos.alos2.Alos2ProductDirectory;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Product directory for ALOS-4 PALSAR-3 CEOS products.
 * Extends ALOS-2 directory since the CEOS structure is the same.
 */
public class Alos4ProductDirectory extends Alos2ProductDirectory {

    public Alos4ProductDirectory(final VirtualDir dir) {
        super(dir);

        constants = new Alos4Constants();
        productDir = dir;
    }

    private static final String[] excludeExt = new String[]{".jpg", ".hdr"};

    private static boolean isValid(final String name) {
        for (String ext : excludeExt) {
            if (name.endsWith(ext))
                return false;
        }
        return true;
    }

    @Override
    protected void readProductDirectory() throws IOException {
        readVolumeDirectoryFileStream();

        updateProductType();

        leaderFile = new Alos4LeaderFile(getCEOSFile(constants.getLeaderFilePrefix())[0].imgInputStream);
        final CeosFile[] trlFile = getCEOSFile(constants.getTrailerFilePrefix());
        if (trlFile != null) {
            trailerFile = new AlosPalsarTrailerFile(trlFile[0].imgInputStream);
        }

        final CeosFile[] ceosFiles = getCEOSFile(constants.getImageFilePrefix());
        final List<AlosPalsarImageFile> imgArray = new ArrayList<>(ceosFiles.length);
        for (CeosFile imageFile : ceosFiles) {
            if (!isValid(imageFile.fileName)) {
                continue;
            }
            try {
                final AlosPalsarImageFile imgFile = new AlosPalsarImageFile(imageFile.imgInputStream,
                        getProductLevel(), imageFile.fileName);
                imgArray.add(imgFile);
            } catch (Exception e) {
                SystemUtils.LOG.warning("Unable to read ALOS-4 image file: " + e.getMessage());
            }
        }
        imageFiles = imgArray.toArray(new AlosPalsarImageFile[0]);

        sceneWidth = imageFiles[0].getRasterWidth();
        sceneHeight = imageFiles[0].getRasterHeight();

        if (leaderFile.getProductLevel() == AlosPalsarConstants.LEVEL1_0 ||
                leaderFile.getProductLevel() == AlosPalsarConstants.LEVEL1_1) {
            isProductSLC = true;
        }
    }

    public boolean isALOS4() throws IOException {
        final String volumeId = getVolumeId().toUpperCase();
        final String logicalVolumeId = getLogicalVolumeId().toUpperCase();
        return (volumeId.contains("ALOS4") || logicalVolumeId.contains("ALOS4"));
    }

    @Override
    protected String getMission() {
        return "ALOS4";
    }

    @Override
    protected String getProductDescription() {
        return Alos4Constants.PRODUCT_DESCRIPTION_PREFIX + leaderFile.getProductLevel();
    }

    @Override
    public Product createProduct() throws IOException {
        Product product = super.createProduct();
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.MISSION, "ALOS4");
        return product;
    }
}
