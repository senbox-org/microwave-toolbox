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
package eu.esa.microwave.dat.toolviews.Projects;

import eu.esa.microwave.dat.dialogs.ProductSetDialog;
import org.esa.snap.core.dataop.downloadable.XMLSupport;
import org.esa.snap.engine_utilities.util.ProductFunctions;
import org.esa.snap.rcp.util.Dialogs;
import org.jdom2.Attribute;
import org.jdom2.Content;
import org.jdom2.Document;
import org.jdom2.Element;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Defines a list of Products
 * User: lveci
 * Date: Aug 20, 2008
 */
public final class ProductSet {

    private List<File> fileList = new ArrayList<>(10);
    private File productSetFile = null;
    private final File productSetFolder;
    private String name = null;

    ProductSet(final File path) {

        productSetFolder = path.getParentFile();
        setName(path.getName());
    }

    public File[] getFileList() {
        return fileList.toArray(new File[0]);
    }

    public void setFileList(final File[] inFileList) {
        fileList.clear();
        fileList.addAll(Arrays.asList(inFileList));
    }

    public File getFile() {
        return productSetFile;
    }

    boolean addProduct(final File file) {

        if (ProductFunctions.isValidProduct(file)) {
            fileList.add(file);
            return true;
        }
        return false;
    }

    public void setName(String newName) {
        if (!Objects.equals(name, newName)) {
            if (productSetFile != null && productSetFile.exists())
                productSetFile.delete();
            if (!newName.toLowerCase().endsWith(".xml"))
                newName += ".xml";
            name = newName;
            productSetFile = new File(productSetFolder, name);
        }
    }

    public String getName() {
        return name;
    }

    public void Save() throws IOException {

        final Element root = new Element("ProjectSet");
        final Document doc = new Document(root);

        for (File file : fileList) {
            final Element fileElem = new Element("product");
            fileElem.setAttribute("path", file.getAbsolutePath());
            root.addContent(fileElem);
        }

        XMLSupport.SaveXML(doc, productSetFile.getAbsolutePath());
    }

    boolean Load(final File file) {

        if (!file.exists())
            return false;
        Document doc;
        try {
            doc = XMLSupport.LoadXML(file.getAbsolutePath());
        } catch (IOException e) {
            Dialogs.showError("Unable to load " + file.toString() + ": " + e.getMessage());
            return false;
        }

        fileList = new ArrayList<>(10);
        Element root = doc.getRootElement();

        final List<Content> children = root.getContent();
        for (Object aChild : children) {
            if (aChild instanceof Element) {
                final Element child = (Element) aChild;
                if (child.getName().equals("product")) {
                    final Attribute attrib = child.getAttribute("path");
                    fileList.add(new File(attrib.getValue()));
                }
            }
        }
        return true;
    }

    public static void OpenProductSet(File file) {
        final ProductSet prodSet = new ProductSet(file);
        prodSet.Load(file);
        final ProductSetDialog dlg = new ProductSetDialog("ProductSet", prodSet);
        dlg.show();
    }

    public static void AddProduct(File productSetFile, File inputFile) {
        final ProductSet prodSet = new ProductSet(productSetFile);
        prodSet.Load(productSetFile);
        if (prodSet.addProduct(inputFile)) {
            final ProductSetDialog dlg = new ProductSetDialog("ProductSet", prodSet);
            dlg.show();
        }
    }

    public static String GetListAsString(File productSetFile) {
        final ProductSet prodSet = new ProductSet(productSetFile);
        prodSet.Load(productSetFile);

        final StringBuilder listStr = new StringBuilder(256);
        final File[] fileList = prodSet.getFileList();
        for (File file : fileList) {
            listStr.append(file.getAbsolutePath());
            listStr.append('\n');
        }

        return listStr.toString();
    }
}
