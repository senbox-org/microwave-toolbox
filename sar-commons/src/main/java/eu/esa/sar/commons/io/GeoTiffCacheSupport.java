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
package eu.esa.sar.commons.io;

import eu.esa.snap.core.dataio.cache.CacheDataProvider;
import eu.esa.snap.core.dataio.cache.CacheManager;
import eu.esa.snap.core.dataio.cache.DataBuffer;
import eu.esa.snap.core.dataio.cache.ProductCache;
import eu.esa.snap.core.dataio.cache.VariableDescriptor;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.util.SystemUtils;

import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import java.awt.Rectangle;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.SampleModel;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Shared ProductCache support for GeoTIFF-based SAR readers (Capella, Sentinel1).
 *
 * Uses a shared-raw caching model: raw int samples are cached once per
 * (ImageIOFile, imageID, sampleOffset) tuple. Bands that share the same raw
 * source (e.g. I/Q in an SLC product) share a single cached tile, with each
 * band's decoding applied on read.
 *
 * Toggle with system property: -Dsar.geotiff.useProductCache=true
 */
public class GeoTiffCacheSupport implements CacheDataProvider {

    public static boolean USE_PRODUCT_CACHE =
            Boolean.parseBoolean(System.getProperty("sar.geotiff.useProductCache", "true"));

    private static final int DEFAULT_TILE_DIM = 512;

    /**
     * Per-band decoder: transform a raw int[] tile into the product data buffer.
     */
    @FunctionalInterface
    public interface TileDecoder {
        void decode(int[] rawSamples, int numPixels, ProductData destBuffer);
    }

    private static final class RawSource {
        final ImageIOFile img;
        final int sampleOffset;
        final int sceneWidth;
        final int sceneHeight;
        final int tileWidth;
        final int tileHeight;

        RawSource(ImageIOFile img, int sampleOffset,
                  int sceneWidth, int sceneHeight, int tileWidth, int tileHeight) {
            this.img = img;
            this.sampleOffset = sampleOffset;
            this.sceneWidth = sceneWidth;
            this.sceneHeight = sceneHeight;
            this.tileWidth = tileWidth;
            this.tileHeight = tileHeight;
        }
    }

    private static final class BandEntry {
        final String rawKey;
        final TileDecoder decoder;

        BandEntry(String rawKey, TileDecoder decoder) {
            this.rawKey = rawKey;
            this.decoder = decoder;
        }
    }

    private ProductCache productCache;

    private final Map<String, BandEntry> bandEntries = new HashMap<>();
    private final Map<String, RawSource> rawSources = new HashMap<>();
    private final Map<String, String> sourceKeyToRawKey = new HashMap<>();

    /**
     * Register a band. The decoder transforms raw int samples into the band's
     * product data. Bands sharing the same (img, imageID, sampleOffset) will
     * share a single cached raw tile.
     */
    public void registerBand(String bandName, ImageIOFile img, int imageID, int sampleOffset,
                             TileDecoder decoder) throws IOException {
        final int effectiveSampleOffset = imageID + sampleOffset;
        final String sourceKey = System.identityHashCode(img) + ":" + effectiveSampleOffset;
        String rawKey = sourceKeyToRawKey.get(sourceKey);
        if (rawKey == null) {
            rawKey = "raw_" + sourceKeyToRawKey.size();
            sourceKeyToRawKey.put(sourceKey, rawKey);

            final int sceneWidth = img.getSceneWidth();
            final int sceneHeight = img.getSceneHeight();
            final int tileWidth = computeTileDim(img, true, sceneWidth);
            final int tileHeight = computeTileDim(img, false, sceneHeight);
            rawSources.put(rawKey, new RawSource(img, effectiveSampleOffset,
                    sceneWidth, sceneHeight, tileWidth, tileHeight));
        }
        bandEntries.put(bandName, new BandEntry(rawKey, decoder));
    }

