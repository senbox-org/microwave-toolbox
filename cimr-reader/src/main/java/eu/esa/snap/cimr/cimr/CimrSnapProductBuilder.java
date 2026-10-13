package eu.esa.snap.cimr.cimr;

import eu.esa.snap.cimr.CimrReaderContext;
import eu.esa.snap.cimr.dddb.descriptor.CimrBandDescriptor;
import eu.esa.snap.cimr.dddb.descriptor.CimrSampleCoding;
import eu.esa.snap.cimr.dddb.descriptor.CimrSampleCodingEntry;
import eu.esa.snap.cimr.grid.CimrGrid;
import eu.esa.snap.cimr.grid.CimrGridMultiLevelSource;
import eu.esa.snap.cimr.grid.GridBandDataSource;
import eu.esa.snap.cimr.grid.GridCoverageDataSource;
import eu.esa.snap.cimr.grid.LazyCrsGeoCoding;
import eu.esa.snap.cimr.metadata.CimrProductMetadata;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.ColorPaletteDef;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.GeoCoding;
import org.esa.snap.core.datamodel.ImageInfo;
import org.esa.snap.core.datamodel.IndexCoding;
import org.esa.snap.core.datamodel.Mask;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.StxFactory;

import java.awt.Color;
import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;


public class CimrSnapProductBuilder {


    public static Product buildProduct(CimrReaderContext readerContext, CimrGridProduct cimrProduct, CimrProductMetadata metadata, String path) throws Exception {
        CimrGrid grid = cimrProduct.getGlobalGrid();
        Product product = new Product(metadata.getProductName(), metadata.getProductType(), grid.getWidth(), grid.getHeight());

        addMetadata(metadata, product);
        addGeoCoding(grid, product);
        addBands(cimrProduct, product);

        product.setFileLocation(new File(path));
        product.setAutoGrouping(readerContext.getAutoGrouping());

        return product;
    }

    private static void addMetadata(CimrProductMetadata metadata, Product product) {
        product.getMetadataRoot().addElement(metadata.getMetadataElement());
    }

    private static void addGeoCoding(CimrGrid grid, Product product) throws Exception {
        GeoCoding geoCoding = new LazyCrsGeoCoding(grid);
        product.setSceneGeoCoding(geoCoding);
    }


    private static void addBands(CimrGridProduct cimrProduct, Product product) {
        CimrGrid grid = cimrProduct.getGlobalGrid();
        Set<String> coverageKeys = new LinkedHashSet<>();

        for (Map.Entry<CimrBandDescriptor, GridBandDataSource> e : cimrProduct.getBands().entrySet()) {
            CimrBandDescriptor desc = e.getKey();
            GridBandDataSource dataSource = e.getValue();
            String coverageKey = getCoverageKey(desc);
            String coverageBandName = getCoverageBandName(desc);

            Band band = product.addBand(desc.getName(), desc.getRasterDataType());
            band.setDescription(desc.getDescription());
            band.setUnit(desc.getUnit());
            applyNoDataPolicy(band, desc);
            band.setSpectralWavelength(desc.getBand().getSpectralWaveLength());
            band.setValidPixelExpression(getCoverageExpression(coverageBandName));
            addSampleCoding(product, band, desc);

            CimrGridMultiLevelSource.attachToBand(band, dataSource, grid);
            addCoverageBandAndMask(product, grid, desc, dataSource, coverageKey, coverageKeys);
        }
    }

    private static void addCoverageBandAndMask(Product product, CimrGrid grid, CimrBandDescriptor desc,
                                               GridBandDataSource dataSource, String coverageKey,
                                               Set<String> coverageKeys) {
        if (!coverageKeys.add(coverageKey)) {
            return;
        }

        String coverageBandName = getCoverageBandName(desc);
        Band coverageBand = product.addBand(coverageBandName, ProductData.TYPE_UINT8);
        coverageBand.setDescription("CIMR grid coverage for " + getCoverageDescription(desc));
        coverageBand.setNoDataValueUsed(false);
        configureCoverageBandDisplay(coverageBand);
        CimrGridMultiLevelSource.attachToBand(coverageBand, new GridCoverageDataSource(dataSource), grid);

        String maskName = getCoverageMaskName(desc);
        Mask coverageMask = product.addMask(maskName, getCoverageExpression(coverageBandName),
                                            coverageBand.getDescription(), Color.CYAN, 0.5);
        configureCoverageMaskDisplay(coverageMask);
    }

