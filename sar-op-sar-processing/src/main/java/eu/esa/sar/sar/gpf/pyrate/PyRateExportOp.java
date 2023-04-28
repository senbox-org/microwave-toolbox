package eu.esa.sar.sar.gpf.pyrate;

import org.apache.commons.io.FileUtils;
import eu.esa.sar.sar.gpf.geometric.RangeDopplerGeocodingOp;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.gpf.GPF;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProducts;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.dem.gpf.AddElevationOp;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.jlinda.nest.dataio.SnaphuImportOp;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;

/**
 * Export products into format suitable for import to PyRate.
 * Located within s1tbx-op-sar-processing to access terrain correction.
 * Written by Alex McVittie April 2023.
 */
@OperatorMetadata(alias = "PyrateExport",
        category = "Radar/Interferometric/PSI \\ SBAS",
        authors = "Alex McVittie",
        version = "1.0",
        copyright = "Copyright (C) 2023 SkyWatch Space Applications Inc.",
        autoWriteDisabled = true,
        description = "Export wrapped SBAS interferometric data for PyRate processing")

public class PyRateExportOp extends Operator {
    // For testing purposes. Setting to true disables the external call to Snaphu unwrapping,
    // useful if ifgs are already unwrapped and you are just testing later stages of the integration.
    boolean testingDisableUnwrapStep = true;

    protected File snaphuProcessingLocation;

    @SourceProducts
    private Product [] sourceProducts;

    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(description = "The output folder to which the data product is written.")
    private File targetFolder;


    // For downloading and running Snaphu.
    @Parameter(description = "SNAPHU binary folder", defaultValue = "/tmp/snaphuBinary")
    protected String snaphuInstallLocation = "";

    // For re-adding the elevation band
    @Parameter(description = "Elevation band", defaultValue = "SRTM 3sec")
    private String elevationSource = "SRTM 3sec";


    // For the SnaphuExportOp operator.
    @Parameter(description = "Directory to write SNAPHU configuration files, unwrapped interferograms, and PyRate inputs to", defaultValue = "/tmp/snaphuProcessing")
    protected String processingLocation = "";

    @Parameter(valueSet = {"TOPO", "DEFO", "SMOOTH", "NOSTATCOSTS"},
            description = "Size of coherence estimation window in Azimuth direction",
            defaultValue = "TOPO",
            label = "Statistical-cost mode")
    protected String statCostMode = "TOPO";

    @Parameter(valueSet = {"MST", "MCF"},
            description = "Algorithm used for initialization of the wrapped phase values",
            defaultValue = "MST",
            label = "Initial method")
    protected String initMethod = "MST";

    @Parameter(description = "Divide the image into tiles and process in parallel. Set to 1 for single tiled.",
            defaultValue = "10", label = "Number of Tile Rows")
    protected int numberOfTileRows = 10;

    @Parameter(description = "Divide the image into tiles and process in parallel. Set to 1 for single tiled.",
            defaultValue = "10", label = "Number of Tile Columns")
    protected int numberOfTileCols = 10;

    @Parameter(description = "Number of concurrent processing threads. Set to 1 for single threaded.",
            defaultValue = "4", label = "Number of Processors")
    protected int numberOfProcessors = 4;

    @Parameter(description = "Overlap, in pixels, between neighboring tiles.",
            defaultValue = "200", label = "Row Overlap")
    protected int rowOverlap = 200;

    @Parameter(description = "Overlap, in pixels, between neighboring tiles.",
            defaultValue = "200", label = "Column Overlap")
    protected int colOverlap = 200;

    @Parameter(description = "Cost threshold to use for determining boundaries of reliable regions\n" +
            " (long, dimensionless; scaled according to other cost constants).\n" +
            " Larger cost threshold implies smaller regions---safer, but more expensive computationally.",
            defaultValue = "500", label = "Tile Cost Threshold")
    protected int tileCostThreshold = 500;

    @Override
    public void setSourceProduct(Product sourceProduct) {
        setSourceProduct(GPF.SOURCE_PRODUCT_FIELD_NAME, sourceProduct);
        this.sourceProduct = sourceProduct;
    }

    @Override
    public void initialize() throws OperatorException {

        runValidationChecks();

        try{
            process();
        }catch (Exception e){
            throw new OperatorException(e);
        }
    }
    // Product and input variable validations.
    private void runValidationChecks() throws OperatorException {

        // Validate the product
        if(sourceProduct == null){
            throw new OperatorException("Source product must not be null.");
        }
        InputProductValidator validator = new InputProductValidator(sourceProduct);
        validator.checkIfCoregisteredStack();

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
        if (!Files.exists(new File(processingLocation).toPath())){
            throw new OperatorException("Path provided for Snaphu processing location does not exist. Please provide a valid path.");
        }
        if (!Files.isDirectory(new File(processingLocation).toPath())){
            throw new OperatorException("Path provided for Snaphu processing is not a folder. Please select a folder, not a file.");
        }
        if (!Files.exists(new File(snaphuInstallLocation).toPath())){
            throw new OperatorException("Path provided for the Snaphu installation does not exist. Please provide an existing path.");
        }
        if(!Files.exists(new File(processingLocation).toPath())){
            throw new OperatorException("Path provided for the intermediary processing does not exist. Please provide an existing path.");
        }
        if(!Files.isWritable(new File(processingLocation).toPath())){
            throw new OperatorException("Path provided for intermediary processing is not writeable.");
        }
        if(!SnaphuHelperMethods.isSnaphuBinary(new File(snaphuInstallLocation)) &&
                !Files.isWritable(new File(snaphuInstallLocation).toPath())){
            throw new OperatorException("Folder provided for SNAPHU installation is not writeable.");
        }
    }