    /**
     * Enable the cache. Call after all bands have been registered.
     */
    public void init(Product product) {
        if (USE_PRODUCT_CACHE && !bandEntries.isEmpty()) {
            productCache = new ProductCache(this);
            CacheManager.getInstance().register(productCache);
            SystemUtils.LOG.info("GeoTiff ProductCache enabled for " + product.getName());
        }
    }

    public boolean isActive() {
        return productCache != null;
    }

    /**
     * Read a tile for the given band through the shared raw cache, applying
     * the band's decoder.
     */
    public void readFromCache(String bandName, int destOffsetX, int destOffsetY,
                              int destWidth, int destHeight, ProductData destBuffer) throws IOException {
        final BandEntry entry = bandEntries.get(bandName);
        if (entry == null) {
            throw new IOException("Band not registered in cache: " + bandName);
        }

        final int numPixels = destWidth * destHeight;
        final ProductData rawData = ProductData.createInstance(ProductData.TYPE_INT32, numPixels);
        final int[] offsets = {destOffsetY, destOffsetX};
        final int[] shapes = {destHeight, destWidth};
        final DataBuffer rawBuffer = new DataBuffer(rawData, offsets, shapes);

        productCache.read(entry.rawKey, offsets, shapes, rawBuffer);

        entry.decoder.decode((int[]) rawData.getElems(), numPixels, destBuffer);
    }

    public void dispose() {
        if (productCache != null) {
            CacheManager.getInstance().remove(productCache);
            productCache = null;
        }
        bandEntries.clear();
        rawSources.clear();
        sourceKeyToRawKey.clear();
    }

    // --- CacheDataProvider ---

    @Override
    public VariableDescriptor getVariableDescriptor(String variableName) throws IOException {
        final RawSource src = rawSources.get(variableName);
        if (src == null) {
            throw new IOException("Raw source not known: " + variableName);
        }
        final VariableDescriptor desc = new VariableDescriptor();
        desc.name = variableName;
        desc.layers = -1;
        desc.width = src.sceneWidth;
        desc.height = src.sceneHeight;
        desc.tileWidth = src.tileWidth;
        desc.tileHeight = src.tileHeight;
        desc.dataType = ProductData.TYPE_INT32;
        return desc;
    }

    @Override
    public DataBuffer readCacheBlock(String variableName, int[] offsets, int[] shapes,
                                     ProductData targetData) throws IOException {
        final RawSource src = rawSources.get(variableName);
        if (src == null) {
            throw new IOException("Raw source not known: " + variableName);
        }

        final int tileHeight = shapes[0];
        final int tileWidth = shapes[1];
        final int numPixels = tileHeight * tileWidth;

        if (targetData == null) {
            targetData = ProductData.createInstance(ProductData.TYPE_INT32, numPixels);
        }
        final int[] dest = (int[]) targetData.getElems();

        final Rectangle region = new Rectangle(offsets[1], offsets[0], tileWidth, tileHeight);
        final ImageReader reader = src.img.getReader();

        synchronized (reader) {
            final ImageReadParam param = reader.getDefaultReadParam();
            param.setSourceRegion(region);
            final RenderedImage image = reader.readAsRenderedImage(0, param);
            final Raster data = image.getData(region);
            final SampleModel sampleModel = data.getSampleModel();
            sampleModel.getSamples(0, 0, tileWidth, tileHeight, src.sampleOffset, dest, data.getDataBuffer());
        }

        return new DataBuffer(targetData, offsets, shapes);
    }

    private static int computeTileDim(ImageIOFile img, boolean width, int sceneDim) {
        try {
            final ImageReader reader = img.getReader();
            final int nativeDim = width ? reader.getTileWidth(0) : reader.getTileHeight(0);
            if (nativeDim > 1 && nativeDim <= DEFAULT_TILE_DIM) {
                return nativeDim;
            }
        } catch (Exception ignored) {
            // fall through to default
        }
        return Math.min(DEFAULT_TILE_DIM, sceneDim);
    }
}
