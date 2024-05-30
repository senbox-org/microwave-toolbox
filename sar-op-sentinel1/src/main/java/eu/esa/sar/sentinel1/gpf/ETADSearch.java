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
package eu.esa.sar.sentinel1.gpf;

import org.esa.snap.core.datamodel.*;
import eu.esa.sar.cloud.opendata.DataSpaces;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.json.simple.JSONObject;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;

public class ETADSearch {

    private final DateFormat dateFormat = ProductData.UTC.createDateFormat("yyyy-MM-dd HH:mm:ss.sss");
    private final DataSpaces dataSpaces;

    public ETADSearch() throws IOException {
        this.dataSpaces = new DataSpaces();
    }

    public DataSpaces.Result[] search(final Product product) throws Exception {
        InputProductValidator inputProductValidator = new InputProductValidator(product);
        inputProductValidator.isSentinel1Product();

        MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
        final String acquisitionMode = absRoot.getAttributeString(AbstractMetadata.ACQUISITION_MODE);
        final String productType = getETADProductType(acquisitionMode);

        String startDate = getTime(product.getStartTime());
        String endDate = getTime(product.getEndTime());

        String query = dataSpaces.constructQuery("SENTINEL-1", productType, startDate, endDate);
        JSONObject response = dataSpaces.query(query);

        return dataSpaces.getResults(response);
    }

    public File download(final DataSpaces.Result result, final File outputFolder) throws Exception {
        return dataSpaces.download(result, outputFolder);
    }

    String getETADProductType(final String acquisitionMode) {
        if (acquisitionMode.equals("IW")) {
            return "IW_ETA__AX";
        } else if (acquisitionMode.equals("EW")) {
            return "EW_ETA__AX";
        } else if (acquisitionMode.equals("SM")) {
            return "SM_ETA__AX";
        } else if (acquisitionMode.equals("WV")) {
            return "WV_ETA__AX";
        }
        return "IW_ETA__AX";
    }

    String getTime(final ProductData.UTC time) {
        return dateFormat.format(time.getAsDate()).replace(" ", "T") +"Z";
    }
}
