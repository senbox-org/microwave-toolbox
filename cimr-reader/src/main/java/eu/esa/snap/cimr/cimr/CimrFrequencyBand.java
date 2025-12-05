package eu.esa.snap.cimr.cimr;

public enum CimrFrequencyBand {

    L_BAND(2.12e8f),
    C_BAND(4.33e7f),
    X_BAND(2.82e7f),
    KU_BAND(1.6e7f),
    KA_BAND(8.22e6f);


    private final float spectralWaveLength;

    CimrFrequencyBand(float spectralWaveLength) {
        this.spectralWaveLength = spectralWaveLength;
    }

    public float getSpectralWaveLength() {
        return spectralWaveLength;
    }
}
