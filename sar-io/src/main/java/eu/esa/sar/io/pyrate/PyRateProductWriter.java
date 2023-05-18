package eu.esa.sar.io.pyrate;


import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.io.pyrate.pyrateheader.PyRateHeaderWriter;
import it.geosolutions.imageio.plugins.tiff.TIFFField;
import it.geosolutions.imageio.plugins.tiff.TIFFImageWriteParam;
import it.geosolutions.imageio.plugins.tiff.TIFFTag;
import it.geosolutions.imageioimpl.plugins.tiff.TIFFIFD;
import it.geosolutions.imageioimpl.plugins.tiff.TIFFImageMetadata;
import it.geosolutions.imageioimpl.plugins.tiff.TIFFImageWriter;
import org.apache.commons.io.FileUtils;
import org.esa.snap.core.dataio.AbstractProductWriter;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.dataio.ProductWriterPlugIn;

import org.esa.snap.core.dataio.dimap.DimapHeaderWriter;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.image.ImageManager;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.core.util.geotiff.GeoTIFF;
import org.esa.snap.core.util.geotiff.GeoTIFFMetadata;
import org.esa.snap.dataio.geotiff.GeoTiffBandWriter;
import org.esa.snap.dataio.geotiff.GeoTiffProductWriter;
import org.esa.snap.dataio.geotiff.internal.*;
import org.esa.snap.engine_utilities.datamodel.Unit;

import javax.imageio.*;
import javax.imageio.stream.FileImageOutputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.media.jai.operator.FormatDescriptor;
import java.awt.color.ColorSpace;
import java.awt.image.*;
import java.awt.image.renderable.ParameterBlock;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.text.ParseException;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;

public class PyRateProductWriter extends AbstractProductWriter {

    private File processingLocation;

    private HashMap<String, GeoTiffBandWriter> bandWriterMap = new HashMap<>();

    private final String BANNED = "BANNED_DATE";

    private boolean hasStartedWriting = false;

    public static final int PRIVATE_BEAM_TIFF_TAG_NUMBER = 65000;



    /**
     * Construct a new instance of a product writer for the given GeoTIFF product writer plug-in.
     *
     * @param writerPlugIn the given GeoTIFF product writer plug-in, must not be <code>null</code>
     */
    public PyRateProductWriter(ProductWriterPlugIn writerPlugIn) {
        super(writerPlugIn);
    }

