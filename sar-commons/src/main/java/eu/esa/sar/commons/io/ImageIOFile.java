/*
 * Copyright (C) 2015 by Array Systems Computing Inc. http://www.array.ca
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

import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.util.ZipUtils;
import org.esa.snap.runtime.Config;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.stream.FileCacheImageInputStream;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.*;
import java.awt.image.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Iterator;

/**
 * Reader for ImageIO File
 */
public class ImageIOFile {

    private final String name;
    private int sceneWidth = 0;
    private int sceneHeight = 0;
    private int dataType;
    private int numImages;
    private int numBands;
    private ImageInfo imageInfo = null;
    private IndexCoding indexCoding = null;
    private boolean isIndexed = false;
    private final File productInputFile;

    private ImageInputStream stream;
    private ImageReader reader;

    private static final boolean useFileCache = Config.instance().preferences().getBoolean("s1tbx.readers.useFileCache", false);

    public ImageIOFile(final File inputFile, final ImageReader iioReader,
                       final File productInputFile) throws IOException {
        this(inputFile.getName(), ImageIO.createImageInputStream(inputFile), iioReader, productInputFile);
    }

    public ImageIOFile(final String name, final ImageInputStream inputStream, final ImageReader iioReader,
                       final File productInputFile) throws IOException {

        this.name = name;
        this.stream = inputStream;
        if (stream == null)
            throw new IOException("Unable to open ");
        this.productInputFile = productInputFile;

        createReader(iioReader);
    }

    public ImageIOFile(final String name, final ImageInputStream inputStream, final ImageReader iioReader,
                       final int numImages, final int numBands, final int dataType,
                       final File productInputFile) throws IOException {

        this.name = name;
        this.stream = inputStream;
        if (stream == null)
            throw new IOException("Unable to open ");

        reader = iioReader;
        initReader();

        this.numImages = numImages;
        this.numBands = numBands;
        this.dataType = dataType;
        this.productInputFile = productInputFile;
    }

    public void initReader() {
        if (reader != null) {
            reader.setInput(stream, false, true);
        }
    }

    private synchronized void createReader(final ImageReader iioReader) throws IOException {

        reader = iioReader;
        initReader();

        numImages = reader.getNumImages(!reader.isSeekForwardOnly());
        if(numImages < 0)
            numImages = 1;
        numBands = 3;

        dataType = ProductData.TYPE_INT32;
        final ImageTypeSpecifier its = reader.getRawImageType(0);
        if (its != null) {
            numBands = reader.getRawImageType(0).getNumBands();
            dataType = bufferImageTypeToProductType(its.getBufferedImageType());

            if (its.getBufferedImageType() == BufferedImage.TYPE_BYTE_INDEXED) {
                isIndexed = true;
                createIndexedImageInfo(its.getColorModel());
            }
        }
    }

    public String getName() {
        return name;
    }

    public ImageInputStream getStream() {
        return stream;
    }

    public static ImageReader getIIOReader(final File inputFile) throws IOException {
        final ImageInputStream stream = ImageIO.createImageInputStream(inputFile);
        if (stream == null)
            throw new IOException("Unable to open " + inputFile.toString());

        final Iterator<ImageReader> imageReaders = ImageIO.getImageReaders(stream);
        if (!imageReaders.hasNext())
            throw new IOException("No ImageIO reader found for " + inputFile.toString());

        return imageReaders.next();
    }

    public ImageReader getReader() throws IOException {
        if (reader == null) {
            throw new IOException("no reader created");
        }
        return reader;
    }

    private static int bufferImageTypeToProductType(int biType) {
        switch (biType) {
            case BufferedImage.TYPE_CUSTOM:
            case BufferedImage.TYPE_INT_RGB:
            case BufferedImage.TYPE_INT_ARGB:
            case BufferedImage.TYPE_INT_ARGB_PRE:
            case BufferedImage.TYPE_INT_BGR:
                return ProductData.TYPE_INT32;
            case BufferedImage.TYPE_3BYTE_BGR:
            case BufferedImage.TYPE_4BYTE_ABGR:
            case BufferedImage.TYPE_4BYTE_ABGR_PRE:
                return ProductData.TYPE_INT16;
            case BufferedImage.TYPE_USHORT_565_RGB:
            case BufferedImage.TYPE_USHORT_555_RGB:
            case BufferedImage.TYPE_USHORT_GRAY:
                return ProductData.TYPE_UINT16;
            case BufferedImage.TYPE_BYTE_GRAY:
            case BufferedImage.TYPE_BYTE_BINARY:
            case BufferedImage.TYPE_BYTE_INDEXED:
                return ProductData.TYPE_INT8;
        }
        return ProductData.TYPE_UNDEFINED;
    }

    final void createIndexedImageInfo(ColorModel colorModel) {
        final IndexColorModel indexColorModel = (IndexColorModel) colorModel;
        indexCoding = new IndexCoding("color_map");
        final int colorCount = indexColorModel.getMapSize();
        final ColorPaletteDef.Point[] points = new ColorPaletteDef.Point[colorCount];
        for (int j = 0; j < colorCount; j++) {
            final String name = "I%3d";
            indexCoding.addIndex(String.format(name, j), j, "");
            points[j] = new ColorPaletteDef.Point(j, new Color(indexColorModel.getRGB(j)), name);
        }

        imageInfo = new ImageInfo(new ColorPaletteDef(points, points.length));
    }

    public boolean isIndexed() {
        return isIndexed;
    }

    public IndexCoding getIndexCoding() {
        return indexCoding;
    }

    public ImageInfo getImageInfo() {
        return imageInfo;
    }

    public void close() throws IOException {
        if (stream != null)
            stream.close();
        if (reader != null)
            reader.dispose();
    }

