package eu.esa.snap.cimr.cimr;

import org.junit.Test;

import static org.junit.Assert.*;


public class CimrFrequencyBandTest {


    @Test
    public void testSpectralWaveLengths() {
        float sw_L_BAND = CimrFrequencyBand.L_BAND.getSpectralWaveLength();
        float sw_C_BAND = CimrFrequencyBand.C_BAND.getSpectralWaveLength();
        float sw_X_BAND = CimrFrequencyBand.X_BAND.getSpectralWaveLength();
        float sw_KU_BAND = CimrFrequencyBand.KU_BAND.getSpectralWaveLength();
        float sw_KA_BAND = CimrFrequencyBand.KA_BAND.getSpectralWaveLength();

        assertEquals(212000000f, sw_L_BAND, 0.1);
        assertEquals(43300000f, sw_C_BAND, 0.1);
        assertEquals(28200000f, sw_X_BAND, 0.1);
        assertEquals(16000000f, sw_KU_BAND, 0.1);
        assertEquals(8220000f, sw_KA_BAND, 0.1);
    }
}