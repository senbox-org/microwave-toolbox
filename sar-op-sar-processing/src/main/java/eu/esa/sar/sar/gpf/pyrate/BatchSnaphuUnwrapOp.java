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
import org.esa.snap.core.util.SystemUtils;
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

    @Parameter(description = "The SNAPHU executable, or a folder that holds it or that SNAPHU is downloaded into",
            label="Snaphu Install Location")
    protected File snaphuInstallLocation;


    @TargetProduct
    private Product trgProduct;

    // Unw* bands created in initialize() so that downstream operators see them; doExecute points
    // their source images at SNAPHU's output.
    private final List<Band> unwrappedPlaceholders = new ArrayList<>();

    // SNAPHU output products backing the unwrapped bands; kept open until dispose().
    private final List<Product> enviProducts = new ArrayList<>();


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
        // The install location needs to be writable only if SNAPHU has to be downloaded into it (checked in
        // downloadSnaphu): a read-only system folder that already holds snaphu is fine.

        trgProduct = new Product(sourceProduct.getName(), sourceProduct.getProductType(), sourceProduct.getSceneRasterWidth(), sourceProduct.getSceneRasterHeight());
        trgProduct.setSceneGeoCoding(sourceProduct.getSceneGeoCoding());
        trgProduct.setName(sourceProduct.getName() + "_snaphu");
        for (Band b: sourceProduct.getBands()){
            if (Unit.PHASE.equals(b.getUnit())) {
                // Wrapped phase is replaced by the unwrapped band read back from SNAPHU's output.
                Band aBand = new Band("Unw" + b.getName(), b.getDataType(), b.getRasterWidth(), b.getRasterHeight());
                aBand.setUnit(Unit.ABS_PHASE);
                trgProduct.addBand(aBand);
                unwrappedPlaceholders.add(aBand);
            } else {
                // Coherence and other bands pass through so the output can feed SBAS / PyRate.
                ProductUtils.copyBand(b.getName(), sourceProduct, trgProduct, true);
            }
        }
        ProductUtils.copyProductNodes(sourceProduct, trgProduct);
        setTargetProduct(trgProduct);

    }

    // exact name: the Linux archive also holds src/snaphu.c, snaphu.h, man/snaphu.1, config/snaphu.conf.*
    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().startsWith("windows");
    }

    private static String snaphuBinaryName() {
        return isWindows() ? "snaphu.exe" : "snaphu";
    }

    /** An executable the user named directly. On Windows every file reports as executable, so it must be a .exe. */
    static boolean isUserSnaphuExecutable(final File file) {
        return file.isFile() && file.canExecute()
                && (!isWindows() || file.getName().toLowerCase().endsWith(".exe"));
    }

    boolean isSnaphuBinary(File file){
        return ! file.isDirectory() &&
                file.canExecute() &&
                file.getName().equals(snaphuBinaryName());
    }

    /**
     * Like {@link #findSnaphuBinary}, but also accepts a binary that lacks the execute permission and grants it:
     * zip entries carry no Unix permissions, so a freshly unzipped Linux binary is never executable.
     */
    File findOrMakeExecutableSnaphuBinary(File rootDir) {
        final File binary = findSnaphuBinary(rootDir);
        if (binary != null) {
            return binary;
        }
        for (File file : FileUtils.listFiles(rootDir, TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE)) {
            if (file.getName().equals(snaphuBinaryName()) && file.setExecutable(true) && isSnaphuBinary(file)) {
                return file;
            }
        }
        return null;
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
            if(b.getUnit() != null && b.getUnit().contains(unit)){
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

    private static final String LINUX_DOWNLOAD_PATH = "https://step.esa.int/thirdparties/snaphu/1.4.2-2/snaphu-v1.4.2_linux.zip";
    private static final String WINDOWS64_DOWNLOAD_PATH = "https://step.esa.int/thirdparties/snaphu/2.0.4/snaphu-v2.0.4_win64.zip";
    private static final String WINDOWS32_DOWNLOAD_PATH = "https://step.esa.int/thirdparties/snaphu/1.4.2-2/snaphu-v1.4.2_win32.zip";

    /**
     * @return the SNAPHU archive for this platform, or null when no build is published for it (macOS).
     */
    static String getSnaphuDownloadUrl(String osName, String osArch) {
        final String os = osName.toLowerCase();
        if (os.contains("windows")) {
            return osArch.equals("amd64") ? WINDOWS64_DOWNLOAD_PATH : WINDOWS32_DOWNLOAD_PATH;
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return null;
        }
        return LINUX_DOWNLOAD_PATH;
    }

    File downloadSnaphu(File snaphuInstallLocation) throws IOException {
        boolean isDownloaded;
        File snaphuBinaryLocation;

        // Check if we have just been given the path to the SNAPHU binary
        if (isSnaphuBinary(snaphuInstallLocation)){
            snaphuBinaryLocation = snaphuInstallLocation;
            return snaphuBinaryLocation;
        }
        // A file is the user's own binary (the usual set-up on macOS, where nothing is downloaded): never
        // download into it. Any name is accepted (e.g. snaphu-2.0.6); the exact-name rule is only for searching
        // a folder, where src/snaphu.c and the like must be skipped. A "snaphu" copy that lost its execute bit
        // is repaired, anything else is reported.
        if (snaphuInstallLocation.isFile()) {
            if (isUserSnaphuExecutable(snaphuInstallLocation)) {
                return snaphuInstallLocation;
            }
            if (snaphuInstallLocation.getName().equals(snaphuBinaryName())
                    && snaphuInstallLocation.setExecutable(true) && isSnaphuBinary(snaphuInstallLocation)) {
                return snaphuInstallLocation;
            }
            throw new IOException("Snaphu Install Location " + snaphuInstallLocation + " is not executable"
                    + (isWindows() ? " (expected a .exe file)" : "; make it executable (chmod +x)")
                    + ", or set the location to the folder that holds " + snaphuBinaryName() + ".");
        }
        // Reuse a binary downloaded by an earlier run instead of downloading it again.
        if (snaphuInstallLocation.isDirectory()) {
            final File existing = findOrMakeExecutableSnaphuBinary(snaphuInstallLocation);
            if (existing != null) {
                return existing;
            }
        }
        String operatingSystem = System.getProperty("os.name");
        final String downloadPath = getSnaphuDownloadUrl(operatingSystem, System.getProperty("os.arch"));
        if (downloadPath == null) {
            throw new IOException("No SNAPHU build can be downloaded for " + operatingSystem
                    + ". Install SNAPHU and set the Snaphu Install Location to the snaphu executable or its folder.");
        }
        // checked before the download starts: otherwise it fails afterwards with a bare FileNotFoundException,
        // or offline with a network error that hides the real problem
        if (!snaphuInstallLocation.exists()) {
            throw new IOException("Snaphu Install Location " + snaphuInstallLocation + " does not exist. Select the "
                    + "snaphu executable, or an existing folder to download SNAPHU into.");
        }
        if (!Files.isWritable(snaphuInstallLocation.toPath())) {
            throw new IOException("Snaphu Install Location " + snaphuInstallLocation + " holds no " +
                    snaphuBinaryName() + " and is not writeable, so SNAPHU cannot be downloaded into it.");
        }
        File zipFile = FileDownloader.downloadFile(new URL(downloadPath), snaphuInstallLocation, null);
        ZipUtils.unzip(zipFile.toPath(), snaphuInstallLocation.toPath(), true);
        snaphuBinaryLocation = findOrMakeExecutableSnaphuBinary(snaphuInstallLocation);
        if (snaphuBinaryLocation == null) {
            throw new IOException("No executable " + snaphuBinaryName() + " found in " + snaphuInstallLocation
                    + " after downloading " + downloadPath + ". Install SNAPHU and set the Snaphu Install Location"
                    + " to the snaphu executable or its folder.");
        }

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
        // Without arguments SNAPHU would not run, and a stale output of an earlier run would be read instead.
        if (snaphuArgs == null || snaphuArgs.isEmpty()) {
            throw new IOException("No snaphu command on line 7 of SNAPHU config file " + configFile
                    + "; run Snaphu Export again.");
        }
        // Build the argv list explicitly so paths containing spaces in the binary
        // location don't get fragmented by string-form whitespace tokenization.
        final List<String> argv = new ArrayList<>();
        argv.add(snaphuBinary.toString());
        argv.addAll(snaphuArgs);
        final ProcessBuilder pb = new ProcessBuilder(argv);
        pb.directory(workingDir);
        pb.redirectErrorStream(true);
        final Process proc = pb.start();
        try {
            try (BufferedReader stdInput = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                 FileWriter logWriter = new FileWriter(logFile, true)) {
                String s;
                while ((s = stdInput.readLine()) != null) {
                    if (pm.isCanceled()) {
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
                            + " for config " + configFile.getName() + "; see " + logFile);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for SNAPHU", e);
            }
        } finally {
            // on cancel, interrupt or any I/O error SNAPHU must not keep running in the background
            if (proc.isAlive()) {
                proc.destroyForcibly();
            }
        }
    }

    /** Progress (out of 100, after the first 1) once {@code done} of {@code total} configurations have run. */
    static int progressAfter(final int done, final int total) {
        return total <= 0 ? 0 : (int) (98L * done / total);
    }


    /**
     * Copy unwrapped phase bands from each ENVI file into the supplied target product.
     * The target bands share the ENVI bands' reader-backed source images, so the ENVI products
     * stay open until {@link #dispose()}: disposing them here left the bands all zero.
     * An Unw* band that already exists in the target (created in initialize) is kept as the same
     * Band object, because downstream operators initialised after this one hold a reference to it.
     *
     * @return the target bands that now carry SNAPHU output
     */
    List<Band> assembleUnwrappedFilesIntoSingularProduct(File directory, Product targetProduct) throws IOException {
        File [] fileNames = directory.listFiles((dir, name) -> name.startsWith("UnwPhase") && name.endsWith(".hdr"));
        if (fileNames == null) {
            throw new IOException("Unable to list SNAPHU output directory: " + directory);
        }
        final List<Band> filled = new ArrayList<>();
        final EnviProductReaderPlugIn readerPlugIn = new EnviProductReaderPlugIn();
        for (File fileName : fileNames) {
            final ProductReader enviProductReader = readerPlugIn.createReaderInstance();
            final Product enviProduct;
            try {
                enviProduct = enviProductReader.readProductNodes(fileName, null);
            } catch (IOException | RuntimeException e) {
                closeQuietly(enviProductReader);
                throw e;
            }
            enviProducts.add(enviProduct); // disposed in dispose(), after the target has been written

            final Band enviBand = enviProduct.getBands()[0];
            final String unwrappedPhaseBandName = enviBand.getName().replace(".snaphu.hdr", "");
            Band unwBand = targetProduct.getBand(unwrappedPhaseBandName);
            if (unwBand != null && (unwBand.getDataType() != enviBand.getDataType()
                    || unwBand.getRasterWidth() != enviBand.getRasterWidth()
                    || unwBand.getRasterHeight() != enviBand.getRasterHeight())) {
                targetProduct.removeBand(unwBand);
                unwBand = null;
            }
            final boolean isNew = unwBand == null;
            if (isNew) {
                unwBand = new Band(unwrappedPhaseBandName, enviBand.getDataType(),
                        enviBand.getRasterWidth(), enviBand.getRasterHeight());
            }
            ProductUtils.copyRasterDataNodeProperties(enviBand, unwBand);
            unwBand.setUnit(Unit.ABS_PHASE);
            unwBand.setDescription("Unwrapped phase");
            unwBand.setSourceImage(enviBand.getSourceImage());
            if (isNew) {
                targetProduct.addBand(unwBand);
            }
            filled.add(unwBand);
        }
        return filled;
    }

    private static void closeQuietly(ProductReader reader) {
        try {
            reader.close();
        } catch (IOException ignore) {
            // best-effort close
        }
    }

    @Override
    public void dispose() {
        for (Product enviProduct : enviProducts) {
            final ProductReader reader = enviProduct.getProductReader();
            enviProduct.dispose();
            if (reader != null) {
                closeQuietly(reader);
            }
        }
        enviProducts.clear();
        super.dispose();
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

            SystemUtils.LOG.info("BatchSnaphuUnwrap: using " + snaphuBinary);

            for (int x = 0; x < configFiles.length; x++){
                // Display current product indicator on the progress monitor.
                int count = x + 1;
                // Add prefix to progress monitor to indicate which product we are currently on.
                String prefix = "(" + count + "/" + configFiles.length + "): ";
                callSnaphuUnwrap(snaphuBinary, configFiles[x], snaphuLogFile, pm, prefix);
                // Move progress bar for each product processed (never backwards, also for more than 100 pairs).
                pm.worked(progressAfter(count, configFiles.length) - progressAfter(x, configFiles.length));
            }

            // Point the Unw* bands from initialize at SNAPHU's output; coherence and other
            // pass-through bands are kept. A missing output is an error: a downstream operator
            // initialised earlier already holds the Unw* band and would write it as all zeros.
            final Product targetProduct = getTargetProduct();
            final List<Band> filled = assembleUnwrappedFilesIntoSingularProduct(snaphuProcessingLocation, targetProduct);
            final List<String> missing = new ArrayList<>();
            for (Band b : unwrappedPlaceholders) {
                if (!filled.contains(b)) {
                    missing.add(b.getName() + ".snaphu.hdr");
                }
            }
            if (!missing.isEmpty()) {
                throw new OperatorException("SNAPHU output not found in " + snaphuProcessingLocation + ": "
                        + String.join(", ", missing) + ". Run Snaphu Export on this product and unwrap every interferogram.");
            }

        } catch (IOException e) {
            throw new OperatorException(e.getMessage(), e);
        } finally {
            pm.done();
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
            super(BatchSnaphuUnwrapOp.class);
        }
    }
}
