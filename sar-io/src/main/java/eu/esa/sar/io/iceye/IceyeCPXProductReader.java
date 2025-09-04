package eu.esa.sar.io.iceye;

import java.awt.image.DataBuffer;

import eu.esa.sar.io.iceye.util.IceyeConstants;
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
        final Band phaseBand = new Band(IceyeConstants.phase_band_prefix + polarization, ProductData.TYPE_FLOAT32, imageWidth, imageHeight);
        phaseBand.setUnit(Unit.PHASE);
        phaseBand.setNoDataValue(99999.0);
        phaseBand.setNoDataValueUsed(true);
        product.addBand(phaseBand);
        bandMap.put(phaseBand, IceyeConstants.PHASE_BAND_INDEX);

        final Band iBand = new VirtualBand(IceyeConstants.i_band_prefix + polarization, ProductData.TYPE_FLOAT32, imageWidth, imageHeight,
                "Amplitude_VV * cos(Phase_VV)");
        iBand.setUnit(Unit.REAL);
        iBand.setNoDataValue(0);
        iBand.setNoDataValueUsed(true);
        product.addBand(iBand);
        bandMap.put(iBand, IceyeConstants.I_BAND_VIRTUAL_INDEX);

        final Band qBand = new VirtualBand(IceyeConstants.q_band_prefix + polarization, ProductData.TYPE_FLOAT32, imageWidth, imageHeight,
                "Amplitude_VV * sin(Phase_VV)");
        qBand.setUnit(Unit.IMAGINARY);
        qBand.setNoDataValue(0);
        qBand.setNoDataValueUsed(true);
        product.addBand(qBand);
        bandMap.put(qBand, IceyeConstants.Q_BAND_VIRTUAL_INDEX);
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

    float getRasterValue(int srcIndex, int bandIndex, DataBuffer dataBuffer) {
        srcIndex *= 2;
        if (bandIndex < 2) {
            return dataBuffer.getElemFloat(srcIndex + bandIndex);
        } else if (bandIndex == IceyeConstants.I_BAND_VIRTUAL_INDEX) {
            float amp = dataBuffer.getElemFloat(srcIndex + IceyeConstants.AMPLITUDE_BAND_INDEX);
            float pha = dataBuffer.getElemFloat(srcIndex + IceyeConstants.PHASE_BAND_INDEX);
            return amp * (float) Math.cos(pha);
        } else if (bandIndex == IceyeConstants.Q_BAND_VIRTUAL_INDEX) {
            float amp = dataBuffer.getElemFloat(srcIndex + IceyeConstants.AMPLITUDE_BAND_INDEX);
            float pha = dataBuffer.getElemFloat(srcIndex + IceyeConstants.PHASE_BAND_INDEX);
            return amp * (float) Math.sin(pha);
        } else {
            return 0;
        }

    }
}
