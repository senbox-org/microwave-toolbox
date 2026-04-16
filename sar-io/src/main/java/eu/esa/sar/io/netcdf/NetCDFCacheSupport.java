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
package eu.esa.sar.io.netcdf;

import eu.esa.snap.core.dataio.cache.CacheDataProvider;
import eu.esa.snap.core.dataio.cache.CacheManager;
import eu.esa.snap.core.dataio.cache.DataBuffer;
import eu.esa.snap.core.dataio.cache.ProductCache;
import eu.esa.snap.core.dataio.cache.VariableDescriptor;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.datamodel.Unit;
import ucar.ma2.Array;
import ucar.ma2.InvalidRangeException;
import ucar.nc2.Attribute;
import ucar.nc2.NetcdfFile;
import ucar.nc2.Variable;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Shared ProductCache support for NetCDF-based SAR readers (Cosmo-SkyMed, ICEYE, Kompsat5).
 * Encapsulates the cache lifecycle and CacheDataProvider implementation.
 *
 * Toggle with system property: -Dsar.netcdf.useProductCache=true
 */
public class NetCDFCacheSupport implements CacheDataProvider {

    public static final boolean USE_PRODUCT_CACHE =
            Boolean.parseBoolean(System.getProperty("sar.netcdf.useProductCache", "true"));

    private ProductCache productCache;

    // Band name → Variable mapping
    private final Map<String, Variable> bandNameToVariable = new HashMap<>();

    // Tracks which bands want the imaginary component (for 3D complex variables)
    private final Set<String> imaginaryBandNames = new HashSet<>();

    // Reader-specific state passed at init
    private NetcdfFile netcdfFile;
    private boolean yFlipped;
    private int sceneHeight;

    // Optional: short-to-float conversion flag (Kompsat5)
    private boolean convertShortToFloat;

    /**
     * Initialize the cache support after bands have been created.
     *
     * @param product     the product (used for band lookup and scene dimensions)
     * @param bandMap     the reader's band→Variable mapping
     * @param netcdfFile  the NetCDF file handle (for synchronized reads)
     * @param yFlipped    whether Y-axis is flipped in the file
     * @param convertShortToFloat  whether to convert short data to float (Kompsat5)
     */
    public void init(Product product, Map<Band, Variable> bandMap, NetcdfFile netcdfFile,
                     boolean yFlipped, boolean convertShortToFloat) {
        this.netcdfFile = netcdfFile;
        this.yFlipped = yFlipped;
        this.sceneHeight = product.getSceneRasterHeight();
        this.convertShortToFloat = convertShortToFloat;

        for (Map.Entry<Band, Variable> entry : bandMap.entrySet()) {
            final Band band = entry.getKey();
            bandNameToVariable.put(band.getName(), entry.getValue());
            if (Unit.IMAGINARY.equals(band.getUnit()) || band.getName().startsWith("q_")
                    || band.getName().startsWith("q ")) {
                imaginaryBandNames.add(band.getName());
            }
        }

        if (USE_PRODUCT_CACHE) {
            productCache = new ProductCache(this);
            CacheManager.getInstance().register(productCache);
            SystemUtils.LOG.info("NetCDF ProductCache enabled for " + product.getName());
        }
    }

    /**
     * @return true if the ProductCache is active and should be used for reads.
     */
    public boolean isActive() {
        return productCache != null;
    }

    /**
     * Read a tile through the ProductCache.
     */
    public void readFromCache(String bandName, int destOffsetX, int destOffsetY,
                              int destWidth, int destHeight, ProductData destBuffer) throws IOException {
        final int[] offsets = {destOffsetY, destOffsetX};
        final int[] shapes = {destHeight, destWidth};
        final DataBuffer target = new DataBuffer(destBuffer, offsets, shapes);
        productCache.read(bandName, offsets, shapes, target);
    }

    /**
     * Dispose the cache and clear internal state.
     */
    public void dispose() {
        if (productCache != null) {
            CacheManager.getInstance().remove(productCache);
            productCache = null;
        }
        bandNameToVariable.clear();
        imaginaryBandNames.clear();
    }

    // --- CacheDataProvider implementation ---

