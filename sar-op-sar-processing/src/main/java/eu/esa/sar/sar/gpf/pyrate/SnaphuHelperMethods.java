package eu.esa.sar.sar.gpf.pyrate;

import com.bc.ceres.core.ProgressMonitor;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.core.util.io.FileDownloader;
import org.esa.snap.dataio.envi.EnviProductReaderPlugIn;

import eu.esa.sar.io.gamma.GammaProductWriter;

import org.esa.snap.engine_utilities.util.ZipUtils;
import org.jlinda.core.utils.DateUtils;
import org.jlinda.nest.dataio.SnaphuExportOp;

import java.io.*;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;

// Class to keep all SNAPHU related tasks in its own space without cluttering up the PyRate operator class.
public class SnaphuHelperMethods {

    // All SNAPHU export & snaphu unwrapping method calls occurs in this method.
    // Takes in an initialized PyRate Export operator, and a source product containing a coregistered stack of
    // wrapped interferograms.
    //
    // Returns an unwrapped stack of coregistered interferograms.
    public static Product processSnaphu(PyRateExportOp pyRateExportOp, Product sourceProduct) throws IOException {
        // Perform SNAPHU-Export
        Product product = SnaphuHelperMethods.setupSnaphuExportOperator(pyRateExportOp, sourceProduct).getTargetProduct();

        // Bands need to be read in fully before writing out to avoid data access errors.
        for(Band b: product.getBands()){
            b.readRasterDataFully(ProgressMonitor.NULL);
        }

        // Write out product to the snaphu processing location folder for unwrapping.
        ProductIO.writeProduct(product, pyRateExportOp.snaphuProcessingLocation.getAbsolutePath(), "snaphu");


        // Download, or locate the downloaded SNAPHU binary within the specified SNAPHU installation location.
        File snaphuBinary = downloadSnaphu(pyRateExportOp.snaphuInstallLocation);

        File snaphuLogFile = new File(pyRateExportOp.snaphuProcessingLocation, "snaphu-log-" +
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss")) + ".log" );
        // Find all SNAPHU configuration files and execute them.
        String [] files = pyRateExportOp.snaphuProcessingLocation.list();
        for(String file: files){
            File aFile = new File(pyRateExportOp.snaphuProcessingLocation, file);
            if(file.endsWith("snaphu.conf") && ! file.equals("snaphu.conf") && !pyRateExportOp.testingDisableUnwrapStep){
                callSnaphuUnwrap(snaphuBinary, aFile, snaphuLogFile);
            }
        }
        // Read in the unwrapped phase bands and assemble back into one product.
        return assembleUnwrappedFilesIntoSingularProduct(pyRateExportOp.snaphuProcessingLocation);
    }

    // Create a SNAPHU export operator, using parameters defined by PyRateExportOp.
    public static SnaphuExportOp setupSnaphuExportOperator(PyRateExportOp pyRateExportOp, Product sourceProduct){
        SnaphuExportOp snaphuExportOp = new SnaphuExportOp();
        snaphuExportOp.setParameter("statCostMode", pyRateExportOp.statCostMode);
        snaphuExportOp.setParameter("initMethod", pyRateExportOp.initMethod);
        snaphuExportOp.setParameter("numberOfTileRows", pyRateExportOp.numberOfTileRows);
        snaphuExportOp.setParameter("numberOfTileCols", pyRateExportOp.numberOfTileCols);
        snaphuExportOp.setParameter("numberOfProcessors", pyRateExportOp.numberOfProcessors);
        snaphuExportOp.setParameter("rowOverlap", pyRateExportOp.rowOverlap);
        snaphuExportOp.setParameter("colOverlap", pyRateExportOp.colOverlap);
        snaphuExportOp.setParameter("tileCostThreshold", pyRateExportOp.tileCostThreshold);
        snaphuExportOp.setParameter("targetFolder", pyRateExportOp.processingLocation);
        snaphuExportOp.setSourceProduct(sourceProduct);
        return snaphuExportOp;

    }

    // Given an install location, download the SNAPHU binary and return the location of the executible.
    private static File downloadSnaphu(File snaphuInstallLocation) throws IOException {
        final String linuxDownloadPath = "http://step.esa.int/thirdparties/snaphu/1.4.2-2/snaphu-v1.4.2_linux.zip";
        final String windowsDownloadPath = "http://step.esa.int/thirdparties/snaphu/2.0.4/snaphu-v2.0.4_win64.zip";
        final String windows32DownloadPath = "http://step.esa.int/thirdparties/snaphu/1.4.2-2/snaphu-v1.4.2_win32.zip";

        boolean isDownloaded;
        File snaphuBinaryLocation;

        // Check if we have just been given the path to the SNAPHU binary
        if (isSnaphuBinary(snaphuInstallLocation)){
            isDownloaded = true;
            snaphuBinaryLocation = snaphuInstallLocation;
        }else{ // We haven't been just given the binary location.

            // Get parent dir if passed in a file somehow
            if(! snaphuInstallLocation.isDirectory()){
                snaphuInstallLocation = snaphuInstallLocation.getParentFile();
            }
            snaphuBinaryLocation = findSnaphuBinary(snaphuInstallLocation);
            isDownloaded = snaphuBinaryLocation != null;
        }
        if (! isDownloaded){
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
        }

        return snaphuBinaryLocation;
    }

    // Check to see if a passed in file is the SNAPHU executable.
    public static boolean isSnaphuBinary(File file){
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

    // Iterate through a given directory and locate the SNAPHU binary within it.
    // Returns null if no SNAPHU binary is found.
    public static File findSnaphuBinary(File rootDir){
        Collection<File> files = FileUtils.listFilesAndDirs(rootDir, TrueFileFilter.INSTANCE, DirectoryFileFilter.DIRECTORY );
        for (File file : files){
            if(isSnaphuBinary(file)){
                return file;
            }
        }
        return null;
    }

    // Unwrap a singular interferogram given a SNAPHU config file and path to the SNAPHU binary.
    private static void callSnaphuUnwrap(File snaphuBinary, File configFile, File logFile) throws IOException {
        File workingDir = configFile.getParentFile();
        String command = null;
        try(BufferedReader in = new BufferedReader(new FileReader(configFile), 1024)){
            // SNAPHU command is on the 7th line
            for(int x = 0; x < 6; x++){
                in.readLine();
            }
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
                System.out.println(s);
            }
            // Read any errors from the attempted command
            while ((s = stdError.readLine()) != null) {

                FileUtils.write(logFile, FileUtils.readFileToString(logFile, "utf-8") + "\n" + s);
                System.out.println(s);
            }
        }
    }

    // Given an input directory containing unwrapped phase bands, read them all in and assemble into one product
    // containing all unwrapped phase bands as separate bands.
    private static Product assembleUnwrappedFilesIntoSingularProduct(File directory) throws IOException {
        File [] fileNames = directory.listFiles((dir, name) -> name.startsWith("UnwPhase") && name.endsWith(".hdr"));

        Product [] enviProducts = new Product[fileNames.length];
        EnviProductReaderPlugIn readerPlugIn = new EnviProductReaderPlugIn();
        ProductReader enviProductReader = readerPlugIn.createReaderInstance();
        enviProducts[0] = enviProductReader.readProductNodes(fileNames[0], null);
        for (int x = 1; x < enviProducts.length; x++){
            enviProducts[x] = enviProductReader.readProductNodes(fileNames[x], null);
            ProductUtils.copyBand(enviProducts[x].getBands()[0].getName(), enviProducts[x], enviProducts[0], true);
        }
        return enviProducts[0];
    }


}
