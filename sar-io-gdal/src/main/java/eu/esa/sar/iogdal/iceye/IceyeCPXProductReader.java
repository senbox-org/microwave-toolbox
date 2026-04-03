package eu.esa.sar.iogdal.iceye;

import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.core.dataio.ProductReaderPlugIn;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.VirtualBand;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import org.esa.snap.core.datamodel.Product;

public class IceyeCPXProductReader extends IceyeAMLCPXProductReader {

    public IceyeCPXProductReader(ProductReaderPlugIn readerPlugIn) {
        super(readerPlugIn);
    }

    void addProductSpecificMetadata(MetadataElement absRoot) {
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SAMPLE_TYPE, IceyeConstants.COMPLEX);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.multilook_flag, 0);
    }

    void addProductSpecificBands(Product product, String polarization) {
        // Phase band from GDAL (band index 1)
        Band gdalPhaseBand = bandProduct.getBandAt(IceyeConstants.PHASE_BAND_INDEX);

        final Band phaseBand = new Band(IceyeConstants.phase_band_prefix + polarization,
                gdalPhaseBand.getDataType(), imageWidth, imageHeight);
        phaseBand.setUnit(Unit.PHASE);
        phaseBand.setNoDataValue(99999.0);
        phaseBand.setNoDataValueUsed(true);
        phaseBand.setSourceImage(gdalPhaseBand.getSourceImage());
        product.addBand(phaseBand);
        bandMap.put(phaseBand, IceyeConstants.PHASE_BAND_INDEX);

        // Virtual I band
        String ampBandName = IceyeConstants.amplitude_band_prefix + polarization;
        String phsBandName = IceyeConstants.phase_band_prefix + polarization;

        final Band iBand = new VirtualBand(IceyeConstants.i_band_prefix + polarization,
                ProductData.TYPE_FLOAT32, imageWidth, imageHeight,
                ampBandName + " * cos(" + phsBandName + ")");
        iBand.setUnit(Unit.REAL);
        iBand.setNoDataValue(0);
        iBand.setNoDataValueUsed(true);
        product.addBand(iBand);

        // Virtual Q band
        final Band qBand = new VirtualBand(IceyeConstants.q_band_prefix + polarization,
                ProductData.TYPE_FLOAT32, imageWidth, imageHeight,
                ampBandName + " * sin(" + phsBandName + ")");
        qBand.setUnit(Unit.IMAGINARY);
        qBand.setNoDataValue(0);
        qBand.setNoDataValueUsed(true);
        product.addBand(qBand);

        ReaderUtils.createVirtualIntensityBand(product, iBand, qBand, "_" + polarization);
    }

    float[] getSlantRangeTimeList(int gridWidth, int gridHeight, int subSamplingX) {
        float[] slantRangeTimeList = new float[gridWidth * gridHeight];
        double slantRangeToFirstPixel = (double) getFromJSON(IceyeConstants.slant_range_to_first_pixel);
        double rangeSpacing = (double) getFromJSON(IceyeConstants.range_spacing);
        for (int i = 0; i < gridHeight; i++) {
            for (int j = 0; j < gridWidth; j++) {
                double slantRangeDist = slantRangeToFirstPixel + rangeSpacing * subSamplingX * j;
                slantRangeTimeList[i * gridWidth
                        + j] = (float) (slantRangeDist / Constants.halfLightSpeed * Constants.sTOns);
            }
        }
        return slantRangeTimeList;
    }
}
