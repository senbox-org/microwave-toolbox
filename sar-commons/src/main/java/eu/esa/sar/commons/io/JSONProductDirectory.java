/*
 * Copyright (C) 2021 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.commons.io;

import eu.esa.sar.cloud.json.JSON;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.metadata.AbstractMetadataIO;
import org.jdom2.Element;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.io.File;
import java.io.IOException;

public abstract class JSONProductDirectory extends AbstractProductDirectory {

    protected JSONObject json;

    public JSONProductDirectory(final File headerFile) {
        super(headerFile);
    }

    public void readProductDirectory() throws IOException {
        try {
            final File headerFile = getFile(getRootFolder() + getHeaderFileName());
            this.json = (JSONObject) JSON.loadJSON(headerFile);
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    protected MetadataElement addMetaData() throws IOException {
        final MetadataElement root = new MetadataElement(Product.METADATA_ROOT_NAME);
        AbstractMetadataIO.AddXMLMetadata(jsonToXML("ProductMetadata", json), AbstractMetadata.addOriginalProductMetadata(root));

        addAbstractedMetadataHeader(root);

        return root;
    }

    private static String clean(final String name) {
        return name.replaceAll(":", "");
    }

    public static Element jsonToXML(final String name, final JSONObject json) {
        final Element root = new Element(name);

        for(Object key : json.keySet()) {
            Object obj = json.get(key);
            String keyName = clean((String)key);
            if(obj instanceof JSONObject) {
                root.addContent(jsonToXML(keyName, (JSONObject) obj));
            } else if(obj instanceof JSONArray) {
                root.addContent(jsonArrayToXML(keyName, (JSONArray)obj));
            } else {
                root.setAttribute(keyName, String.valueOf(obj));
            }
        }
        return root;
    }

    private static Element jsonArrayToXML(final String name, final JSONArray jsonArray) {
        final Element root = new Element(name);

        int cnt = 1;
        for(Object obj : jsonArray) {
            if(obj instanceof JSONObject) {
                root.addContent(jsonToXML(name, (JSONObject) obj));
            } else if(obj instanceof JSONArray) {
                root.addContent(jsonArrayToXML(name, (JSONArray)obj));
            } else {
                root.setAttribute(name + cnt, String.valueOf(obj));
                ++cnt;
            }
        }
        return root;
    }

    protected abstract void addAbstractedMetadataHeader(final MetadataElement root) throws IOException;
}
