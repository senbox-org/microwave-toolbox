package eu.esa.sar.sar.gpf.pyrate;

/*
    For use with PyRate. Purpose of this operator is to take a given input folder from SNAPHU unwrap,
    unwrap all interferograms in the folder, and then import back into one singular product.

    Outputs a single product containing all the unwrapped bands. Needs to have SNAPHU Import applied after to
    preserve geocoding.
 */


import com.bc.ceres.core.ProgressMonitor;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataAttribute;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.core.util.io.FileDownloader;
import org.esa.snap.dataio.envi.EnviProductReaderPlugIn;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.esa.snap.engine_utilities.util.ZipUtils;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;

import static org.esa.snap.core.gpf.internal.JaiHelper.createTargetProduct;


@OperatorMetadata(alias = "BatchSnaphuUnwrapOp",
        category = "Radar/Interferometric/Unwrapping",
        authors = "Alex McVittie",
        version = "1.0",
        copyright = "Copyright (C) 2023 SkyWatch Space Applications Inc.",
        autoWriteDisabled = true,
        description = "Downloads and executes SNAPHU on a ")

public class BatchSnaphuUnwrapOp extends Operator {

    @SourceProduct(alias = "source")
    private Product sourceProduct;

    @Parameter(description = "Directory that the Snaphu Export operator wrote to",
            label="Snaphu Export folder")
    protected File snaphuProcessingLocation;

    @Parameter(description = "Directory to install the SNAPHU binary to",
            label="Snaphu Install Location")
    protected File snaphuInstallLocation;


    @TargetProduct
    private Product trgProduct;



    @Override
    public void initialize() throws OperatorException {
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

        // Validate the folder locations provided
        if (!Files.exists(snaphuProcessingLocation.toPath())){
            throw new OperatorException("Path provided for Snaphu processing location does not exist. Please provide a valid path.");
        }
        if (!Files.isDirectory(snaphuProcessingLocation.toPath())){
            throw new OperatorException("Path provided for Snaphu processing is not a folder. Please select a folder, not a file.");
        }

        if(!Files.isWritable(snaphuProcessingLocation.toPath())){
            throw new OperatorException("Path provided for Snaphu processing is not writeable.");
        }
        if (!isSnaphuBinary(snaphuInstallLocation) &&
                !Files.isWritable(snaphuInstallLocation.toPath())){
            throw new OperatorException("Path provided for Snaphu install is not writeable.");
        }

        trgProduct = new Product(sourceProduct.getName(), sourceProduct.getProductType(), sourceProduct.getSceneRasterWidth(), sourceProduct.getSceneRasterHeight());
        trgProduct.setSceneGeoCoding(sourceProduct.getSceneGeoCoding());
        trgProduct.setName(sourceProduct.getName() + "_snaphu");
        MetadataElement trgRoot = trgProduct.getMetadataRoot();
        MetadataElement srcRoot = sourceProduct.getMetadataRoot();
        for(MetadataElement me : srcRoot.getElements()){
            trgRoot.addElement(me);
        }
        for(MetadataAttribute ma : srcRoot.getAttributes()){
            trgRoot.addAttribute(ma);
        }
        for (Band b: sourceProduct.getBands()){
            if(b.getUnit().equals(Unit.PHASE)){
                Band aBand = new Band("Unw" + b.getName(), b.getDataType(), b.getRasterWidth(), b.getRasterHeight());
                trgProduct.addBand(aBand);
            }else{
                ProductUtils.copyBand(b.getName(), sourceProduct, trgProduct, true);
            }
        }
        setTargetProduct(trgProduct);




    }

    boolean isSnaphuBinary(File file){
        if(System.getProperty("os.name").toLowerCase().startsWith("windows")){
            return ! file.isDirectory() &&
                    file.canExecute() &&
                    file.getName().equals("snaphu.exe");
        }else{
            return ! file.isDirectory() &&
                    file.canExecute() &&
                    file.getName().startsWith("snaphu");
        }
    }