    public int getSceneWidth() throws IOException {
        if (sceneWidth == 0) {
            sceneWidth = reader.getWidth(0);
        }
        return sceneWidth;
    }

    public int getSceneHeight() throws IOException {
        if (sceneHeight == 0) {
            sceneHeight = reader.getHeight(0);
        }
        return sceneHeight;
    }

    public int getDataType() {
        return dataType;
    }

    public void setDataType(final int dataType) {
        this.dataType = dataType;
    }

    public int getNumImages() {
        return numImages;
    }

    public int getNumBands() {
        return numBands;
    }

    public void readImageIORasterBand(final int sourceOffsetX, final int sourceOffsetY,
                                      final int sourceStepX, final int sourceStepY,
                                      final ProductData destBuffer,
                                      final int destOffsetX, final int destOffsetY,
                                      final int destWidth, final int destHeight,
                                      final int imageID,
                                      final int bandSampleOffset) throws IOException {
        final ImageReadParam param = reader.getDefaultReadParam();
        param.setSourceSubsampling(sourceStepX, sourceStepY,
                sourceOffsetX % sourceStepX,
                sourceOffsetY % sourceStepY);
        
        // Read only the required region
        param.setSourceRegion(new Rectangle(destOffsetX, destOffsetY, destWidth, destHeight));
        
        final Raster data;
        synchronized (reader) {
            data = getData(param, destOffsetX, destOffsetY, destWidth, destHeight);
        }

        final DataBuffer dataBuffer = data.getDataBuffer();
        final SampleModel sampleModel = data.getSampleModel();
        final int dataBufferType = dataBuffer.getDataType();
        final int sampleOffset = imageID + bandSampleOffset;
        final Object dest = destBuffer.getElems();

        try {
            // Use direct buffer access when possible
            if (dest instanceof int[] && (dataBufferType == DataBuffer.TYPE_USHORT || dataBufferType == DataBuffer.TYPE_SHORT
                    || dataBufferType == DataBuffer.TYPE_INT)) {
                sampleModel.getSamples(0, 0, destWidth, destHeight, sampleOffset, (int[]) dest, dataBuffer);
            } else if (dataBufferType == DataBuffer.TYPE_FLOAT && dest instanceof float[]) {
                sampleModel.getSamples(0, 0, destWidth, destHeight, sampleOffset, (float[]) dest, dataBuffer);
            } else if (dataBufferType == DataBuffer.TYPE_DOUBLE && dest instanceof double[]) {
                sampleModel.getSamples(0, 0, destWidth, destHeight, sampleOffset, (double[]) dest, dataBuffer);
            } else {
                // Fallback to using a temporary array
                final double[] dArray = new double[destWidth * destHeight];
                sampleModel.getSamples(0, 0, destWidth, destHeight, sampleOffset, dArray, dataBuffer);

                if (dest instanceof double[]) {
                    System.arraycopy(dArray, 0, dest, 0, dArray.length);
                } else {
                    for (int i = 0; i < dArray.length; i++) {
                        destBuffer.setElemDoubleAt(i, dArray[i]);
                    }
                }
            }
        } catch (Exception e) {
            SystemUtils.LOG.warning("Error reading image data: " + e.getMessage());
            if (dest instanceof double[]) {
                Arrays.fill((double[]) dest, 0.0);
            } else if (dest instanceof float[]) {
                Arrays.fill((float[]) dest, 0.0f);
            } else if (dest instanceof int[]) {
                Arrays.fill((int[]) dest, 0);
            }
        }
    }

    private Raster getData(final ImageReadParam param,
                                        final int destOffsetX, final int destOffsetY,
                                        final int destWidth, final int destHeight) throws IOException {
        try {
            final RenderedImage image = reader.readAsRenderedImage(0, param);
            return image.getData(new Rectangle(destOffsetX, destOffsetY, destWidth, destHeight));
        } catch (Exception e) {
            if(ZipUtils.isZip(productInputFile) && !ZipUtils.isValid(productInputFile)) {
                throw new IOException("Zip file is corrupt "+productInputFile.getName());
            }
            throw e;
        }
    }

    public static class BandInfo {
        public final int imageID;
        public final int bandSampleOffset;
        public final ImageIOFile img;
        public final boolean isImaginary;
        public boolean isComplexSample;
        public Band realBand, imaginaryBand;

        public BandInfo(final Band band, final ImageIOFile imgFile, final int id, final int offset) {
            img = imgFile;
            imageID = id;
            bandSampleOffset = offset;
            isImaginary = band.getUnit() != null && band.getUnit().equals(Unit.IMAGINARY);
            isComplexSample = false;
            if(isImaginary) {
                imaginaryBand = band;
            } else {
                realBand = band;
            }
        }

        public void setComplexSample(final boolean isComplexSample) {
            this.isComplexSample = isComplexSample;
        }

        public void setRealBand(final Band band) {
            this.realBand = band;
        }

        public void setImaginaryBand(final Band band) {
            this.imaginaryBand = band;
        }
    }

    public static File createCacheDir() throws IOException {
        final File cacheDir = new File(SystemUtils.getCacheDir(), "temp");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new IOException("Failed to create directory '" + cacheDir + "'.");
        }
        return cacheDir;
    }

    public static ImageInputStream createImageInputStream(final InputStream inStream, final Dimension bandDimensions) throws IOException {
        final long freeMemory = Runtime.getRuntime().freeMemory() / 1024 / 1024;
        final long size = bandDimensions.width / 1024 * bandDimensions.height /1024 * 32L;
        if(useFileCache || freeMemory < 100L || size > 15000L) {
            SystemUtils.LOG.info("Using FileCacheImageInputStream");
            return new FileCacheImageInputStream(inStream, createCacheDir());
        }
        return new MemoryCacheImageInputStream(inStream);
    }
}
