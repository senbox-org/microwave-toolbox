package eu.esa.sar.io.pyrate;


import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.io.pyrate.pyrateheader.PyRateHeaderWriter;
import org.apache.commons.io.FileUtils;
import org.esa.snap.core.dataio.AbstractProductWriter;
import org.esa.snap.core.dataio.ProductWriter;
import org.esa.snap.core.dataio.ProductWriterPlugIn;

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;

import org.esa.snap.core.util.ProductUtils;

import org.esa.snap.dataio.geotiff.GeoTiffProductWriterPlugIn;
import org.esa.snap.engine_utilities.datamodel.Unit;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

public class PyRateProductWriter extends AbstractProductWriter {

    private File processingLocation;

    private HashMap<String, ProductWriter> bandProductWriterHashMap = new HashMap<>();


    private final String BANNED = "BANNED_DATE";


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

        // Set up PyRATE output directory in our processing directory.
        new File(processingLocation, "pyrateOutputs").mkdirs();
        File geoTiffs = new File(processingLocation, "geoTiffs");
        File headerFileFolder = new File(processingLocation, "headers");
        geoTiffs.mkdirs();
        headerFileFolder.mkdirs();

        // Set up config builder object
        PyRateConfigurationFileBuilder configBuilder = new PyRateConfigurationFileBuilder();

        configBuilder.coherenceFileList = new File(processingLocation, "coherenceFiles.txt").getName();
        configBuilder.interferogramFileList = new File(processingLocation, "ifgFiles.txt").getName();
        configBuilder.outputDirectory = new File(processingLocation, "pyrateOutputs").getName();

        String mainFileContents = configBuilder.createMainConfigFileContents();
        FileUtils.write(new File(processingLocation, "input_parameters.conf"), mainFileContents);

        PyRateHeaderWriter gammaHeaderWriter = new PyRateHeaderWriter(getSourceProduct());

        // Write the header files.
        try {
            gammaHeaderWriter.writeHeaderFiles(headerFileFolder, new File(processingLocation, configBuilder.headerFileList));
        } catch (ParseException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        ArrayList<String> bannedDates = gammaHeaderWriter.getBannedDates();

        // Create empty GeoTIFF files for writing to in write raster data.
        for (Band b: getBandsToExport(getSourceProduct())){
            File outputGeoTiff;
            if(!b.getName().equals("elevation")){
                outputGeoTiff = new File(geoTiffs, createPyRateFileName(b.getName(), b.getUnit(), bannedDates ) + ".tif" );
            }else{
                outputGeoTiff = new File(processingLocation, "DEM.tif" );
            }
            bandProductWriterHashMap.put(b.getName(), new GeoTiffProductWriterPlugIn().createWriterInstance() );
            Product singleBandProduct = new Product(b.getName(), getSourceProduct().getProductType(), getSourceProduct().getSceneRasterWidth(), getSourceProduct().getSceneRasterHeight());
            ProductUtils.copyProductNodes(getSourceProduct(), singleBandProduct);
            ProductUtils.copyBand(b.getName(), getSourceProduct(), singleBandProduct, true);
            bandProductWriterHashMap.get(b.getName()).writeProductNodes(singleBandProduct, outputGeoTiff);
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

        // Write file containing list of interferogam and coherence bands.
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

                String pyRateName = createPyRateFileName(b.getName(), b.getUnit(), bannedDates);
                if (pyRateName.equals(BANNED)){
                    continue;
                }
                String fileName = new File(new File(processingLocation, "geoTiffs"), pyRateName).getAbsolutePath();
                productSingleBand.setName(pyRateName);

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

    @Override
    public void writeBandRasterData(Band sourceBand,
                             int sourceOffsetX, int sourceOffsetY,
                             int sourceWidth, int sourceHeight,
                             ProductData sourceBuffer,
                             ProgressMonitor pm) throws IOException{


        Product sourceProduct = sourceBand.getProduct();
        final ArrayList<Band> bandsToExport = getBandsToExport(sourceProduct);
        if (bandsToExport.contains(sourceBand)){

            bandProductWriterHashMap.get(sourceBand.getName()).writeBandRasterData(sourceBand,
                    sourceOffsetX, sourceOffsetY,
                    sourceWidth, sourceHeight,
                    sourceBuffer, pm);

            bandProductWriterHashMap.get(sourceBand.getName()).flush();
            bandProductWriterHashMap.get(sourceBand.getName()).close();
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
            else if((b.getUnit() != null && (b.getUnit().contains(Unit.COHERENCE) ||
                                             b.getUnit().contains(Unit.PHASE)) )){
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



}