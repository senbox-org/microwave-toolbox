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
package eu.esa.sar.commons.io;

import org.esa.snap.core.datamodel.MetadataAttribute;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.util.io.FileUtils;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class AbstractProductDirectoryTest {

    private AbstractProductDirectory productDirectory;
    private File testFile;

    @Before
    public void setUp() throws IOException {
        testFile = File.createTempFile("testFile", ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(testFile))) {
            ZipEntry entry = new ZipEntry("emptyFile.txt");
            zos.putNextEntry(entry);
            zos.closeEntry();
        }
        productDirectory = new TestDirectory(testFile);
    }

    @Test
    public void createProductDir_withZipFile() {
        productDirectory.createProductDir(testFile);
        assertNotNull(productDirectory.getProductDir());
        assertEquals(FileUtils.getFilenameWithoutExtension(testFile), productDirectory.getBaseName());
    }

    @Test
    public void createProductDir_withDirectory() throws IOException {
        File dir = File.createTempFile("testDir", "");
        dir.mkdir();
        productDirectory.createProductDir(dir);
        assertNotNull(productDirectory.getProductDir());
        assertEquals(dir.getParentFile().getName(), productDirectory.getBaseName());
        dir.delete();
    }

    @Test
    public void getElem_withExistingElement() {
        MetadataElement element = new MetadataElement("base");
        element.addElement(new MetadataElement("test"));
        assertEquals(element.getElement("test"), AbstractProductDirectory.getElem(element, "test"));
    }

    @Test
    public void getDouble_withExistingAttribute() {
        MetadataElement element = new MetadataElement("test");
        MetadataAttribute attribute = new MetadataAttribute("test", ProductData.TYPE_FLOAT64);
        attribute.getData().setElemDouble(1.0);
        element.addAttribute(attribute);
        assertEquals(1.0, AbstractProductDirectory.getDouble(element, "test"), 0.0);
    }

    @Test
    public void getInt_withExistingAttribute() {
        MetadataElement element = new MetadataElement("test");
        MetadataAttribute attribute = new MetadataAttribute("test", ProductData.TYPE_INT32);
        attribute.getData().setElemInt(1);
        element.addAttribute(attribute);
        assertEquals(1, AbstractProductDirectory.getInt(element, "test"));
    }

    @Test
    public void getString_withExistingAttribute() {
        MetadataElement element = new MetadataElement("test");
        MetadataAttribute attribute = new MetadataAttribute("test", ProductData.TYPE_ASCII);
        attribute.getData().setElems("value");
        element.addAttribute(attribute);
        assertEquals("value", AbstractProductDirectory.getString(element, "test"));
    }


    private static class TestDirectory extends JSONProductDirectory {

        public TestDirectory(File headerFile) {
            super(headerFile);
        }

        @Override
        protected void addImageFile(String imgPath, MetadataElement newRoot) {
        }

        @Override
        protected void addBands(Product product) {
        }

        @Override
        protected void addGeoCoding(Product product) {
        }

        @Override
        protected void addTiePointGrids(Product product) {
        }

        @Override
        protected void addAbstractedMetadataHeader(MetadataElement root) {
        }

        @Override
        protected String getProductName() {
            return "";
        }

        @Override
        protected String getProductType() {
            return "";
        }
    }
}