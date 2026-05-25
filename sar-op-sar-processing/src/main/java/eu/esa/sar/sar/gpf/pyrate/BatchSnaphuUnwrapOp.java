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
import java.util.List;


@OperatorMetadata(alias = "BatchSnaphuUnwrapOp",
        category = "Radar/Interferometric/Unwrapping",
        authors = "Alex McVittie",
        version = "1.0",
        copyright = "Copyright (C) 2023 SkyWatch Space Applications Inc.",
        description = "Downloads and executes SNAPHU on a set of two or more interferograms.")

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
        if(snaphuProcessingLocation == null){
            throw new OperatorException("SNAPHU processing location is null");
        }
        if(snaphuInstallLocation == null){
            throw new OperatorException("SNAPHU install location is null");
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
        for (Band b: sourceProduct.getBands()){
            Band aBand = new Band(b.getName(), b.getDataType(), b.getRasterWidth(), b.getRasterHeight());
            aBand.setUnit(b.getUnit());
            if(b.getUnit().equals(Unit.PHASE)){
                aBand.setName("Unw"+ b.getName());
            }
            trgProduct.addBand(aBand);
        }
        ProductUtils.copyProductNodes(sourceProduct, trgProduct);
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
        final String[] entries = directory.list();
        if (entries == null) {
            return new File[0];
        }
        ArrayList<File> configFiles = new ArrayList<>();
        for(String file: entries){
            File aFile = new File(directory, file);
            if(file.endsWith("snaphu.conf") && ! file.equals("snaphu.conf")){
                configFiles.add(aFile);
            }
        }
        return configFiles.toArray(new File[0]);
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
        // Extract the snaphu args from line 7 of the config file, which has the form:
        //   "#  snaphu <arg1> <arg2> ..."   (the leading `# ` or `#snaphu ` is comment-prefix).
        // The previous code did `substring(14)` which assumed an exact comment prefix length
        // and would throw or capture wrong characters if the template changed; we instead
        // strip the comment prefix and the literal `snaphu` token defensively.
        List<String> snaphuArgs = null;
        try (BufferedReader in = new BufferedReader(new FileReader(configFile), 1024)) {
            for (int x = 0; x < 6; x++) {
                in.readLine();
            }
            final String line7 = in.readLine();
            if (line7 == null) {
                throw new IOException("SNAPHU config file " + configFile + " has fewer than 7 lines");
            }
            String stripped = line7;
            // Drop comment prefix.
            while (!stripped.isEmpty() && (stripped.charAt(0) == '#' || Character.isWhitespace(stripped.charAt(0)))) {
                stripped = stripped.substring(1);
            }
            // Drop the literal "snaphu" token if present (we substitute our own binary path).
            if (stripped.toLowerCase().startsWith("snaphu")) {
                stripped = stripped.substring("snaphu".length()).trim();
            }
            snaphuArgs = new ArrayList<>();
            for (String tok : stripped.split("\\s+")) {
                if (!tok.isEmpty()) {
                    snaphuArgs.add(tok);
                }
            }
        }
        if (snaphuArgs != null && !snaphuArgs.isEmpty()) {
            // Build the argv list explicitly so paths containing spaces in the binary
            // location don't get fragmented by string-form whitespace tokenization.
            final List<String> argv = new ArrayList<>();
            argv.add(snaphuBinary.toString());
            argv.addAll(snaphuArgs);
            final ProcessBuilder pb = new ProcessBuilder(argv);
            pb.directory(workingDir);
            pb.redirectErrorStream(true);
            final Process proc = pb.start();

            try (BufferedReader stdInput = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                 FileWriter logWriter = new FileWriter(logFile, true)) {
                String s;
                while ((s = stdInput.readLine()) != null) {
                    if (pm.isCanceled()) {
                        proc.destroy();
                        throw new IOException("SNAPHU unwrap cancelled by user");
                    }
                    logWriter.write(s);
                    logWriter.write(System.lineSeparator());
                    logWriter.flush();
                    pm.setTaskName(msgPrefix + s);
                }
            }

            try {
                final int exitCode = proc.waitFor();
                if (exitCode != 0) {
                    throw new IOException("SNAPHU exited with code " + exitCode
                            + " for config " + configFile.getName());
                }
            } catch (InterruptedException e) {
                proc.destroy();
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for SNAPHU", e);
            }
        }
    }


    /**
     * Copy unwrapped phase bands from each ENVI file directly into the supplied target
     * product. Previously this method mutated the OPERATOR'S INPUT product (removing
     * wrapped bands and inserting unwrapped ones), which violates GPF's immutable-source
     * contract and corrupts any downstream graph node that still holds a reference to
     * the source. We now write to the target and close each ENVI reader so file handles
     * don't accumulate.
     */
    void assembleUnwrappedFilesIntoSingularProduct(File directory, Product targetProduct) throws IOException {
        File [] fileNames = directory.listFiles((dir, name) -> name.startsWith("UnwPhase") && name.endsWith(".hdr"));
        if (fileNames == null) {
            throw new IOException("Unable to list SNAPHU output directory: " + directory);
        }
        final EnviProductReaderPlugIn readerPlugIn = new EnviProductReaderPlugIn();
        for (File fileName : fileNames) {
            final ProductReader enviProductReader = readerPlugIn.createReaderInstance();
            Product enviProduct = null;
            try {
                enviProduct = enviProductReader.readProductNodes(fileName, null);
                final String unwrappedPhaseBandName = enviProduct.getBands()[0].getName().replace(".snaphu.hdr", "");
                ProductUtils.copyBand(unwrappedPhaseBandName, enviProduct, targetProduct, true);
            } finally {
                if (enviProduct != null) {
                    enviProduct.dispose();
                }
                try {
                    enviProductReader.close();
                } catch (IOException ignore) {
                    // best-effort close
                }
            }
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

            // Reset the target product's bands (placeholder bands from initialize), then
            // copy the assembled unwrapped phase bands directly into the target — no
            // mutation of the source product.
            final Product targetProduct = getTargetProduct();
            for (Band b : targetProduct.getBands()) {
                targetProduct.removeBand(b);
            }
            assembleUnwrappedFilesIntoSingularProduct(snaphuProcessingLocation, targetProduct);

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