    @Override
    public VariableDescriptor getVariableDescriptor(String variableName) throws IOException {
        final Variable variable = bandNameToVariable.get(variableName);
        if (variable == null) {
            throw new IOException("Variable not known: " + variableName);
        }

        final VariableDescriptor desc = new VariableDescriptor();
        desc.name = variableName;
        desc.layers = -1;

        // For complex 3D variables [rows, width, 2], the band data type is determined
        // by the first two dimensions. Use the band's actual data type.
        final int[] shape = variable.getShape();
        if (shape.length >= 2) {
            desc.height = shape[0];
            desc.width = shape[1];
        } else {
            throw new IOException("Variable has insufficient dimensions: " + variableName);
        }

        // Determine output data type
        if (convertShortToFloat &&
                (variable.getDataType() == ucar.ma2.DataType.SHORT || variable.getDataType() == ucar.ma2.DataType.USHORT)) {
            desc.dataType = ProductData.TYPE_FLOAT32;
        } else {
            desc.dataType = NetCDFUtils.getProductDataType(variable);
        }

        // Use HDF5 chunk sizes for tile dimensions if available
        final Attribute chunkSizes = variable.findAttribute("_ChunkSizes");
        if (chunkSizes != null && chunkSizes.getLength() >= 2) {
            final ucar.ma2.Array chunkValues = chunkSizes.getValues();
            desc.tileHeight = chunkValues.getInt(0);
            desc.tileWidth = chunkValues.getInt(1);
        } else {
            desc.tileHeight = Math.min(512, desc.height);
            desc.tileWidth = Math.min(512, desc.width);
        }

        return desc;
    }

    @Override
    public DataBuffer readCacheBlock(String variableName, int[] offsets, int[] shapes,
                                     ProductData targetData) throws IOException {
        final Variable variable = bandNameToVariable.get(variableName);
        if (variable == null) {
            throw new IOException("Variable not known: " + variableName);
        }

        final int tileHeight = shapes[0];
        final int tileWidth = shapes[1];
        final int numPixels = tileHeight * tileWidth;

        // Determine the data type for allocation
        final int dataType;
        if (convertShortToFloat &&
                (variable.getDataType() == ucar.ma2.DataType.SHORT || variable.getDataType() == ucar.ma2.DataType.USHORT)) {
            dataType = ProductData.TYPE_FLOAT32;
        } else {
            dataType = NetCDFUtils.getProductDataType(variable);
        }

        if (targetData == null) {
            targetData = ProductData.createInstance(dataType, numPixels);
        }

        // Compute file-space Y coordinate (handle yFlipped)
        final int productY = offsets[0];
        final int fileY = yFlipped ? (sceneHeight - productY - tileHeight) : productY;

        final int rank = variable.getRank();
        final boolean isComplex3D = rank >= 3;
        final boolean wantImag = imaginaryBandNames.contains(variableName);

        // Build origin/shape arrays for the NetCDF read
        final int[] origin = new int[rank];
        final int[] readShape = new int[rank];
        for (int i = 0; i < rank; i++) {
            origin[i] = 0;
            readShape[i] = 1;
        }
        origin[0] = fileY;
        readShape[0] = tileHeight;
        origin[1] = offsets[1];
        readShape[1] = tileWidth;
        if (isComplex3D) {
            origin[2] = wantImag ? 1 : 0;
            readShape[2] = 1;
        }

        final Array rawBuffer;
        synchronized (netcdfFile) {
            try {
                rawBuffer = variable.read(origin, readShape);
            } catch (InvalidRangeException e) {
                throw new IOException(e);
            }
        }

        // Copy data into targetData, handling type conversion and yFlip
        if (convertShortToFloat &&
                (variable.getDataType() == ucar.ma2.DataType.SHORT || variable.getDataType() == ucar.ma2.DataType.USHORT)) {
            final short[] src = (short[]) rawBuffer.get1DJavaArray(ucar.ma2.DataType.SHORT);
            final float[] dest = (float[]) targetData.getElems();
            if (yFlipped) {
                for (int row = 0; row < tileHeight; row++) {
                    final int srcRow = tileHeight - 1 - row;
                    for (int x = 0; x < tileWidth; x++) {
                        dest[row * tileWidth + x] = src[srcRow * tileWidth + x];
                    }
                }
            } else {
                for (int i = 0; i < src.length; i++) {
                    dest[i] = src[i];
                }
            }
        } else {
            final Object srcElems = rawBuffer.get1DJavaArray(rawBuffer.getElementType());
            if (yFlipped) {
                // Flip rows: product row 0 = file row (tileHeight-1)
                final int elemSize = rawBuffer.getElementType() == float.class ? 4 :
                        rawBuffer.getElementType() == short.class ? 2 :
                                rawBuffer.getElementType() == int.class ? 4 : 1;
                for (int row = 0; row < tileHeight; row++) {
                    final int srcRow = tileHeight - 1 - row;
                    System.arraycopy(srcElems, srcRow * tileWidth, targetData.getElems(), row * tileWidth, tileWidth);
                }
            } else {
                targetData.setElems(srcElems);
            }
        }

        return new DataBuffer(targetData, offsets, shapes);
    }
}
