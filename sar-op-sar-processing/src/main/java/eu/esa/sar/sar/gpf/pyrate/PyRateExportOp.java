package eu.esa.sar.sar.gpf.pyrate;

import com.bc.ceres.core.ProgressMonitor;
import org.apache.commons.io.FileUtils;
import eu.esa.sar.sar.gpf.geometric.RangeDopplerGeocodingOp;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.gpf.GPF;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.annotations.*;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.dem.gpf.AddElevationOp;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.jlinda.nest.dataio.SnaphuImportOp;
import org.esa.snap.core.dataop.resamp.ResamplingFactory;

import java.io.*;
import java.nio.file.Files;
import java.text.ParseException;
import java.util.ArrayList;

/**
 * Export a terrain-corrected, coregistered SBAS stack of unwrapped interferograms into PyRate format
 * for external processing.
 * Located within s1tbx-op-sar-processing to access terrain correction.
 * Written by Alex McVittie April 2023.
 */
@OperatorMetadata(alias = "PyrateExport",
        category = "Radar/Interferometric/Unwrapping",
        authors = "Alex McVittie",
        version = "1.0",
        copyright = "Copyright (C) 2023 SkyWatch Space Applications Inc.",
        autoWriteDisabled = true,
        description = "Export unwrapped SBAS interferometric data for PyRate processing")

public class PyRateExportOp extends Operator {

    @SourceProduct(alias = "source")
    private Product sourceProduct;
    
    // For the SnaphuExportOp operator.
    @Parameter(description = "Directory to write SNAPHU configuration files, unwrapped interferograms, and PyRate inputs to",
            defaultValue = "",
            label="Processing location")
    protected File processingLocation;

    @TargetProduct
    private Product targetProduct;

    public PyRateExportOp() {
    }

    @Override
    public void setSourceProduct(Product sourceProduct) {
        setSourceProduct(GPF.SOURCE_PRODUCT_FIELD_NAME, sourceProduct);
        this.sourceProduct = sourceProduct;
    }

    @Override
    public void initialize() throws OperatorException {

        // Validate the product
        if(sourceProduct == null){
            throw new OperatorException("Source product must not be null.");
        }
        InputProductValidator validator = new InputProductValidator(sourceProduct);
        validator.checkIfCoregisteredStack();
        validator.checkIfMapProjected(true);


        // Validate that we have a good number of bands to process.
        int numPhaseBands = getNumBands(sourceProduct, Unit.PHASE);
        int numCoherenceBands = getNumBands(sourceProduct, Unit.COHERENCE);

        if(numPhaseBands < 2){
            throw new OperatorException("PyRate needs more than 1 wrapped phase band.");
        }

        if(numCoherenceBands == 0){
            throw new OperatorException("PyRate requires coherence bands for processing.");
        }

        if(numPhaseBands != numCoherenceBands){
            throw new OperatorException("Mismatch in number of phase and coherence bands. Each interferogram needs a corresponding coherence band.");
        }

        // Validate the folder locations provided
        if (!Files.exists(processingLocation.toPath())){
            throw new OperatorException("Path provided for Snaphu processing location does not exist. Please provide a valid path.");
        }
        if (!Files.isDirectory(processingLocation.toPath())){
            throw new OperatorException("Path provided for Snaphu processing is not a folder. Please select a folder, not a file.");
        }
        if(!Files.isWritable(processingLocation.toPath())){
            throw new OperatorException("Path provided for intermediary processing is not writeable.");
        }
        targetProduct = sourceProduct;

    }

