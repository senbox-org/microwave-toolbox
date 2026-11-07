package eu.esa.snap.cimr;

import com.bc.ceres.annotation.STTM;
import org.esa.snap.core.dataio.DecodeQualification;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.util.io.SnapFileFilter;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;


public class CimrL1BProductReaderPluginTest {

    private CimrL1BProductReaderPlugin plugIn;


    @Before
    public void setUp() {
        plugIn = new CimrL1BProductReaderPlugin();
    }


    @Test
    public void getDecodeQualification_wrongExtension() {
        final File file = new File("ignore_the_name.txt");

        assertEquals(DecodeQualification.UNABLE, plugIn.getDecodeQualification(file));
    }

    @Test
    public void getDecodeQualification_correctExtension_wrongFilePattern() {
        final File file = new File("ignore_the_name.nc");

        assertEquals(DecodeQualification.UNABLE, plugIn.getDecodeQualification(file));
    }

    @Test
    @STTM("SNAP-4262")
    public void getDecodeQualification_correctExtension_correctFilePattern() {
        final File specExample = new File("W_PT-DME-Lisbon-SAT-CIMR-1B_C_DME_20250101T121212_G_O_20250101T103000_20250101T104000_O_N_001.nc");
        final File september2026Product = new File("W_PT-DME-Lisbon-SAT-CIMR-1B_C_DME_20260921T093512_G_D_20280105T164500_20280105T165100_T_N_002.nc");

        assertEquals(DecodeQualification.INTENDED, plugIn.getDecodeQualification(specExample));
        assertEquals(DecodeQualification.INTENDED, plugIn.getDecodeQualification(september2026Product));
    }

    @Test
    @STTM("SNAP-4262")
    public void getDecodeQualification_correctExtension_oldFilePattern() {
        final File oldLegacyName = new File("W_PT-DME-Lisbon-SAT-CIMR-1B_C_DME_20230420T103323_LD_20280110T114800_20280110T115700_TN.nc");
        final File missingDispositionAndProcessingMode = new File("W_xx-esa-Lisbon-SAT-CIMR-1B_C_DME_20251029T000420_G_20280105T121500_20280105T121600_002.nc");
        final File march2026IntermediateName = new File("W_PT-DME-Lisbon-SAT-CIMR-1B_C_DME_20260312T140912_G_D_20280105T094800_20280105T095300_002.nc");

        assertEquals(DecodeQualification.UNABLE, plugIn.getDecodeQualification(oldLegacyName));
        assertEquals(DecodeQualification.UNABLE, plugIn.getDecodeQualification(missingDispositionAndProcessingMode));
        assertEquals(DecodeQualification.UNABLE, plugIn.getDecodeQualification(march2026IntermediateName));
    }


    @Test
    public void getInputTypes() {
        final Class[] inputTypes = plugIn.getInputTypes();
        assertEquals(2, inputTypes.length);
        assertEquals(File.class, inputTypes[0]);
        assertEquals(String.class, inputTypes[1]);
    }

    @Test
    public void createReaderInstance() {
        final ProductReader readerInstance = plugIn.createReaderInstance();

        assertNotNull(readerInstance);
        assertTrue(readerInstance instanceof CimrL1BProductReader);
    }

    @Test
    public void getFormatNames() {
        final String[] formatNames = plugIn.getFormatNames();

        assertEquals(1, formatNames.length);
        assertEquals("CIMR-L1B", formatNames[0]);
    }

    @Test
    public void getDefaultFileExtensions() {
        final String[] extensions = plugIn.getDefaultFileExtensions();

        assertEquals(1, extensions.length);
        assertEquals(".nc", extensions[0]);
    }

    @Test
    public void getDescription() {
        final String description = plugIn.getDescription(null);
        assertEquals("CIMR Level 1B Data Products in NetCDF Format", description);
    }

    @Test
    public void getProductFileFilter() {
        final SnapFileFilter productFileFilter = plugIn.getProductFileFilter();
        assertArrayEquals(plugIn.getDefaultFileExtensions(), productFileFilter.getExtensions());
        assertEquals(plugIn.getFormatNames()[0], productFileFilter.getFormatName());

        assertEquals("CIMR Level 1B Data Products in NetCDF Format (*.nc)", productFileFilter.getDescription());
    }
}