    File findSnaphuBinary(File rootDir){
        Collection<File> files = FileUtils.listFilesAndDirs(rootDir, TrueFileFilter.INSTANCE, DirectoryFileFilter.DIRECTORY );
        for (File file : files){
            if(isSnaphuBinary(file)){
                return file;
            }
        }
        return null;
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

    File [] getSnaphuConfigFiles(File directory){
        ArrayList<File> configFiles = new ArrayList<>();
        for(String file: directory.list()){
            File aFile = new File(directory, file);
            if(file.endsWith("snaphu.conf") && ! file.equals("snaphu.conf")){
                configFiles.add(aFile);            }
        }

        return configFiles.toArray(new File[]{});

    }

    File downloadSnaphu(File snaphuInstallLocation) throws IOException {
        final String linuxDownloadPath = "http://step.esa.int/thirdparties/snaphu/1.4.2-2/snaphu-v1.4.2_linux.zip";
        final String windowsDownloadPath = "http://step.esa.int/thirdparties/snaphu/2.0.4/snaphu-v2.0.4_win64.zip";
        final String windows32DownloadPath = "http://step.esa.int/thirdparties/snaphu/1.4.2-2/snaphu-v1.4.2_win32.zip";

        boolean isDownloaded;
        File snaphuBinaryLocation;

        // Check if we have just been given the path to the SNAPHU binary
        if (isSnaphuBinary(snaphuInstallLocation)){
            snaphuBinaryLocation = snaphuInstallLocation;
            return snaphuBinaryLocation;
        }
        // We have checked the passed in folder and it does not contain the SNAPHU binary.
        String operatingSystem = System.getProperty("os.name");
        String downloadPath;

        if(operatingSystem.toLowerCase().contains("windows")){
            // Using Windows
            boolean bitDepth64 = System.getProperty("os.arch").equals("amd64");
            if(bitDepth64){
                downloadPath = windowsDownloadPath;
            }else{
                downloadPath = windows32DownloadPath;
            }
        }
        else{
            // Using MacOS or Linux
            downloadPath = linuxDownloadPath;
        }
        File zipFile = FileDownloader.downloadFile(new URL(downloadPath), snaphuInstallLocation, null);
        ZipUtils.unzip(zipFile.toPath(), snaphuInstallLocation.toPath(), true);
        snaphuBinaryLocation = findSnaphuBinary(snaphuInstallLocation);


        return snaphuBinaryLocation;
    }

    void callSnaphuUnwrap(File snaphuBinary, File configFile, File logFile, ProgressMonitor pm, String msgPrefix) throws IOException {
        File workingDir = configFile.getParentFile();
        String command = null;
        try(BufferedReader in = new BufferedReader(new FileReader(configFile), 1024)){
            // SNAPHU command is on the 7th line
            for(int x = 0; x < 6; x++){
                in.readLine();
            }
            // Start at 14th character to get rid of the binary prefix and comment symbol of the command
            command = in.readLine().substring(14);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        if (command != null){
            Process proc = Runtime.getRuntime().exec(snaphuBinary.toString() + command, null, workingDir);
            BufferedReader stdInput = new BufferedReader(new
                    InputStreamReader(proc.getInputStream()));

            BufferedReader stdError = new BufferedReader(new
                    InputStreamReader(proc.getErrorStream()));

            if (!logFile.exists()){
                FileUtils.write(logFile, "");
            }

            // Read the output from the command
            String s = null;
            while ((s = stdInput.readLine()) != null) {
                FileUtils.write(logFile, FileUtils.readFileToString(logFile, "utf-8") + "\n" + s);
                pm.setTaskName(msgPrefix + s);

            }
            // Read any errors from the attempted command
            while ((s = stdError.readLine()) != null) {

                FileUtils.write(logFile, FileUtils.readFileToString(logFile, "utf-8") + "\n" + s);
            }
        }
    }


    void assembleUnwrappedFilesIntoSingularProduct(File directory) throws IOException {
        File [] fileNames = directory.listFiles((dir, name) -> name.startsWith("UnwPhase") && name.endsWith(".hdr"));
        for (File file : fileNames){
            System.out.println(file.getAbsolutePath());

        }

        Product [] enviProducts = new Product[fileNames.length];
        EnviProductReaderPlugIn readerPlugIn = new EnviProductReaderPlugIn();
        ProductReader enviProductReader = readerPlugIn.createReaderInstance();
        System.out.println("Reading products....");
        for (int x = 0; x < enviProducts.length; x++){
            System.out.println("Reading envi product " + fileNames[x].getName());
            enviProducts[x] = enviProductReader.readProductNodes(fileNames[x], null);
            String trgBandName = enviProducts[x].getBands()[0].getName().replace(".snaphu.hdr", "");
            Band trgBand = getTargetProduct().getBand(trgBandName);
            System.out.println(trgBand.getName());
            Band enviBand = enviProducts[x].getBands()[0];
            System.out.println(enviBand.getName());
            trgBand.setRasterData(enviBand.createCompatibleRasterData());
        }
    }

    @Override
    public void doExecute(ProgressMonitor pm){

        pm.beginTask("Starting batch Snaphu export...", 100);
        pm.worked(1);

        try {
            pm.setTaskName("Downloading SNAPHU binary....");
            File snaphuBinary = downloadSnaphu(snaphuInstallLocation);

            // Write out all unwrap stdout and stderr to singular log file.
            File snaphuLogFile = new File(snaphuProcessingLocation, "snaphu-log-" +
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss")) + ".log" );
            // Find all SNAPHU configuration files and execute them.
            File [] configFiles = getSnaphuConfigFiles(snaphuProcessingLocation);

            // Move progress bar for each product processed.
            int work = (int) ((1.0 / configFiles.length) * 100) - 1;

            for (int x = 0; x < configFiles.length; x++){
                // Display current product indicator on the progress monitor.
                int count = x + 1;
                // Add prefix to progress monitor to indicate which product we are currently on.
                String prefix = "(" + count + "/" + configFiles.length + "): ";
                callSnaphuUnwrap(snaphuBinary, configFiles[x], snaphuLogFile, pm, prefix);

                pm.worked(work);
            }
            assembleUnwrappedFilesIntoSingularProduct(snaphuProcessingLocation);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        pm.done();
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

            super(BatchSnaphuUnwrapOp.class);
        }
    }
}