    @Override
    protected void writeProductNodesImpl() throws IOException {


        if (getOutput() instanceof String) {
            processingLocation = new File((String) getOutput()).getParentFile();
        } else {
            processingLocation = ((File) getOutput()).getParentFile();
        }
        new File(processingLocation, "geoTiffs").mkdirs();
        new File(processingLocation, "headers").mkdirs();

        // Set up PyRATE output directory in our processing directory.
        new File(processingLocation, "pyrateOutputs").mkdirs();


        PyRateConfigurationFileBuilder configBuilder = new PyRateConfigurationFileBuilder();

        File geoTiffs = new File(processingLocation, "geoTiffs");
        geoTiffs.mkdirs();



        configBuilder.coherenceFileList = new File(processingLocation, "coherenceFiles.txt").getName();

        configBuilder.interferogramFileList = new File(processingLocation, "ifgFiles.txt").getName();

        configBuilder.outputDirectory = new File(processingLocation, "pyrateOutputs").getName();

        File headerFileFolder = new File(processingLocation, "headers");

        String mainFileContents = configBuilder.createMainConfigFileContents();

        FileUtils.write(new File(processingLocation, "input_parameters.conf"), mainFileContents);

        PyRateHeaderWriter gammaHeaderWriter = new PyRateHeaderWriter(getSourceProduct());

        headerFileFolder.mkdirs();
        try {
            gammaHeaderWriter.writeHeaderFiles(headerFileFolder, new File(processingLocation, configBuilder.headerFileList));
        } catch (ParseException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }


        ArrayList<String> bannedDates = gammaHeaderWriter.getBannedDates();
        final TiffIFD ifd = new TiffIFD(getSourceProduct());
        for (Band b: getSourceProduct().getBands()){
            if(b.getUnit() != null && b.getUnit().contains(Unit.PHASE)){
                File outputGeoTiff = new File(geoTiffs, createPyRateFileName(b.getName(), Unit.PHASE, bannedDates ) + ".tif" );
                ImageOutputStream outputStream = new FileImageOutputStream(outputGeoTiff);
                bandWriterMap.put(b.getName(), new GeoTiffBandWriter(ifd, outputStream, getSourceProduct()));
            }else if(b.getUnit() != null && b.getUnit().contains(Unit.COHERENCE)){
                File outputGeoTiff = new File(geoTiffs, createPyRateFileName(b.getName(), Unit.COHERENCE, bannedDates ) + ".tif" );
                ImageOutputStream outputStream = new FileImageOutputStream(outputGeoTiff);
                bandWriterMap.put(b.getName(), new GeoTiffBandWriter(ifd, outputStream, getSourceProduct()));
            }else if(b.getName().equals("elevation")){
                File outputGeoTiff = new File(processingLocation, "DEM.tif" );
                ImageOutputStream outputStream = new FileImageOutputStream(outputGeoTiff);
                bandWriterMap.put(b.getName(), new GeoTiffBandWriter(ifd, outputStream, getSourceProduct()));
            }
        }

        // Write coherence and phase bands out to individual GeoTIFFS
        String interferogramFileList = null;
        try {
            interferogramFileList = createBandFileList(getSourceProduct(), "GeoTIFF", Unit.PHASE, bannedDates);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        String coherenceFiles = null;
        try {
            coherenceFiles = createBandFileList(getSourceProduct(), "GeoTIFF", Unit.COHERENCE, bannedDates);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // Write out elevation band in GeoTIFF format.
        try {
            writeElevationBand(getSourceProduct(), configBuilder.demFile, "GeoTIFF");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }


        // Only write in GAMMA format to write out header. .rslc image data gets deleted.
        try {
            writeElevationBand(getSourceProduct(), configBuilder.demFile, "Gamma");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }


        try {
            FileUtils.write(new File(processingLocation, configBuilder.coherenceFileList), coherenceFiles);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try {
            FileUtils.write(new File(processingLocation, configBuilder.interferogramFileList), interferogramFileList);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    private String createPyRateFileName(String bandName, String unit, ArrayList<String> bannedDates){

        String [] name = bandName.split("_");
        int y = 0;
        String firstDate = "";
        String secondDate = "";
        for (String aname : name){
            if (aname.length() == 9){
                firstDate = aname;
                secondDate = name[y + 1];
                break;
            }
            y+= 1;
        }

        int firstDateNum = Integer.parseInt(bandNameDateToPyRateDate(firstDate, false));
        int secondDateNum = Integer.parseInt(bandNameDateToPyRateDate(secondDate, false));
        if(secondDateNum < firstDateNum ||
                bannedDates.contains(bandNameDateToPyRateDate(firstDate, false)) ||
                bannedDates.contains(bandNameDateToPyRateDate(secondDate, false))){
            return BANNED;
        }

        String pyRateDate = bandNameDateToPyRateDate(firstDate, false) + "-" + bandNameDateToPyRateDate(secondDate, false);
        String pyRateName = pyRateDate + "_" + unit;
        return pyRateName;



    }

    // Writing out tifs from this method causes issues when run from a graph.
    // All file writing operations accessing band data are removed and run during write raster band data method(s).
    // Generates a string containing a list of file names, delimited by newlines.
    private String createBandFileList(Product product, String format, String unit, ArrayList<String> bannedDates) throws IOException {
        String fileNames = "";
        int x = 0;
        for(Band b: product.getBands()){
            if(b.getUnit() != null && b.getUnit().contains(unit)){
                Product productSingleBand = new Product(product.getName(), product.getProductType(), product.getSceneRasterWidth(), product.getSceneRasterHeight());
                productSingleBand.setSceneGeoCoding(product.getSceneGeoCoding());
                //b.readRasterDataFully();
                //ProductUtils.copyBand(b.getName(), product, productSingleBand, true);
                String pyRateName = createPyRateFileName(b.getName(), unit, bannedDates);
                if (pyRateName.equals(BANNED)){
                    continue;
                }
                String fileName = new File(new File(processingLocation, "geoTiffs"), pyRateName).getAbsolutePath();
                productSingleBand.setName(pyRateName);
                //productSingleBand.getBands()[0].setName(pyRateName);

                //ProductIO.writeProduct(productSingleBand, fileName, format);

                if(format.equals("GeoTIFF")){
                    fileName += ".tif";
                }else{
                    PyRateHeaderWriter.adjustGammaHeader(productSingleBand, new File(new File(processingLocation, "geoTiffs"), productSingleBand.getName() + ".par"));
                    new File(processingLocation, productSingleBand.getName() + ".rslc").delete();
                }
                fileNames += "\n" + new File(fileName).getParentFile().getName() + "/" + new File(fileName).getName();
                x++;
            }
        }
        // Cut off trailing newline character.
        return fileNames.substring(1);
    }

    private void writeElevationBand(Product product, String name, String format) throws IOException {
        Product productSingleBand = new Product(product.getName(), product.getProductType(), product.getSceneRasterWidth(), product.getSceneRasterHeight());
        productSingleBand.setSceneGeoCoding(product.getSceneGeoCoding());
        Band elevationBand = product.getBand("elevation");
        ProductUtils.copyBand("elevation", product, productSingleBand, true);
        String fileName = new File(processingLocation, name).getAbsolutePath();
        //ProductIO.writeProduct(productSingleBand, fileName, format);
        if(format.equals("Gamma")){
            // Default GAMMA format writing doesn't add everything we need. Add the additional needed files.
            //PyRateHeaderWriter.adjustGammaHeader(productSingleBand, new File(processingLocation, "DEM.par"));
            //new File(processingLocation, "elevation.rslc").delete();
        }
    }

    // Converts format of 14May2020 to 20200414. or 2020 04 14 depending on if forPARFile is set to true or not.
    public static String bandNameDateToPyRateDate(String bandNameDate, boolean forPARFile){
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM").withLocale(Locale.ENGLISH);
        TemporalAccessor accessor = formatter.parse(toSentenceCase(bandNameDate.substring(2, 5)));
        int monthNumber = accessor.get(ChronoField.MONTH_OF_YEAR);
        String month = String.valueOf(monthNumber);
        if(monthNumber < 10){
            month = "0" + month;
        }
        // Formatted as YYYYMMDD if for band/product names, YYYY MM DD if for GAMMA PAR file contents.
        String delimiter = " ".substring(forPARFile ? 0: 1);
        return bandNameDate.substring(5) + delimiter +
                month + delimiter + bandNameDate.substring(0, 2);
    }
    public static String toSentenceCase(String word){
        String firstCharacter = word.substring(0, 1);
        String rest = word.substring(1);
        return firstCharacter.toUpperCase() + rest.toLowerCase();
    }

    // Based off of BigGeotiffProductWriter
    @Override
    public synchronized void writeBandRasterData(Band sourceBand,
                             int sourceOffsetX, int sourceOffsetY,
                             int sourceWidth, int sourceHeight,
                             ProductData sourceBuffer,
                             ProgressMonitor pm) throws IOException{
        Product sourceProduct = sourceBand.getProduct();
        final ArrayList<Band> bandsToExport = getBandsToExport(sourceProduct);
        if (bandsToExport.contains(sourceBand)){
            ImageOutputStream outputStream;
            if(!sourceBand.getName().equals("elevation")){
                File outputFile = new File(processingLocation, "geoTiffs/" + createPyRateFileName(sourceBand.getName(), sourceBand.getUnit(), new ArrayList<String>()) + ".tif");
                outputStream = new FileImageOutputStream(outputFile);
            }else{
                File outputFile = new File(processingLocation, "DEM.tif");
                outputStream = new FileImageOutputStream(outputFile);
            }

            TiffIFD ifd = new TiffIFD(sourceProduct);
            writeBandRasterData(sourceBand, sourceOffsetX, sourceOffsetY, sourceWidth, sourceHeight,sourceBuffer, outputStream, ifd, pm);


        }
    }

    private ArrayList<Band> getBandsToExport(Product sourceProduct){
        final ArrayList<Band> bandsToWrite = new ArrayList<>();

        // We only want to write out coherence, interferograms, and DEM. Any additional bands such as intensity
        // or imaginary i&q bands are not to be written.
        PyRateHeaderWriter headerWriter = new PyRateHeaderWriter(sourceProduct);

        for (Band b : sourceProduct.getBands()){
            if(b.getName().equals("elevation")){
                bandsToWrite.add(b);
            }
            else if((b.getUnit() != null && (b.getUnit().equals(Unit.COHERENCE) ||
                                             b.getUnit().equals(Unit.PHASE)) )){
                for(String bannedDate :  headerWriter.getBannedDates()){
                    if(createPyRateFileName(b.getName(), "", new ArrayList<>()).contains(bannedDate)){
                        break;
                    }
                }
                bandsToWrite.add(b);

            }
        }
        return bandsToWrite;
    }

    @Override
    public void flush() throws IOException {

    }

    @Override
    public void close() throws IOException {

    }

    @Override
    public void deleteOutput() throws IOException {

    }


    /**
     * Writes raster data from the given in-memory source buffer into the data sink specified by the given source band
     * and region.
     * <p>
     * <h3>Source band</h3> The source band is used to identify the data sink in which this method transfers the sample
     * values given in the source buffer. The method does not modify the pixel data of the given source band at all.
     * <p>
     * <h3>Source buffer</h3> The first element of the source buffer corresponds to the given <code>sourceOffsetX</code>
     * and <code>sourceOffsetY</code> of the source region. These parameters are an offset within the band's raster data
     * and <b>not</b> an offset within the source buffer.<br> The number of elements in the buffer must be exactly be
     * <code>sourceWidth * sourceHeight</code>. The pixel values to be writte are considered to be stored in
     * line-by-line order, so the raster X co-ordinate varies faster than the Y.
     * <p>
     * <h3>Source region</h3> The given destination region specified by the <code>sourceOffsetX</code>,
     * <code>sourceOffsetY</code>, <code>sourceWidth</code> and <code>sourceHeight</code> parameters is given in the
     * source band's raster co-ordinates. These co-ordinates are identical with the destination raster co-ordinates
     * since product writers do not support spectral or spatial subsets.
     *
     * @param sourceBand   the source band which identifies the data sink to which to write the sample values
     * @param regionData   the data buffer which provides the sample values to be written
     * @param regionX      the X-offset in the band's raster co-ordinates
     * @param regionY      the Y-offset in the band's raster co-ordinates
     * @param regionWidth  the width of region to be written given in the band's raster co-ordinates
     * @param regionHeight the height of region to be written given in the band's raster co-ordinates
     * @throws java.io.IOException      if an I/O error occurs
     * @throws IllegalArgumentException if the number of elements source buffer not equals <code>sourceWidth *
     *                                  sourceHeight</code> or the source region is out of the band's raster
     * @see Band#getRasterWidth()
     * @see Band#getRasterHeight()
     */
    public void writeBandRasterData(final Band sourceBand,
                                    final int regionX,
                                    final int regionY,
                                    final int regionWidth,
                                    final int regionHeight,
                                    final ProductData regionData,
                                    ImageOutputStream ios,
                                    TiffIFD ifd,
                                    ProgressMonitor pm) throws IOException {

        final int bandDataType = ifd.getBandDataType();
        final int stripIndex = 0; // The writer writes only single band GeoTIFFs for PyRate.
        final TiffValue[] offsetValues = ifd.getEntry(TiffTag.STRIP_OFFSETS).getValues();
        final long stripOffset = ((TiffLong) offsetValues[stripIndex]).getValue();
        final TiffValue[] bitsPerSampleValues = ifd.getEntry(TiffTag.BITS_PER_SAMPLE).getValues();
        final long elemSize = ((TiffShort) bitsPerSampleValues[stripIndex]).getValue() / 8;
        final long sourceWidthBytes = sourceBand.getRasterWidth() * elemSize;
        final long regionOffsetXInBytes = regionX * elemSize;
        final long pixelOffset = sourceWidthBytes * regionY + regionOffsetXInBytes;
        final long startOffset = stripOffset + pixelOffset;

        pm.beginTask("Writing band '" + sourceBand.getName() + "'...", regionHeight);
        try {
            for (int y = 0; y < regionHeight; y++) {
                ios.seek(startOffset + y * sourceWidthBytes);
                final int stride = y * regionWidth;
                if (bandDataType == ProductData.TYPE_UINT8) {
                    final byte[] data = new byte[regionWidth];
                    for (int x = 0; x < regionWidth; x++) {
                        data[x] = (byte) regionData.getElemUIntAt(stride + x);
                    }
                    ios.write(data);
                } else if (bandDataType == ProductData.TYPE_INT8) {
                    final byte[] data = new byte[regionWidth];
                    for (int x = 0; x < regionWidth; x++) {
                        data[x] = (byte) regionData.getElemIntAt(stride + x);
                    }
                    ios.write(data);
                } else if (bandDataType == ProductData.TYPE_UINT16) {
                    final short[] data = new short[regionWidth];
                    for (int x = 0; x < regionWidth; x++) {
                        data[x] = (short) regionData.getElemUIntAt(stride + x);
                    }
                    ios.writeShorts(data, 0, regionWidth);
                } else if (bandDataType == ProductData.TYPE_INT16) {
                    final short[] data = new short[regionWidth];
                    for (int x = 0; x < regionWidth; x++) {
                        data[x] = (short) regionData.getElemIntAt(stride + x);
                    }
                    ios.writeShorts(data, 0, regionWidth);
                } else if (bandDataType == ProductData.TYPE_UINT32) {
                    final int[] data = new int[regionWidth];
                    for (int x = 0; x < regionWidth; x++) {
                        data[x] = (int) regionData.getElemUIntAt(stride + x);
                    }
                    ios.writeInts(data, 0, regionWidth);
                } else if (bandDataType == ProductData.TYPE_INT32) {
                    final int[] data = new int[regionWidth];
                    for (int x = 0; x < regionWidth; x++) {
                        data[x] = regionData.getElemIntAt(stride + x);
                    }
                    ios.writeInts(data, 0, regionWidth);
                } else if (bandDataType == ProductData.TYPE_FLOAT32) {
                    final float[] data = new float[regionWidth];
                    for (int x = 0; x < regionWidth; x++) {
                        if (sourceBand.isFlagBand() && ProductData.isUIntType(sourceBand.getDataType())) {
                            // bits in int and uint are the same. We need int here for the float conversion method.
                            data[x] = Float.intBitsToFloat(regionData.getElemIntAt(stride + x));
                        } else {
                            data[x] = regionData.getElemFloatAt(stride + x);
                        }
                    }
                    ios.writeFloats(data, 0, regionWidth);
                } else if (bandDataType == ProductData.TYPE_FLOAT64) {
                    final double[] data = new double[regionWidth];
                    for (int x = 0; x < regionWidth; x++) {
                        if (sourceBand.isFlagBand() && ProductData.isUIntType(sourceBand.getDataType())) {
                            data[x] = Double.longBitsToDouble(regionData.getElemUIntAt(stride + x));
                        } else {
                            data[x] = regionData.getElemDoubleAt(stride + x);
                        }
                    }
                    ios.writeDoubles(data, 0, regionWidth);
                }
                pm.worked(1);
            }
        } finally {
            pm.done();
        }
    }


}