    /*
        All main preprocessing and generation of PyRATE inputs happens within this process() method.
        After product validation (Is a coregistered stack, contains ifgs, has more than 2 ifgs, the output paths are valid, etc),
        this method is executed.
        The PyRATE preparation workflow is as follows:
        1) Generate SNAPHU input to unwrap each interferogram in the stack.
        2) Download SNAPHU if it is not present in the installation location.
        3) Loop through the SNAPHU input directory and unwrap each interferogram.
        4) Assemble the unwrapped interferograms into one product, and then use SNAPHU Import to bring them back into the original product.
        5) Add an elevation band if not supplied in the original product.
        6) Write unwrapped interferograms, along with the coherence bands, to the input PyRATE directory.
        7) Generate the needed configuration files for PyRATE.

        The user can then run `pyrate workflow -f input_parameters.conf` from processingLocation/sourceProductName dir.
     */
    private void process() throws Exception {
        // Processing location provided by user is the root directory. We want to save all data in a folder that is named
        // the source product to avoid data overwriting with different products.
        processingLocation = new File(processingLocation, sourceProduct.getName()).getAbsolutePath();
        new File(processingLocation).mkdirs();

        // Create sub folder for SNAPHU processing and intermediary files.
        new File(processingLocation, "snaphu").mkdirs();

        snaphuProcessingLocation = new File(processingLocation, "snaphu");
        snaphuProcessingLocation.mkdirs();

        // Unwrap interferograms and merge into one multi-band product.
        Product unwrappedInterferograms = SnaphuHelperMethods.processSnaphu(this, sourceProduct);

        // Snaphu import takes an array of the original product with wrapped interferograms, and a product with the unwrapped
        // interferograms. Put them into an array for input.
        Product [] productPair = new Product[]{sourceProduct, unwrappedInterferograms};

        SnaphuImportOp snaphuImportOp = new SnaphuImportOp();
        snaphuImportOp.setSourceProducts(productPair);
        snaphuImportOp.setParameter("doNotKeepWrapped", true);

        Product imported = snaphuImportOp.getTargetProduct();

        // Importing from snaphuImportOp does not preserve the coherence bands. Copy them over from source product.
        for (Band b : sourceProduct.getBands()){
            if (b.getUnit().contains(Unit.COHERENCE)){
                ProductUtils.copyBand(b.getName(), sourceProduct, imported, true);
            } else if (b.getName().contains("elevation")){
                ProductUtils.copyBand(b.getName(), sourceProduct, imported, true);
            }
        }

        // Preserve geocoding
        imported.setSceneGeoCoding(sourceProduct.getSceneGeoCoding());

        // Some products may be undesireable in PyRate processing. Store all dates that are undesireable in here
        // to prevent writing out and mucking up PyRate processing.
        ArrayList<String> bannedDates = new ArrayList<>();


        // PyRATE input data needs to be projected into a geographic coordinate system. Needs terrain correction.
        RangeDopplerGeocodingOp rangeDopplerGeocodingOp = new RangeDopplerGeocodingOp();
        rangeDopplerGeocodingOp.setSourceProduct(imported);
        Product terrainCorrected = rangeDopplerGeocodingOp.getTargetProduct();


        // Set up PyRATE output directory in our processing directory.
        new File(processingLocation, "pyrateOutputs").mkdirs();

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

        FileUtils.write(new File(processingLocation, "input_parameters.conf"), mainFileContents);

        // PyRATE requires individual headers for each source image that goes into an interferogram image pair.
        PyRateGammaHeaderWriter gammaHeaderWriter = new PyRateGammaHeaderWriter(terrainCorrected);
        headerFileFolder.mkdirs();
        gammaHeaderWriter.writeHeaderFiles(headerFileFolder, new File(processingLocation, configBuilder.headerFileList));
        bannedDates = gammaHeaderWriter.getBannedDates();

        // Write coherence and phase bands out to individual GeoTIFFS
        String interferogramFileList = writeBands(terrainCorrected, "GeoTIFF", Unit.PHASE, bannedDates);
        String coherenceFiles = writeBands(terrainCorrected, "GeoTIFF", Unit.COHERENCE, bannedDates);


        // Attempting to use an elevation band that was pre-existing seems to be an impossible feat.
        // Writing out returns a "type not supported" error.
        // Have to re-add an elevation band to properly run this workflow.
        AddElevationOp addElevationOp = new AddElevationOp();
        addElevationOp.setSourceProduct(terrainCorrected);
        addElevationOp.setParameter("demName", elevationSource);
        Product tcWithElevation = addElevationOp.getTargetProduct();


        // Write out elevation band in GeoTIFF format.
        writeElevationBand(tcWithElevation, configBuilder.demFile, "GeoTIFF");

        // Only write in GAMMA format to write out header. .rslc image data gets deleted.
        writeElevationBand(tcWithElevation, configBuilder.demFile, "Gamma");

        // Populate files containing the coherence and interferograms.
        FileUtils.write(new File(processingLocation, configBuilder.coherenceFileList), coherenceFiles);
        FileUtils.write(new File(processingLocation, configBuilder.interferogramFileList), interferogramFileList);

        // Set the target output product to be the terrain corrected product
        // with elevation, coherence, and unwrapped phase bands.
        setTargetProduct(terrainCorrected);

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
}