    /*

        1) Write unwrapped interferograms, along with the coherence bands, to the input PyRATE directory.
        2) Generate the needed configuration files for PyRATE.

        The user can then run `pyrate workflow -f input_parameters.conf` from processingLocation/sourceProductName dir.
     */
    @Override
    public void doExecute(ProgressMonitor pm) {
        pm.beginTask("Starting PyRate export...", 100);


        // Processing location provided by user is the root directory. We want to save all data in a folder that is named
        // the source product to avoid data overwriting with different products.
        processingLocation = new File(processingLocation, sourceProduct.getName());
        processingLocation.mkdirs();

        // Some products may be undesireable in PyRate processing. Store all dates that are undesireable in here
        // to prevent writing out and mucking up PyRate processing.
        ArrayList<String> bannedDates = new ArrayList<>();


        // Set up PyRATE output directory in our processing directory.
        new File(processingLocation, "pyrateOutputs").mkdirs();

        pm.worked(2);

        // Generate PyRATE configuration files
        PyRateConfigurationFileBuilder configBuilder = new PyRateConfigurationFileBuilder();

        File geoTiffs = new File(processingLocation, "geoTiffs");
        geoTiffs.mkdirs();

        configBuilder.coherenceFileList = new File(processingLocation, "coherenceFiles.txt").getName();

        configBuilder.interferogramFileList = new File(processingLocation, "ifgFiles.txt").getName();

        configBuilder.outputDirectory = new File(processingLocation, "pyrateOutputs").getName();
        configBuilder.parallel = false;

        File headerFileFolder = new File(processingLocation, "headers");

        String mainFileContents = configBuilder.createMainConfigFileContents();
        pm.worked(8);

        try {
            FileUtils.write(new File(processingLocation, "input_parameters.conf"), mainFileContents);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        pm.worked(15);

        // PyRATE requires individual headers for each source image that goes into an interferogram image pair.
        PyRateGammaHeaderWriter gammaHeaderWriter = new PyRateGammaHeaderWriter(sourceProduct);
        headerFileFolder.mkdirs();
        try {
            gammaHeaderWriter.writeHeaderFiles(headerFileFolder, new File(processingLocation, configBuilder.headerFileList));
        } catch (ParseException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        pm.worked(15);
        bannedDates = gammaHeaderWriter.getBannedDates();


        // Write coherence and phase bands out to individual GeoTIFFS
        String interferogramFileList = null;
        try {
            interferogramFileList = writeBands(sourceProduct, "GeoTIFF", Unit.PHASE, bannedDates);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        pm.worked(20);
        String coherenceFiles = null;
        try {
            coherenceFiles = writeBands(sourceProduct, "GeoTIFF", Unit.COHERENCE, bannedDates);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        pm.worked(20);



        // Write out elevation band in GeoTIFF format.
        try {
            writeElevationBand(sourceProduct, configBuilder.demFile, "GeoTIFF");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }


        // Only write in GAMMA format to write out header. .rslc image data gets deleted.
        try {
            writeElevationBand(sourceProduct, configBuilder.demFile, "Gamma");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        pm.worked(10);

        // Populate files containing the coherence and interferograms.
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

        // Set the target output product to be the terrain corrected product
        // with elevation, coherence, and unwrapped phase bands.
        setTargetProduct(sourceProduct);

        pm.done();

    }

    // Simple method to get the number of bands in a product with a specified band unit.
    private int getNumBands(Product product, String unit){
        int numBands = 0;
        for (Band b: product.getBands()){
            if(b.getUnit().contains(unit)){
                numBands++;
            }
        }
        return numBands;
    }

    private String writeBands(Product product, String format, String unit, ArrayList<String> bannedDates) throws IOException {
        String fileNames = "";
        int x = 0;
        for(Band b: product.getBands()){
            if(b.getUnit().contains(unit)){
                Product productSingleBand = new Product(product.getName(), product.getProductType(), product.getSceneRasterWidth(), product.getSceneRasterHeight());
                productSingleBand.setSceneGeoCoding(product.getSceneGeoCoding());
                b.readRasterDataFully();
                ProductUtils.copyBand(b.getName(), product, productSingleBand, true);
                String [] name = b.getName().split("_");
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
                int firstDateNum = Integer.parseInt(PyRateCommons.bandNameDateToPyRateDate(firstDate, false));
                int secondDateNum = Integer.parseInt(PyRateCommons.bandNameDateToPyRateDate(secondDate, false));
                // Secondary date cannot be before primary date. Don't write out band if so. Also do not write any ifg pairs
                // that have been deemed as containing bad metadata.
                if(secondDateNum < firstDateNum ||
                        bannedDates.contains(PyRateCommons.bandNameDateToPyRateDate(firstDate, false)) ||
                        bannedDates.contains(PyRateCommons.bandNameDateToPyRateDate(secondDate, false))){
                    continue;
                }
                String pyRateDate = PyRateCommons.bandNameDateToPyRateDate(firstDate, false) + "-" + PyRateCommons.bandNameDateToPyRateDate(secondDate, false);
                String pyRateName = pyRateDate + "_" + unit;
                String fileName = new File(new File(processingLocation, "geoTiffs"), pyRateName).getAbsolutePath();
                productSingleBand.setName(pyRateName);
                productSingleBand.getBands()[0].setName(pyRateName);

                ProductIO.writeProduct(productSingleBand, fileName, format);

                if(format.equals("GeoTIFF")){
                    fileName += ".tif";
                }else{
                    PyRateGammaHeaderWriter.adjustGammaHeader(productSingleBand, new File(new File(processingLocation, "geoTiffs"), productSingleBand.getName() + ".par"));
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
        ProductIO.writeProduct(productSingleBand, fileName, format);
        if(format.equals("Gamma")){
            // Default GAMMA format writing doesn't add everything we need. Add the additional needed files.
            PyRateGammaHeaderWriter.adjustGammaHeader(productSingleBand, new File(processingLocation, "DEM.par"));
            new File(processingLocation, "elevation.rslc").delete();
        }
    }


    /**
     * The SPI is used to register this operator in the graph processing framework
     * via the SPI configuration file
     * {@code META-INF/services/org.esa.snap.core.gpf.OperatorSpi}.
     * This class may also serve as a factory for new operator instances.
     *
     * @see OperatorSpi#createOperator()
     * @see OperatorSpi#createOperator(java.util.Map, java.util.Map)
     */
    public static class Spi extends OperatorSpi {
        public Spi() {

            super(PyRateExportOp.class);
        }
    }
}
