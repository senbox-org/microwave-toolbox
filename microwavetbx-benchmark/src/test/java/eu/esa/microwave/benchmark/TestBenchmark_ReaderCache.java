/*
 * Copyright (C) 2026 SkyWatch Space Applications Inc. https://www.skywatch.com
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */
package eu.esa.microwave.benchmark;

import eu.esa.sar.commons.io.GeoTiffCacheSupport;
import eu.esa.sar.commons.test.TestData;
import eu.esa.sar.io.netcdf.NetCDFCacheSupport;
import eu.esa.sar.io.nisar.subreaders.NisarSubReader;
import org.esa.snap.core.datamodel.Product;
import org.junit.Test;

import java.io.File;

/**
 * Benchmarks each microwave-toolbox reader that uses ProductCache,
 * with the cache both enabled and disabled, to measure the caching impact.
 *
 * Readers covered:
 *  - Sentinel1 (GeoTIFF) — GRD and SLC
 *  - Capella (GeoTIFF)
 *  - NISAR (HDF5)
 *  - ICEYE (NetCDF/HDF5)
 *  - Cosmo-SkyMed (NetCDF/HDF5)
 *  - Kompsat-5 (NetCDF/HDF5)
 */
public class TestBenchmark_ReaderCache extends BaseBenchmarks {

    // Sentinel1 files come from BaseBenchmarks (slcFile, grdFile, grdZipFile).

    private static final File capellaSLC = new File(TestData.inputSAR +
            "Capella/Spot/SLC/CAPELLA_C02_SP_SLC_HH_20201209213329_20201209213332.json");

    private static final File nisarRSLC = new File(TestData.inputSAR +
            "NISAR/NISAR_L1_PR_RSLC_001_005_A_219_2005_DHDH_A_20081012T060910_20081012T060926_P01101_F_N_J_001.h5");

    private static final File iceyeSLC = new File(TestData.inputSAR +
            "Iceye/SLC/ICEYE_SLC_GRD_Example_Spotlight_SAR_Imagery/ICEYE_SLC_Data_Jurong_Island_Singapore_SL_092019/ICEYE_SLC_SL_10402_20190920T075151.h5");

    private static final File cosmoSCS = new File(TestData.inputSAR +
            "Cosmo/level1B/hdf5/EL20100624_102783_1129476.6.2/CSKS2_SCS_B_S2_01_VV_RA_SF_20100623045532_20100623045540.h5");

    private static final File k5SCS = new File(TestData.inputSAR +
            "K5/HDF/K5_20170125111222_000000_18823_A_UH28_HH_SCS_B_L1A/K5_20170125111222_000000_18823_A_UH28_HH_SCS_B_L1A_Aux.xml");

    public TestBenchmark_ReaderCache() {
        super("ReaderCache");
    }

    // --- Sentinel1 SLC ---

    @Test
    public void testS1SLC_withCache() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        readWriteWithCache(slcFile, true);
    }

    @Test
    public void testS1SLC_noCache() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        readWriteWithCache(slcFile, false);
    }

    // --- Sentinel1 GRD ---

    @Test
    public void testS1GRD_withCache() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        readWriteWithCache(grdFile, true);
    }

    @Test
    public void testS1GRD_noCache() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        readWriteWithCache(grdFile, false);
    }

    // --- Capella SLC ---

    @Test
    public void testCapella_withCache() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        readWriteWithCache(capellaSLC, true);
    }

    @Test
    public void testCapella_noCache() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        readWriteWithCache(capellaSLC, false);
    }

    // --- NISAR RSLC ---

    @Test
    public void testNISAR_withCache() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        readWriteWithCache(nisarRSLC, true);
    }

    @Test
    public void testNISAR_noCache() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        readWriteWithCache(nisarRSLC, false);
    }

    // --- ICEYE SLC ---

    @Test
    public void testIceye_withCache() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        readWriteWithCache(iceyeSLC, true);
    }

    @Test
    public void testIceye_noCache() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        readWriteWithCache(iceyeSLC, false);
    }

    // --- Cosmo-SkyMed SCS (NetCDF) ---

    @Test
    public void testCosmoNetCDF_withCache() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        readWriteWithCache(cosmoSCS, true);
    }

    @Test
    public void testCosmoNetCDF_noCache() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        readWriteWithCache(cosmoSCS, false);
    }

    // --- Kompsat-5 SCS ---

    @Test
    public void testK5_withCache() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        readWriteWithCache(k5SCS, true);
    }

    @Test
    public void testK5_noCache() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        readWriteWithCache(k5SCS, false);
    }

    private void readWriteWithCache(final File srcFile, final boolean cacheEnabled) throws Exception {
        setCacheFlags(cacheEnabled);
        Benchmark b = new Benchmark(groupName, testName) {
            @Override
            protected void execute() throws Exception {
                final Product srcProduct = read(srcFile);
                write(srcProduct, outputFolder, DIMAP);
                srcProduct.dispose();
            }
        };
        b.run();
    }

    private static void setCacheFlags(final boolean enabled) {
        GeoTiffCacheSupport.USE_PRODUCT_CACHE = enabled;
        NetCDFCacheSupport.USE_PRODUCT_CACHE = enabled;
        NisarSubReader.USE_PRODUCT_CACHE = enabled;
    }
}