    private static void configureCoverageBandDisplay(Band coverageBand) {
        coverageBand.setStx(new StxFactory()
                .withMinimum(0.0)
                .withMaximum(1.0)
                .withIntHistogram(true)
                .withHistogramBins(new int[512])
                .create());

        ColorPaletteDef.Point[] points = {
                new ColorPaletteDef.Point(0.0, Color.BLACK, "uncovered"),
                new ColorPaletteDef.Point(1.0, Color.WHITE, "covered")
        };
        ColorPaletteDef palette = new ColorPaletteDef(points, 256);
        coverageBand.setImageInfo(new ImageInfo(palette));
    }

    private static void configureCoverageMaskDisplay(Mask coverageMask) {
        coverageMask.setStx(new StxFactory()
                .withMinimum(0.0)
                .withMaximum(255.0)
                .withIntHistogram(true)
                .withHistogramBins(new int[512])
                .create());

        ColorPaletteDef.Point[] points = {
                new ColorPaletteDef.Point(0.0, Color.BLACK, "uncovered"),
                new ColorPaletteDef.Point(255.0, Color.WHITE, "covered")
        };
        ColorPaletteDef palette = new ColorPaletteDef(points, 256);
        coverageMask.setImageInfo(new ImageInfo(palette));
    }

    private static String getCoverageExpression(String coverageBandName) {
        return coverageBandName + " > 0.0";
    }

    private static String getCoverageKey(CimrBandDescriptor desc) {
        return desc.getBand().name() + "|" + desc.getFeedIndex();
    }

    private static String getCoverageBandName(CimrBandDescriptor desc) {
        return getCoverageName(desc, "coverage");
    }

    private static String getCoverageMaskName(CimrBandDescriptor desc) {
        return getCoverageName(desc, "coverage_mask");
    }

    private static String getCoverageName(CimrBandDescriptor desc, String name) {
        return desc.getBand().name() + "_" + name + "_feed_" + (desc.getFeedIndex() + 1);
    }

    private static String getCoverageDescription(CimrBandDescriptor desc) {
        return desc.getBand().name() + " feed " + desc.getFeedIndex();
    }

    private static void applyNoDataPolicy(Band band, CimrBandDescriptor desc) {
        if (desc.isFloatingPointRasterDataType()) {
            band.setNoDataValue(Double.NaN);
            band.setNoDataValueUsed(true);
        } else {
            // TODO: BL - 12/10/2026 - enable descriptor/DDDB fill values for integer, categorical, and flag bands.
            band.setNoDataValueUsed(false);
        }
    }

    private static void addSampleCoding(Product product, Band band, CimrBandDescriptor desc) {
        CimrSampleCoding sampleCoding = desc.getSampleCoding();
        if (sampleCoding == null) {
            return;
        }
        if (sampleCoding.getType() == CimrSampleCoding.Type.INDEX) {
            band.setSampleCoding(getOrCreateIndexCoding(product, sampleCoding));
        }
    }

    private static IndexCoding getOrCreateIndexCoding(Product product, CimrSampleCoding sampleCoding) {
        IndexCoding indexCoding = product.getIndexCodingGroup().get(sampleCoding.getName());
        if (indexCoding == null) {
            indexCoding = new IndexCoding(sampleCoding.getName());
            for (CimrSampleCodingEntry entry : sampleCoding.getEntries()) {
                indexCoding.addIndex(entry.getName(), entry.getValue(), entry.getDescription());
            }
            product.getIndexCodingGroup().add(indexCoding);
        }
        return indexCoding;
    }
}
