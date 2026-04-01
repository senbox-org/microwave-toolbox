/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.io.nisar.subreaders;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.product.Missions;
import eu.esa.sar.io.netcdf.NetCDFUtils;
import eu.esa.sar.io.netcdf.NetcdfConstants;
import eu.esa.sar.io.nisar.util.NisarXConstants;
import eu.esa.sar.io.pcidsk.UTM2LatLon;
import org.esa.snap.core.dataio.IllegalFileFormatException;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.StxFactory;
import org.esa.snap.core.datamodel.TiePointGeoCoding;
import org.esa.snap.core.datamodel.TiePointGrid;
import org.esa.snap.core.util.StopWatch;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.core.util.geotiff.EPSGCodes;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import ucar.ma2.Array;
import ucar.ma2.ArrayStructure;
import ucar.ma2.ArrayStructureBB;
import ucar.ma2.DataType;
import ucar.ma2.InvalidRangeException;
import ucar.ma2.Range;
import ucar.ma2.StructureMembers;
import ucar.nc2.Attribute;
import ucar.nc2.Group;
import ucar.nc2.NetcdfFile;
import ucar.nc2.Variable;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ucar.ma2.DataType.STRUCTURE;

public abstract class NisarSubReader {

    protected final Map<Band, Variable> bandMap = new HashMap<>();

    // Row-strip LRU cache for compound/Structure reads.
    // SNAP requests tiles from many Y positions concurrently (multi-threaded),
    // so we cache multiple strips to avoid thrashing.
    private static final int STRIP_HEIGHT = 512;
    private static final int MAX_CACHED_STRIPS = 8;

    private static class StripData {
        final float[] real;
        final float[] imag;
        final int startRow, endRow, width;
        StripData(float[] real, float[] imag, int startRow, int endRow, int width) {
            this.real = real;
            this.imag = imag;
            this.startRow = startRow;
            this.endRow = endRow;
            this.width = width;
        }
    }

    // Key: variableIdentity | stripIndex — preserves LRU access order
    @SuppressWarnings("serial")
    private final Map<Long, StripData> stripCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, StripData> eldest) {
            return size() > MAX_CACHED_STRIPS;
        }
    };
    protected final DateFormat standardDateFormat = ProductData.UTC.createDateFormat("yyyy-MM-dd HH:mm:ss");
    protected NetcdfFile netcdfFile = null;
    protected Product product = null;
    protected String productType;
    protected boolean isComplex = true;

    public void close() throws IOException {
        if (netcdfFile != null) {
            netcdfFile.close();
            netcdfFile = null;
        }
    }

    protected void open(final File inputFile) throws IOException {
        this.netcdfFile = NetcdfFile.open(inputFile.getPath());
        if (netcdfFile == null) {
            close();
            throw new IllegalFileFormatException(inputFile.getName() +
                    " Could not be interpreted by the reader.");
        }

        if (netcdfFile.getRootGroup().getGroups().isEmpty()) {
            close();
            throw new IllegalFileFormatException("No netCDF groups found.");
        }
    }

    /**
     * Provides an implementation of the <code>readProductNodes</code> interface method. Clients implementing this
     * method can be sure that the input object and eventually the subset information has already been set.
     * <p/>
     * <p>This method is called as a last step in the <code>readProductNodes(input, subsetInfo)</code> method.
     */
    public Product readProduct(final ProductReader reader, final File inputFile) throws IOException {
        open(inputFile);

        try {
            final Group groupSAR = getSARGroup();
            final Group groupID = getIdentificationGroup(groupSAR);
            
            Group groupFrequency = getFrequencyAGroup(groupSAR);
            if (groupFrequency == null) {
                groupFrequency = getFrequencyBGroup(groupSAR);
            }
            
            if (groupFrequency == null) {
                 throw new IOException("No frequency group found (A or B)");
            }

            Variable[] rasterVariables = getRasterVariables(groupFrequency);

            final int rasterHeight = rasterVariables[0].getDimension(0).getLength();
            final int rasterWidth = rasterVariables[0].getDimension(1).getLength();
            productType = getProductType(groupID);

            product = new Product(inputFile.getName(),
                    productType,
                    rasterWidth, rasterHeight,
                    reader);
            product.setFileLocation(inputFile);

            addMetadataToProduct();
            addBandsToProduct();
            addTiePointGridsToProduct();
            addDopplerMetadata();

            // Set Stx for all bands that don't have it set yet
            // This must happen after all product setup is complete as some operations can clear Stx
            for (Band band : product.getBands()) {
                if (!band.isStxSet()) {
                    Variable var = bandMap.get(band);
                    if (var != null) {
                        setStxFromAttributes(band, var);
                    }
                    if (!band.isStxSet()) {
                        band.setStx(new StxFactory().withMinimum(0).withMaximum(1)
                                .withIntHistogram(false).withHistogramBins(new int[512]).create());
                    }
                }
            }

            return product;
        } catch (Exception e) {
            SystemUtils.LOG.severe(e.getMessage());
            throw new IOException(e);
        }
    }

    protected Group getSARGroup() {
        final Group groupScience = this.netcdfFile.getRootGroup().findGroup("science");
        Group sarGroup = groupScience.findGroup("LSAR");
        if (sarGroup == null) {
            sarGroup = groupScience.findGroup("SSAR");
        }
        return sarGroup;
    }

    protected Group getIdentificationGroup(final Group groupLSAR) {
        return groupLSAR.findGroup("identification");
    }

    protected Group getFrequencyAGroup(final Group groupLSAR) {
        final Group groupProductType = groupLSAR.findGroup(productType);
        final Group groupSwaths = groupProductType.findGroup("swaths");
        return groupSwaths.findGroup("frequencyA");
    }

    protected Group getFrequencyBGroup(final Group groupLSAR) {
        final Group groupProductType = groupLSAR.findGroup(productType);
        final Group groupSwaths = groupProductType.findGroup("swaths");
        return groupSwaths.findGroup("frequencyB");
    }

    protected Group[] getPolarizationGroups(final Group group) {
        List<Group> polGroups = new ArrayList<>();
        final Group groupHH = group.findGroup("HH");
        polGroups.add(groupHH);

        return polGroups.toArray(new Group[0]);
    }

    protected abstract Variable[] getRasterVariables(final Group group);

    protected String getProductType(Group groupID) throws Exception {
        return groupID.findVariable(NisarXConstants.PRODUCT_TYPE).readScalarString();
    }

    protected String getDescription(String filename, Group groupID) throws Exception {
        final String productType = getProductType(groupID);
        final Variable missionID = groupID.findVariable(NisarXConstants.MISSION);
        String description = filename + " - " + productType;
        if (missionID != null) {
            description += " - " + missionID.readScalarString();
        }
        return description;
    }

    protected void addBandsToProduct() {
        final Group groupSAR = getSARGroup();

        Group groupFreqA = getFrequencyAGroup(groupSAR);
        if (groupFreqA != null) {
            addBandsForFrequency(groupFreqA, "");
        }

        Group groupFreqB = getFrequencyBGroup(groupSAR);
        if (groupFreqB != null) {
            addBandsForFrequency(groupFreqB, "_S");
        }
    }

    protected abstract void addBandsForFrequency(Group groupFrequency, String suffix);

    protected void addMetadataToProduct() throws Exception {

        final MetadataElement origMetadataRoot = AbstractMetadata.addOriginalProductMetadata(product.getMetadataRoot());
        NetCDFUtils.addAttributes(origMetadataRoot, NetcdfConstants.GLOBAL_ATTRIBUTES_NAME,
                netcdfFile.getGlobalAttributes());

        for (Variable variable : netcdfFile.getVariables()) {
            try {
                NetCDFUtils.addVariableMetadata(origMetadataRoot, variable, 5000);
            } catch (Exception e) {
                SystemUtils.LOG.warning("Error reading metadata " + e.getMessage());
            }
        }

        addAbstractedMetadataHeader(product.getMetadataRoot());
    }

    protected void addAbstractedMetadataHeader(final MetadataElement root) throws Exception {

        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(root);
        final MetadataElement origMeta = AbstractMetadata.getOriginalProductMetadata(product);

        MetadataElement globals = origMeta.getElement("Global_Attributes");
        MetadataElement science = origMeta.getElement("science");
        final boolean isLSAR = science.containsElement("LSAR");
        MetadataElement sar = isLSAR ? science.getElement("LSAR") : science.getElement("SSAR");
        MetadataElement identification = sar.getElement("identification");

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PRODUCT, product.getName());
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.MISSION, Missions.NISAR);

        String title = globals.getAttributeString("title");
        product.setDescription(title);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SPH_DESCRIPTOR, title);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PRODUCT_TYPE, productType);

        MetadataElement lookDirection = identification.getElement("lookDirection");
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.antenna_pointing,
                lookDirection.getAttributeString("lookDirection").toLowerCase());

        MetadataElement trackNumber = identification.getElement("trackNumber");
        if (trackNumber != null) {
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.REL_ORBIT,
                    trackNumber.getAttributeInt("trackNumber"));
        }

        // absoluteOrbitNumber - for interferometric products, falls back to referenceAbsoluteOrbitNumber
        MetadataElement absoluteOrbitNumber = identification.getElement("absoluteOrbitNumber");
        if(absoluteOrbitNumber != null) {
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ABS_ORBIT,
                    absoluteOrbitNumber.getAttributeInt("absoluteOrbitNumber"));
        } else {
            MetadataElement refOrbit = identification.getElement("referenceAbsoluteOrbitNumber");
            if(refOrbit != null) {
                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ABS_ORBIT,
                        refOrbit.getAttributeInt("referenceAbsoluteOrbitNumber"));
            }
        }

        MetadataElement orbitPassDirection = identification.getElement("orbitPassDirection");
        if(orbitPassDirection != null) {
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PASS,
                    getOrbitPass(orbitPassDirection.getAttributeString("orbitPassDirection")));
        }

        // Processing time
        MetadataElement processingDateTime = identification.getElement("processingDateTime");
        if(processingDateTime != null) {
            ProductData.UTC procTime = parseNisarTime(processingDateTime, "processingDateTime");
            if(procTime != null) {
                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PROC_TIME, procTime);
            }
        }

        // Zero Doppler times - for interferometric products, fall back to reference times
        ProductData.UTC startTime = getTimeFromElement(identification,
                "zeroDopplerStartTime", "referenceZeroDopplerStartTime");
        if(startTime != null) {
            product.setStartTime(startTime);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_line_time, startTime);
        }

        ProductData.UTC endTime = getTimeFromElement(identification,
                "zeroDopplerEndTime", "referenceZeroDopplerEndTime");
        if(endTime != null) {
            product.setEndTime(endTime);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_line_time, endTime);
        }

        // Set ACQUISITION_MODE
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ACQUISITION_MODE, "Stripmap");

        // Set sample type based on product type
        if(productType.equals("RSLC") || productType.equals("GSLC")) {
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SAMPLE_TYPE, "COMPLEX");
        } else {
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SAMPLE_TYPE, "DETECTED");
        }

        // Set raster dimensions
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.num_output_lines, product.getSceneRasterHeight());
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.num_samples_per_line, product.getSceneRasterWidth());

        // Extract SAR parameters from frequency group
        addSARParameters(absRoot, sar);

        // Remove troublesome inputs elements that contain null-separated file paths
        // (demFiles, calFiles, antPatternFiles, etc.) which are illegal in XML
        MetadataElement productTypeElem = sar.getElement(productType);
        if(productTypeElem != null) {
            MetadataElement metadataElem = productTypeElem.getElement("metadata");
            if(metadataElem != null) {
                MetadataElement processingInformation = metadataElem.getElement("processingInformation");
                if(processingInformation != null) {
                    MetadataElement inputs = processingInformation.getElement("inputs");
                    if(inputs != null) {
                        String[] inputElementNames = inputs.getElementNames();
                        for(String name : inputElementNames) {
                            inputs.removeElement(inputs.getElement(name));
                        }
                    }
                }
            }
        }

        // Add orbit state vectors
        addOrbitStateVectors(absRoot);

    }

    private void addSARParameters(final MetadataElement absRoot, final MetadataElement sar) {
        try {
            final Group groupSAR = getSARGroup();
            final Group groupProductType = groupSAR.findGroup(productType);
            if(groupProductType == null) return;

            // Find swaths or grids group
            Group swathsOrGrids = groupProductType.findGroup("swaths");
            if(swathsOrGrids == null) {
                swathsOrGrids = groupProductType.findGroup("grids");
            }
            if(swathsOrGrids == null) return;

            // Get frequency group
            Group freqGroup = swathsOrGrids.findGroup("frequencyA");
            if(freqGroup == null) {
                freqGroup = swathsOrGrids.findGroup("frequencyB");
            }
            if(freqGroup == null) return;

            // Radar frequency
            Variable centerFreq = freqGroup.findVariable("acquiredCenterFrequency");
            if(centerFreq == null) {
                centerFreq = freqGroup.findVariable("processedCenterFrequency");
            }
            if(centerFreq == null) {
                centerFreq = freqGroup.findVariable("centerFrequency");
            }
            if(centerFreq != null) {
                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.radar_frequency,
                        centerFreq.readScalarDouble() / 1.0e6); // Hz to MHz
            }

            // PRF - search in frequency group and sub-groups
            Variable prf = findVariableInGroupTree(freqGroup, "nominalAcquisitionPRF");
            if(prf != null) {
                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.pulse_repetition_frequency,
                        prf.readScalarDouble());
            }

            // Slant range to first pixel - from slantRange array (first element)
            Variable slantRange = findSlantRangeVariable(swathsOrGrids, freqGroup, groupProductType);
            if(slantRange != null) {
                Array slantRangeArray = slantRange.read();
                double slantRangeToFirstPixel = slantRangeArray.getDouble(0);
                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.slant_range_to_first_pixel,
                        slantRangeToFirstPixel);
            }

            // Range spacing - search in frequency group tree, then processing parameters
            Variable rangeSpacing = findVariableInGroupTree(freqGroup, "slantRangeSpacing");
            if(rangeSpacing == null) {
                rangeSpacing = findVariableInGroupTree(freqGroup, "sceneCenterGroundRangeSpacing");
            }
            if(rangeSpacing == null) {
                rangeSpacing = findVariableInGroupTree(freqGroup, "xCoordinateSpacing");
            }
            if(rangeSpacing == null) {
                rangeSpacing = findInProcessingParams(groupProductType, "slantRangeSpacing");
            }
            if(rangeSpacing != null) {
                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_spacing,
                        Math.abs(rangeSpacing.readScalarDouble()));
            }

            // Azimuth spacing - search in frequency group tree, then processing parameters
            Variable azSpacing = findVariableInGroupTree(freqGroup, "sceneCenterAlongTrackSpacing");
            if(azSpacing == null) {
                azSpacing = findVariableInGroupTree(freqGroup, "yCoordinateSpacing");
            }
            if(azSpacing != null) {
                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_spacing,
                        Math.abs(azSpacing.readScalarDouble()));
            }

            // Line time interval (zeroDopplerTimeSpacing) - search multiple locations
            Variable timeSpacing = findZeroDopplerTimeSpacing(swathsOrGrids, freqGroup, groupProductType);
            if(timeSpacing != null) {
                double spacing = timeSpacing.readScalarDouble();
                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.line_time_interval, spacing);

                // If PRF not found, compute from zeroDopplerTimeSpacing
                if(prf == null && spacing > 0) {
                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.pulse_repetition_frequency,
                            1.0 / spacing);
                }
            }

            // Log critical SAR parameters for diagnostics
            SystemUtils.LOG.info("NISAR SAR params:" +
                    " slant_range_to_first_pixel=" + absRoot.getAttributeDouble(AbstractMetadata.slant_range_to_first_pixel) +
                    " range_spacing=" + absRoot.getAttributeDouble(AbstractMetadata.range_spacing) +
                    " azimuth_spacing=" + absRoot.getAttributeDouble(AbstractMetadata.azimuth_spacing) +
                    " line_time_interval=" + absRoot.getAttributeDouble(AbstractMetadata.line_time_interval) +
                    " radar_frequency=" + absRoot.getAttributeDouble(AbstractMetadata.radar_frequency) +
                    " prf=" + absRoot.getAttributeDouble(AbstractMetadata.pulse_repetition_frequency));

            // For interferometric products, try to get looks from processing parameters
            Group metadataGroup = groupProductType.findGroup("metadata");
            if(metadataGroup != null) {
                Group procInfo = metadataGroup.findGroup("processingInformation");
                if(procInfo != null) {
                    Group params = procInfo.findGroup("parameters");
                    if(params != null) {
                        // Try interferogram parameters first
                        Group ifgParams = params.findGroup("interferogram");
                        if(ifgParams != null) {
                            Group ifgFreq = ifgParams.findGroup("frequencyA");
                            if(ifgFreq == null) ifgFreq = ifgParams.findGroup("frequencyB");
                            if(ifgFreq != null) {
                                Variable azLooks = ifgFreq.findVariable("numberOfAzimuthLooks");
                                Variable rgLooks = ifgFreq.findVariable("numberOfRangeLooks");
                                if(azLooks != null) {
                                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_looks,
                                            azLooks.readScalarInt());
                                }
                                if(rgLooks != null) {
                                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_looks,
                                            rgLooks.readScalarInt());
                                }
                            }
                        }
                    }
                }
            }

            // Default looks for SLC products
            if(productType.equals("RSLC") || productType.equals("GSLC")) {
                if(absRoot.getAttributeDouble(AbstractMetadata.range_looks) == AbstractMetadata.NO_METADATA) {
                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_looks, 1);
                }
                if(absRoot.getAttributeDouble(AbstractMetadata.azimuth_looks) == AbstractMetadata.NO_METADATA) {
                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_looks, 1);
                }
            }

            // For non-SLC, try to get looks from the frequency group or interferogram subgroup
            if(absRoot.getAttributeDouble(AbstractMetadata.range_looks) == AbstractMetadata.NO_METADATA) {
                // For geocoded products, check the interferogram or pixelOffsets subgroups
                for(Group sub : freqGroup.getGroups()) {
                    Variable azLooks = sub.findVariable("numberOfAzimuthLooks");
                    Variable rgLooks = sub.findVariable("numberOfRangeLooks");
                    if(azLooks != null) {
                        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_looks,
                                azLooks.readScalarInt());
                    }
                    if(rgLooks != null) {
                        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_looks,
                                rgLooks.readScalarInt());
                    }
                    if(azLooks != null || rgLooks != null) break;
                }
            }

            // Final fallback for looks
            if(absRoot.getAttributeDouble(AbstractMetadata.range_looks) == AbstractMetadata.NO_METADATA) {
                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_looks, 1);
            }
            if(absRoot.getAttributeDouble(AbstractMetadata.azimuth_looks) == AbstractMetadata.NO_METADATA) {
                AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_looks, 1);
            }

        } catch (Exception e) {
            SystemUtils.LOG.warning("Error reading SAR parameters: " + e.getMessage());
        }
    }

    private static Variable findInProcessingParams(Group groupProductType, String varName) {
        Group metadata = groupProductType.findGroup("metadata");
        if(metadata == null) return null;
        Group procInfo = metadata.findGroup("processingInformation");
        if(procInfo == null) return null;
        Group params = procInfo.findGroup("parameters");
        if(params == null) return null;
        Group refGroup = params.findGroup("reference");
        if(refGroup == null) return null;
        Group refFreq = refGroup.findGroup("frequencyA");
        if(refFreq == null) refFreq = refGroup.findGroup("frequencyB");
        if(refFreq == null) return null;
        return refFreq.findVariable(varName);
    }

    private static Variable findVariableInGroupTree(Group group, String varName) {
        return findVariableInGroupTree(group, varName, 4);
    }

    private static Variable findVariableInGroupTree(Group group, String varName, int maxDepth) {
        Variable v = group.findVariable(varName);
        if(v != null) return v;
        if(maxDepth <= 0) return null;
        for(Group sub : group.getGroups()) {
            v = findVariableInGroupTree(sub, varName, maxDepth - 1);
            if(v != null) return v;
        }
        return null;
    }

    private Variable findZeroDopplerTimeSpacing(Group swathsOrGrids, Group freqGroup, Group groupProductType) {
        // 1. Direct child of swaths/grids (RSLC)
        Variable v = swathsOrGrids.findVariable("zeroDopplerTimeSpacing");
        if(v != null) return v;

        // 2. In frequency group (GSLC)
        v = freqGroup.findVariable("zeroDopplerTimeSpacing");
        if(v != null) return v;

        // 3. In sub-groups of frequency group (interferogram, pixelOffsets, etc.)
        for(Group sub : freqGroup.getGroups()) {
            v = sub.findVariable("zeroDopplerTimeSpacing");
            if(v != null) return v;
        }

        // 4. In metadata/processingInformation/parameters/reference/frequencyA (GOFF, GUNW)
        Group metadataGroup = groupProductType.findGroup("metadata");
        if(metadataGroup != null) {
            Group procInfo = metadataGroup.findGroup("processingInformation");
            if(procInfo != null) {
                Group params = procInfo.findGroup("parameters");
                if(params != null) {
                    // Try reference/frequencyA
                    Group refGroup = params.findGroup("reference");
                    if(refGroup != null) {
                        Group refFreq = refGroup.findGroup("frequencyA");
                        if(refFreq == null) refFreq = refGroup.findGroup("frequencyB");
                        if(refFreq != null) {
                            v = refFreq.findVariable("zeroDopplerTimeSpacing");
                            if(v != null) return v;
                        }
                    }
                }
            }
            // 5. In metadata/sourceData/swaths (GCOV)
            Group sourceData = metadataGroup.findGroup("sourceData");
            if(sourceData != null) {
                Group srcSwaths = sourceData.findGroup("swaths");
                if(srcSwaths != null) {
                    v = srcSwaths.findVariable("zeroDopplerTimeSpacing");
                    if(v != null) return v;
                }
            }
        }

        return null;
    }

    private Variable findSlantRangeVariable(Group swathsOrGrids, Group freqGroup, Group groupProductType) {
        // 1. Direct child of swaths/grids (RSLC, RIFG, ROFF, RUNW)
        Variable v = swathsOrGrids.findVariable("slantRange");
        if(v != null) return v;

        // 2. In frequency group
        v = freqGroup.findVariable("slantRange");
        if(v != null) return v;

        // 3. In sub-groups of frequency group (interferogram, pixelOffsets, etc.)
        for(Group sub : freqGroup.getGroups()) {
            v = sub.findVariable("slantRange");
            if(v != null) return v;
        }

        // 4. In metadata/sourceData/swaths (GCOV and other geocoded products)
        Group metadataGroup = groupProductType.findGroup("metadata");
        if(metadataGroup != null) {
            Group sourceData = metadataGroup.findGroup("sourceData");
            if(sourceData != null) {
                Group srcSwaths = sourceData.findGroup("swaths");
                if(srcSwaths != null) {
                    v = srcSwaths.findVariable("slantRange");
                    if(v != null) return v;
                }
            }
            // 5. In metadata/processingInformation/parameters/reference/frequencyA
            Group procInfo = metadataGroup.findGroup("processingInformation");
            if(procInfo != null) {
                Group params = procInfo.findGroup("parameters");
                if(params != null) {
                    Group refGroup = params.findGroup("reference");
                    if(refGroup != null) {
                        Group refFreq = refGroup.findGroup("frequencyA");
                        if(refFreq == null) refFreq = refGroup.findGroup("frequencyB");
                        if(refFreq != null) {
                            v = refFreq.findVariable("slantRange");
                            if(v != null) return v;
                        }
                    }
                }
            }
        }

        return null;
    }

    private TiePointGrid createTiePointGrid(final Variable var) throws IOException {
        final int rank = var.getRank();
        final int gridWidth = var.getDimension(rank - 1).getLength();
        int gridHeight = var.getDimension(rank - 2).getLength();
        if (rank >= 3 && gridHeight <= 1)
            gridHeight = var.getDimension(rank - 3).getLength();
        return NetCDFUtils.createTiePointGrid(var, gridWidth, gridHeight,
                product.getSceneRasterWidth(), product.getSceneRasterHeight());
    }

    private static class Grids {
        TiePointGrid latGrid;
        TiePointGrid lonGrid;
    }

    private Grids createTiePointGridFromUTM(final int epsg, final Variable varX, final Variable varY) throws IOException {
        final int rank = varX.getRank();
        final int gridWidth = varX.getDimension(rank - 1).getLength();
        int gridHeight = varX.getDimension(rank - 2).getLength();
        if (rank >= 3 && gridHeight <= 1)
            gridHeight = varX.getDimension(rank - 3).getLength();
        final int sceneWidth = product.getSceneRasterWidth();
        final int sceneHeight = product.getSceneRasterHeight();
        final double subSamplingX = (double) sceneWidth / (double) (gridWidth - 1);
        final double subSamplingY = (double) sceneHeight / (double) (gridHeight - 1);

        TiePointGrid eastingTPG = NetCDFUtils.createTiePointGrid(varX, gridWidth, gridHeight, sceneWidth, sceneHeight);
        TiePointGrid northingTPG = NetCDFUtils.createTiePointGrid(varY, gridWidth, gridHeight, sceneWidth, sceneHeight);

        final int length = gridWidth * gridHeight;
        float[] easting = eastingTPG.getTiePoints();
        float[] northing = northingTPG.getTiePoints();
        final float[] latTiePoints = new float[length];
        final float[] lonTiePoints = new float[length];

        String epsgName = EPSGCodes.getInstance().getName(epsg);
        if(epsgName == null || !epsgName.contains("UTM")) {
            SystemUtils.LOG.warning("EPSG " + epsg + " is not a recognized UTM projection, skipping geocoding");
            return null;
        }
        String zone = epsgName.substring(epsgName.lastIndexOf("_") + 1);
        String row = "N";
        if (zone.endsWith("S")) {
            row = "A";  // southern rows
        }
        zone = zone.substring(0, zone.length() - 1);

        UTM2LatLon conv = new UTM2LatLon();
        for (int i = 0; i < length; ++i) {
            final String utmStr = zone + " " + row + " " + easting[i] + " " + northing[i];
            final double latlon[] = conv.convertUTMToLatLong(utmStr);
            latTiePoints[i] = (float) latlon[0];
            lonTiePoints[i] = (float) latlon[1];
        }

        Grids grids = new Grids();
        grids.latGrid = new TiePointGrid("latitude", gridWidth, gridHeight, 0.5f, 0.5f,
                subSamplingX, subSamplingY, latTiePoints);
        grids.latGrid.setUnit(Unit.DEGREES);

        grids.lonGrid = new TiePointGrid("longitude", gridWidth, gridHeight, 0.5f, 0.5f,
                subSamplingX, subSamplingY, lonTiePoints, TiePointGrid.DISCONT_AT_180);
        grids.lonGrid.setUnit(Unit.DEGREES);

        return grids;
    }

    protected void addTiePointGridsToProduct() throws IOException {

        try {
            Group metadataGroup = netcdfFile.findGroup("/science/LSAR/" + productType + "/metadata");
            if (metadataGroup == null) {
                metadataGroup = netcdfFile.findGroup("/science/SSAR/" + productType + "/metadata");
            }
            if(metadataGroup == null) return;

            Group gridGroup = findGridGroup(metadataGroup);
            if(gridGroup == null) return;

            Variable incidenceAngleVar = gridGroup.findVariable("incidenceAngle");
            if (incidenceAngleVar != null) {
                TiePointGrid incidenceAngleGrid = createTiePointGrid(incidenceAngleVar);
                incidenceAngleGrid.setName(OperatorUtils.TPG_INCIDENT_ANGLE);
                product.addTiePointGrid(incidenceAngleGrid);
            }

            Variable coordYVar = findVariable(gridGroup, "coordinateY", "yCoordinates");
            Variable coordXVar = findVariable(gridGroup, "coordinateX", "xCoordinates");
            if (coordYVar != null && coordXVar != null) {
                Attribute unitsAttr = coordYVar.findAttribute("units");
                String unit = unitsAttr != null ? unitsAttr.getStringValue() : "";

                TiePointGrid latGrid, lonGrid;
                if (unit.contains("meter") || unit.equals("m")) {
                    // Projected coordinates - find EPSG code
                    int epsg = 0;
                    Variable epsgVar = gridGroup.findVariable("epsg");
                    if (epsgVar != null) {
                        epsg = epsgVar.readScalarInt();
                    } else {
                        Variable projection = gridGroup.findVariable("projection");
                        if(projection != null) {
                            // projection may be a scalar integer (EPSG code) or have an epsg_code attribute
                            try {
                                epsg = projection.readScalarInt();
                            } catch (Exception e) {
                                Attribute epsgAttr = projection.findAttribute("epsg_code");
                                if(epsgAttr != null) {
                                    epsg = epsgAttr.getNumericValue().intValue();
                                }
                            }
                        }
                    }

                    if(epsg == 0) {
                        SystemUtils.LOG.warning("No EPSG code found for projected coordinates");
                        return;
                    }

                    Grids grids;
                    if(coordXVar.getRank() == 1 && coordYVar.getRank() == 1) {
                        grids = createTiePointGridFrom1DProjected(epsg, coordXVar, coordYVar);
                    } else {
                        grids = createTiePointGridFromUTM(epsg, coordXVar, coordYVar);
                    }
                    if(grids == null) return;
                    latGrid = grids.latGrid;
                    lonGrid = grids.lonGrid;
                } else {
                    if(coordXVar.getRank() == 1 && coordYVar.getRank() == 1) {
                        // 1D lat/lon arrays - create 2D mesh
                        Grids grids = createTiePointGridFrom1DGeographic(coordXVar, coordYVar);
                        latGrid = grids.latGrid;
                        lonGrid = grids.lonGrid;
                    } else {
                        latGrid = createTiePointGrid(coordYVar);
                        latGrid.setName(OperatorUtils.TPG_LATITUDE);

                        lonGrid = createTiePointGrid(coordXVar);
                        lonGrid.setName(OperatorUtils.TPG_LONGITUDE);
                    }
                }

                product.addTiePointGrid(latGrid);
                product.addTiePointGrid(lonGrid);

                final TiePointGeoCoding tpGeoCoding = new TiePointGeoCoding(latGrid, lonGrid);
                product.setSceneGeoCoding(tpGeoCoding);
            }
        } catch (Exception e) {
            SystemUtils.LOG.warning("Error creating tie-point grids: " + e.getMessage());
        }
    }

    private Grids createTiePointGridFrom1DProjected(final int epsg, final Variable varX, final Variable varY) throws IOException {
        float[] xValues = (float[]) varX.read().get1DJavaArray(DataType.FLOAT);
        float[] yValues = (float[]) varY.read().get1DJavaArray(DataType.FLOAT);
        final int gridWidth = xValues.length;
        final int gridHeight = yValues.length;
        final int sceneWidth = product.getSceneRasterWidth();
        final int sceneHeight = product.getSceneRasterHeight();
        final double subSamplingX = (double) sceneWidth / (double) (gridWidth - 1);
        final double subSamplingY = (double) sceneHeight / (double) (gridHeight - 1);

        final int length = gridWidth * gridHeight;
        final float[] latTiePoints = new float[length];
        final float[] lonTiePoints = new float[length];

        String epsgName = EPSGCodes.getInstance().getName(epsg);
        if(epsgName != null && epsgName.contains("UTM")) {
            // UTM projection - use UTM2LatLon converter
            String zone = epsgName.substring(epsgName.lastIndexOf("_") + 1);
            String row = "N";
            if (zone.endsWith("S")) {
                row = "A";
            }
            zone = zone.substring(0, zone.length() - 1);

            UTM2LatLon conv = new UTM2LatLon();
            for (int j = 0; j < gridHeight; ++j) {
                for (int i = 0; i < gridWidth; ++i) {
                    final String utmStr = zone + " " + row + " " + xValues[i] + " " + yValues[j];
                    final double[] latlon = conv.convertUTMToLatLong(utmStr);
                    latTiePoints[j * gridWidth + i] = (float) latlon[0];
                    lonTiePoints[j * gridWidth + i] = (float) latlon[1];
                }
            }
        } else {
            // For non-UTM projections, log warning and skip geocoding
            SystemUtils.LOG.warning("EPSG " + epsg + " is not a UTM projection, geocoding may be inaccurate");
            return null;
        }

        Grids grids = new Grids();
        grids.latGrid = new TiePointGrid("latitude", gridWidth, gridHeight, 0.5f, 0.5f,
                subSamplingX, subSamplingY, latTiePoints);
        grids.latGrid.setUnit(Unit.DEGREES);

        grids.lonGrid = new TiePointGrid("longitude", gridWidth, gridHeight, 0.5f, 0.5f,
                subSamplingX, subSamplingY, lonTiePoints, TiePointGrid.DISCONT_AT_180);
        grids.lonGrid.setUnit(Unit.DEGREES);

        return grids;
    }

    private Grids createTiePointGridFrom1DGeographic(final Variable varX, final Variable varY) throws IOException {
        float[] lonValues = (float[]) varX.read().get1DJavaArray(DataType.FLOAT);
        float[] latValues = (float[]) varY.read().get1DJavaArray(DataType.FLOAT);
        final int gridWidth = lonValues.length;
        final int gridHeight = latValues.length;
        final int sceneWidth = product.getSceneRasterWidth();
        final int sceneHeight = product.getSceneRasterHeight();
        final double subSamplingX = (double) sceneWidth / (double) (gridWidth - 1);
        final double subSamplingY = (double) sceneHeight / (double) (gridHeight - 1);

        final int length = gridWidth * gridHeight;
        final float[] latTiePoints = new float[length];
        final float[] lonTiePoints = new float[length];

        for (int j = 0; j < gridHeight; ++j) {
            for (int i = 0; i < gridWidth; ++i) {
                latTiePoints[j * gridWidth + i] = latValues[j];
                lonTiePoints[j * gridWidth + i] = lonValues[i];
            }
        }

        Grids grids = new Grids();
        grids.latGrid = new TiePointGrid("latitude", gridWidth, gridHeight, 0.5f, 0.5f,
                subSamplingX, subSamplingY, latTiePoints);
        grids.latGrid.setUnit(Unit.DEGREES);

        grids.lonGrid = new TiePointGrid("longitude", gridWidth, gridHeight, 0.5f, 0.5f,
                subSamplingX, subSamplingY, lonTiePoints, TiePointGrid.DISCONT_AT_180);
        grids.lonGrid.setUnit(Unit.DEGREES);

        return grids;
    }

    private ProductData.UTC getTimeFromElement(MetadataElement identification,
                                               String primaryName, String fallbackName) {
        MetadataElement elem = identification.getElement(primaryName);
        if(elem == null && fallbackName != null) {
            elem = identification.getElement(fallbackName);
            primaryName = fallbackName;
        }
        if(elem == null) return null;
        return parseNisarTime(elem, primaryName);
    }

    private ProductData.UTC parseNisarTime(MetadataElement elem, String attrName) {
        if(elem == null) return null;
        try {
            String timeStr = elem.getAttributeString(attrName, "");
            if(timeStr.isEmpty()) {
                // Try first available attribute
                String[] names = elem.getAttributeNames();
                if(names.length > 0) {
                    timeStr = elem.getAttributeString(names[0], "");
                }
            }
            if(timeStr.isEmpty()) return null;

            return parseNisarTimeString(timeStr);
        } catch (Exception e) {
            SystemUtils.LOG.warning("Failed to parse time from " + attrName + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Parses a NISAR time string preserving fractional seconds.
     * Input format: "2008-10-12T06:09:11.487618" or "2008-10-12 06:09:11.487618"
     * Fractional seconds are critical for orbit interpolation accuracy.
     */
    private ProductData.UTC parseNisarTimeString(String timeStr) throws java.text.ParseException {
        timeStr = timeStr.replace("T", " ").trim();

        // Split at decimal point to preserve fractional seconds
        double fracSeconds = 0.0;
        String mainPart = timeStr;
        final int dotPos = timeStr.indexOf('.');
        if(dotPos > 0) {
            mainPart = timeStr.substring(0, dotPos);
            fracSeconds = Double.parseDouble("0" + timeStr.substring(dotPos));
        }

        ProductData.UTC utc = ProductData.UTC.parse(mainPart, standardDateFormat);
        if(fracSeconds > 0.0) {
            // Add fractional seconds as fraction of a day to the MJD
            utc = new ProductData.UTC(utc.getMJD() + fracSeconds / 86400.0);
        }
        return utc;
    }

    private static String getOrbitPass(String pass) {
        return pass.toUpperCase().contains("ASC") ? "ASCENDING" : "DESCENDING";
    }

    protected Band createBand(final String bandName, final int width, final int height, final String unit, final Variable var) {
        final Band band = new Band(bandName, ProductData.TYPE_FLOAT32, width, height);
        band.setDescription(var.getDescription());
        band.setUnit(unit);
        band.setNoDataValue(0);
        band.setNoDataValueUsed(true);

        setStxFromAttributes(band, var);
        if (!band.isStxSet()) {
            band.setStx(new StxFactory().withMinimum(0).withMaximum(1)
                    .withIntHistogram(false).withHistogramBins(new int[512]).create());
        }
        product.addBand(band);
        bandMap.put(band, var);

        return band;
    }

    protected Band createBand(Group polGroup, String variableName, String bandName, String bandUnit, float nodatavalue) {
        final Variable var = polGroup.findVariable(variableName);
        if (var != null) {
            int rasterHeight = var.getDimension(0).getLength();
            int rasterWidth = var.getDimension(1).getLength();
            Band band = createBand(bandName, rasterWidth, rasterHeight, bandUnit, var);
            band.setNoDataValue(nodatavalue);
            band.setNoDataValueUsed(true);
            return band;
        }
        return null;
    }

    protected static void setStxFromAttributes(final Band band, final Variable var) {
        try {
            Attribute minAt = var.findAttribute("min_value");
            Attribute maxAt = var.findAttribute("max_value");
            if (minAt == null) minAt = var.findAttribute("min_real_value");
            if (maxAt == null) maxAt = var.findAttribute("max_real_value");
            if (minAt != null && maxAt != null) {
                double min = minAt.getNumericValue().doubleValue();
                double max = maxAt.getNumericValue().doubleValue();
                band.setStx(new StxFactory().withMinimum(min).withMaximum(max)
                        .withIntHistogram(false).withHistogramBins(new int[512]).create());
                return;
            }

            // Fallback: try mean +/- 3*stddev
            Attribute meanAt = var.findAttribute("mean_value");
            if (meanAt == null) meanAt = var.findAttribute("mean_real_value");
            Attribute stdAt = var.findAttribute("sample_stddev");
            if (stdAt == null) stdAt = var.findAttribute("sample_stddev_real");
            if (meanAt != null && stdAt != null) {
                double mean = meanAt.getNumericValue().doubleValue();
                double std = stdAt.getNumericValue().doubleValue();
                band.setStx(new StxFactory().withMinimum(mean - 3 * std).withMaximum(mean + 3 * std)
                        .withIntHistogram(false).withHistogramBins(new int[512]).create());
            }
        } catch (Exception e) {
            // ignore
        }
    }

    private Variable findVariable(Group group, String... names) {
        for (String name : names) {
            Variable var = group.findVariable(name);
            if (var != null) {
                return var;
            }
        }
        return null;
    }

    private Group findGridGroup(final Group group) {
        Group gridGroup = group.findGroup("geolocationGrid");
        if (gridGroup == null) {
            gridGroup = group.findGroup("radarGrid");
        }
        return gridGroup;
    }

    protected static String getPolarization(final Product product, NetcdfFile netcdfFile) {

        final MetadataElement globalElem = AbstractMetadata.getOriginalProductMetadata(product).getElement(
                NetcdfConstants.GLOBAL_ATTRIBUTES_NAME);

        try {
            if (globalElem != null) {
                final String polStr = netcdfFile.getRootGroup().findVariable(NisarXConstants.MDS1_TX_RX_POLAR).
                        readScalarString();

                if (!polStr.isEmpty())
                    return polStr;
            }
        } catch (IOException e) {
            SystemUtils.LOG.severe(e.getMessage());

        }
        return null;
    }

    protected String getSampleType() {

        try {
            if (NisarXConstants.SLC.equalsIgnoreCase(netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.SPH_DESCRIPTOR).readScalarString())) {

                isComplex = true;
                return NisarXConstants.COMPLEX;
            }
        } catch (IOException e) {
            SystemUtils.LOG.severe(e.getMessage());
        }
        isComplex = false;
        return NisarXConstants.DETECTED;
    }

    protected void addOrbitStateVectors(final MetadataElement absRoot) {

        try {
            // Find orbit group: science/[LSAR|SSAR]/[productType]/metadata/orbit
            String sarBand = netcdfFile.findGroup("/science/LSAR") != null ? "LSAR" : "SSAR";
            String orbitPath = "/science/" + sarBand + "/" + productType + "/metadata/orbit";
            Group orbitGroup = netcdfFile.findGroup(orbitPath);
            if(orbitGroup == null) {
                SystemUtils.LOG.warning("NISAR orbit group not found at " + orbitPath);
                return;
            }

            // For interferometric products, orbit data may be under reference/ subgroup
            Group dataGroup = orbitGroup.findGroup("reference");
            if(dataGroup == null) {
                dataGroup = orbitGroup;
            }

            Variable posVar = dataGroup.findVariable("position");
            Variable velVar = dataGroup.findVariable("velocity");
            Variable timeVar = dataGroup.findVariable("time");
            if(posVar == null || velVar == null || timeVar == null) {
                SystemUtils.LOG.warning("NISAR orbit: missing position/velocity/time variables in " + dataGroup.getFullName());
                return;
            }

            // Parse epoch from time variable units attribute (e.g. "seconds since 2008-10-12T00:00:00.00000000")
            Attribute unitsAttr = timeVar.findAttribute("units");
            if(unitsAttr == null) {
                SystemUtils.LOG.warning("NISAR orbit: time variable has no 'units' attribute");
                return;
            }
            String unitsStr = unitsAttr.getStringValue();
            String epochStr = unitsStr.replace("seconds since ", "").trim();
            final ProductData.UTC epoch = parseNisarTimeString(epochStr);
            final double epochMJD = epoch.getMJD();

            // Read orbit arrays — handle both float and double storage
            final Array timeArray = timeVar.read();
            final Array posArray = posVar.read();
            final Array velArray = velVar.read();

            final int[] posShape = posArray.getShape();
            final int[] velShape = velArray.getShape();
            final int numPoints = (int) timeArray.getSize();

            SystemUtils.LOG.info("NISAR orbit: " + numPoints + " points, position shape=" +
                    java.util.Arrays.toString(posShape) + ", velocity shape=" + java.util.Arrays.toString(velShape));

            // Determine array layout: [N,3] or [3,N]
            final boolean transposed = posShape.length == 2 && posShape[0] == 3 && posShape[1] != 3;

            final MetadataElement orbitVectorListElem = absRoot.getElement(AbstractMetadata.orbit_state_vectors);

            for (int i = 0; i < numPoints; i++) {
                double timeSec = timeArray.getDouble(i);
                ProductData.UTC vectorUTC = new ProductData.UTC(epochMJD + timeSec / 86400.0);

                double xPos, yPos, zPos, xVel, yVel, zVel;
                if (transposed) {
                    // Shape [3, N]: row 0=x, row 1=y, row 2=z
                    int idx0 = 0 * posShape[1] + i;
                    int idx1 = 1 * posShape[1] + i;
                    int idx2 = 2 * posShape[1] + i;
                    xPos = posArray.getDouble(idx0);
                    yPos = posArray.getDouble(idx1);
                    zPos = posArray.getDouble(idx2);
                    xVel = velArray.getDouble(idx0);
                    yVel = velArray.getDouble(idx1);
                    zVel = velArray.getDouble(idx2);
                } else {
                    // Shape [N, 3]: row i = [x, y, z]
                    int idx0 = i * 3;
                    xPos = posArray.getDouble(idx0);
                    yPos = posArray.getDouble(idx0 + 1);
                    zPos = posArray.getDouble(idx0 + 2);
                    xVel = velArray.getDouble(idx0);
                    yVel = velArray.getDouble(idx0 + 1);
                    zVel = velArray.getDouble(idx0 + 2);
                }

                if (i == 0) {
                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.STATE_VECTOR_TIME, vectorUTC);
                    SystemUtils.LOG.info("NISAR orbit[0]: time=" + vectorUTC.format() +
                            " pos=[" + xPos + "," + yPos + "," + zPos + "]" +
                            " vel=[" + xVel + "," + yVel + "," + zVel + "]");
                }

                final MetadataElement orbitVectorElem = new MetadataElement(AbstractMetadata.orbit_vector + (i + 1));
                orbitVectorElem.setAttributeUTC(AbstractMetadata.orbit_vector_time, vectorUTC);

                orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_x_pos, xPos);
                orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_y_pos, yPos);
                orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_z_pos, zPos);
                orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_x_vel, xVel);
                orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_y_vel, yVel);
                orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_z_vel, zVel);

                orbitVectorListElem.addElement(orbitVectorElem);
            }

            ProductData.UTC lastVectorUTC = new ProductData.UTC(epochMJD + timeArray.getDouble(numPoints - 1) / 86400.0);
            double spacingSec = numPoints > 1 ? (timeArray.getDouble(1) - timeArray.getDouble(0)) : 0;
            ProductData.UTC firstLineTime = absRoot.getAttributeUTC(AbstractMetadata.first_line_time);
            ProductData.UTC lastLineTime = absRoot.getAttributeUTC(AbstractMetadata.last_line_time);

            SystemUtils.LOG.info("NISAR orbit: " + numPoints + " vectors, spacing=" + spacingSec + "s" +
                    ", orbit_range=[" + new ProductData.UTC(epochMJD + timeArray.getDouble(0) / 86400.0).format() +
                    " → " + lastVectorUTC.format() + "]" +
                    ", acquisition=[" + firstLineTime.format() + " → " + lastLineTime.format() + "]");

            // Warn if orbit doesn't cover acquisition
            if (firstLineTime.getMJD() < (epochMJD + timeArray.getDouble(0) / 86400.0) ||
                    lastLineTime.getMJD() > (epochMJD + timeArray.getDouble(numPoints - 1) / 86400.0)) {
                SystemUtils.LOG.warning("NISAR orbit state vectors do NOT span the acquisition time range!");
            }

        } catch (Exception e) {
            SystemUtils.LOG.severe("Error reading NISAR orbit state vectors: " + e.getMessage());
            e.printStackTrace();
        }
    }

    protected void addDopplerMetadata() {
        try {
            final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);

            final MetadataElement dopplerCentroidCoefficientsElem = absRoot.getElement(AbstractMetadata.dop_coefficients);
            final MetadataElement dopplerListElem = new MetadataElement(AbstractMetadata.dop_coef_list + ".1");
            dopplerCentroidCoefficientsElem.addElement(dopplerListElem);

            final ProductData.UTC utcTime = absRoot.getAttributeUTC(AbstractMetadata.first_line_time,
                    AbstractMetadata.NO_METADATA_UTC);

            dopplerListElem.setAttributeUTC(AbstractMetadata.dop_coef_time, utcTime);

            AbstractMetadata.addAbstractedAttribute(dopplerListElem, AbstractMetadata.slant_range_time,
                    ProductData.TYPE_FLOAT64, "ns", "Slant Range Time");
            AbstractMetadata.setAttribute(dopplerListElem, AbstractMetadata.slant_range_time, 0.0);

            // Try to read Doppler centroid values from the HDF5 processing parameters
            String sarBand = netcdfFile.findGroup("/science/LSAR") != null ? "LSAR" : "SSAR";
            String paramPath = "/science/" + sarBand + "/" + productType + "/metadata/processingInformation/parameters";
            Group paramsGroup = netcdfFile.findGroup(paramPath);

            boolean coeffsAdded = false;
            if (paramsGroup != null) {
                // Try frequencyA or frequencyB under parameters
                Group freqParams = paramsGroup.findGroup("frequencyA");
                if (freqParams == null) freqParams = paramsGroup.findGroup("frequencyB");

                if (freqParams != null) {
                    Variable dopCentroid = freqParams.findVariable("dopplerCentroid");
                    if (dopCentroid != null) {
                        // dopplerCentroid is (time, range) - use first row as coefficients
                        double[] allValues = (double[]) dopCentroid.read().getStorage();
                        int numRange = dopCentroid.getDimension(1).getLength();
                        for (int i = 0; i < numRange; i++) {
                            final MetadataElement coefElem = new MetadataElement(AbstractMetadata.coefficient + '.' + (i + 1));
                            dopplerListElem.addElement(coefElem);
                            AbstractMetadata.addAbstractedAttribute(coefElem, AbstractMetadata.dop_coef,
                                    ProductData.TYPE_FLOAT64, "", "Doppler Centroid Coefficient");
                            AbstractMetadata.setAttribute(coefElem, AbstractMetadata.dop_coef, allValues[i]);
                        }
                        coeffsAdded = true;
                    }
                }
            }

            // If no coefficients found, add a default zero coefficient
            if (!coeffsAdded) {
                final MetadataElement coefElem = new MetadataElement(AbstractMetadata.coefficient + ".1");
                dopplerListElem.addElement(coefElem);
                AbstractMetadata.addAbstractedAttribute(coefElem, AbstractMetadata.dop_coef,
                        ProductData.TYPE_FLOAT64, "", "Doppler Centroid Coefficient");
                AbstractMetadata.setAttribute(coefElem, AbstractMetadata.dop_coef, 0.0);
            }
        } catch (Exception e) {
            SystemUtils.LOG.warning("Error reading Doppler metadata: " + e.getMessage());
        }
    }

    /**
     * {@inheritDoc}
     */
    public void readBandRasterDataImpl(int sourceOffsetX, int sourceOffsetY, int sourceWidth, int sourceHeight,
                                       int sourceStepX, int sourceStepY, Band destBand, int destOffsetX,
                                       int destOffsetY, int destWidth, int destHeight, ProductData destBuffer,
                                       ProgressMonitor pm) throws IOException {
//        StopWatch stopWatch = new StopWatch();
//        stopWatch.start();
//        System.out.println("Requesting " +
//            " sourceOffsetX=" + sourceOffsetX + " sourceOffsetY=" + sourceOffsetY +
//            " sourceWidth=" + sourceWidth + " sourceHeight=" + sourceHeight +
//            " sourceStepX=" + sourceStepX + " sourceStepY=" + sourceStepY +
//            " destBand=" + destBand.getName() +
//            " destOffsetX=" + destOffsetX + " destOffsetY=" + destOffsetY +
//            " destWidth=" + destWidth + " destHeight=" + destHeight);

        final int sceneHeight = destBand.getRasterHeight();
        final int sceneWidth = destBand.getRasterWidth();

        final Variable variable = bandMap.get(destBand);

        // Clamp dimensions
        int readHeight = Math.min(sourceHeight, sceneHeight - sourceOffsetY);
        int readWidth = Math.min(sourceWidth, sceneWidth - sourceOffsetX);

        if (readHeight <= 0 || readWidth <= 0) {
            return;
        }

        pm.beginTask("Reading band " + destBand.getName(), 1);
        try {
            if (variable.getDataType() == STRUCTURE) {
                // HDF5 compound type (complex64 = {float r; float i}):
                // NetCDF4-Java may report rank=0 for compound Structure variables even when the
                // underlying dataset is 2D, so ranged reads fail. Instead: read the full variable
                // once (cached), extract the I or Q member float array (cached per band), then
                // copy the requested tile from the in-memory array.
                readComplexBandTile(variable, destBand, sceneWidth, sceneHeight,
                        sourceOffsetX, sourceOffsetY, sourceStepX, sourceStepY,
                        destWidth, destHeight, destBuffer);
            } else {
                final List<Range> tileRanges = new ArrayList<>();
                tileRanges.add(new Range(sourceOffsetY, sourceOffsetY + readHeight - 1, sourceStepY));
                tileRanges.add(new Range(sourceOffsetX, sourceOffsetX + readWidth - 1, sourceStepX));

                final Array array;
                try {
                    synchronized (netcdfFile) {
                        array = variable.read(tileRanges);
                    }
                } catch (InvalidRangeException e) {
                    throw new IOException(e);
                }

                final float[] tempArray = (float[]) array.get1DJavaArray(DataType.FLOAT);
                final int actualHeight = array.getShape()[0];
                final int actualWidth = array.getShape()[1];

                if (actualWidth == destWidth) {
                    System.arraycopy(tempArray, 0, destBuffer.getElems(), 0, tempArray.length);
                } else {
                    for (int y = 0; y < actualHeight; y++) {
                        if (y >= destHeight) break;
                        System.arraycopy(tempArray, y * actualWidth, destBuffer.getElems(), y * destWidth,
                                Math.min(actualWidth, destWidth));
                    }
                }
            }
            pm.worked(1);
        } catch (Exception e) {
            SystemUtils.LOG.severe(e.getMessage());
            throw new IOException(e);
        } finally {
            pm.done();
        }

//        stopWatch.stop();
//        System.out.println("readBandRasterDataImpl sourceOffsetY="+sourceOffsetY+" in " + stopWatch.getTimeDiffString());
    }

    /**
     * Returns the cache key for a given variable and strip index.
     */
    private static long stripCacheKey(Variable variable, int stripIndex) {
        return ((long) System.identityHashCode(variable) << 32) | (stripIndex & 0xFFFFFFFFL);
    }

    /**
     * Returns the cached StripData for the given row, reading from disk if needed.
     * Uses a multi-entry LRU cache so concurrent tile requests from different
     * Y positions don't thrash a single-entry cache.
     */
    private StripData getStrip(Variable variable, int sceneWidth, int sceneHeight,
                               int sourceOffsetY) throws IOException {

        final int stripIndex = sourceOffsetY / STRIP_HEIGHT;
        final long key = stripCacheKey(variable, stripIndex);

        // Fast check: is this strip already cached?
        synchronized (stripCache) {
            StripData cached = stripCache.get(key);
            if (cached != null) return cached;
        }

        // Cache miss — read the strip from disk
        final int stripStart = stripIndex * STRIP_HEIGHT;
        final int stripEnd = Math.min(stripStart + STRIP_HEIGHT, sceneHeight);
        if (stripEnd <= stripStart) return null;

        final int stripRows = stripEnd - stripStart;
        final int numPixels = stripRows * sceneWidth;

        try {
            final ArrayStructure structArray;

            if (variable.getRank() >= 2) {
                final List<Range> stripRanges = new ArrayList<>();
                stripRanges.add(new Range(stripStart, stripEnd - 1));
                stripRanges.add(new Range(0, sceneWidth - 1));

                synchronized (netcdfFile) {
                    structArray = (ArrayStructure) variable.read(stripRanges);
                }
            } else if (variable instanceof ucar.nc2.Structure) {
                final ucar.nc2.Structure struct = (ucar.nc2.Structure) variable;
                final int linearStart = stripStart * sceneWidth;

                synchronized (netcdfFile) {
                    structArray = struct.readStructure(linearStart, numPixels);
                }
            } else {
                return null;
            }

            final float[] realArr = new float[numPixels];
            final float[] imagArr = new float[numPixels];

            if (structArray instanceof ArrayStructureBB) {
                final ArrayStructureBB bbArray = (ArrayStructureBB) structArray;
                final ByteBuffer bb = bbArray.getByteBuffer();
                bb.order(ByteOrder.nativeOrder());

                final StructureMembers members = bbArray.getStructureMembers();
                final int elementSize = members.getStructureSize();

                int realOffset = 0, imagOffset = 4;
                for (StructureMembers.Member m : members.getMembers()) {
                    final String n = m.getName().toLowerCase();
                    if (n.equals("r") || n.equals("real") || n.endsWith("_r")) {
                        realOffset = m.getDataParam();
                    } else if (n.equals("i") || n.equals("imag") || n.equals("imaginary") || n.endsWith("_i")) {
                        imagOffset = m.getDataParam();
                    }
                }

                for (int idx = 0; idx < numPixels; idx++) {
                    final int baseOffset = idx * elementSize;
                    realArr[idx] = bb.getFloat(baseOffset + realOffset);
                    imagArr[idx] = bb.getFloat(baseOffset + imagOffset);
                }
            } else {
                final StructureMembers members = structArray.getStructureMembers();
                StructureMembers.Member realMember = null, imagMember = null;
                for (StructureMembers.Member m : members.getMembers()) {
                    final String n = m.getName().toLowerCase();
                    if (n.equals("r") || n.equals("real") || n.endsWith("_r")) {
                        realMember = m;
                    } else if (n.equals("i") || n.equals("imag") || n.equals("imaginary") || n.endsWith("_i")) {
                        imagMember = m;
                    }
                }
                for (int idx = 0; idx < numPixels; idx++) {
                    if (realMember != null) realArr[idx] = structArray.getScalarFloat(idx, realMember);
                    if (imagMember != null) imagArr[idx] = structArray.getScalarFloat(idx, imagMember);
                }
            }

            final StripData strip = new StripData(realArr, imagArr, stripStart, stripEnd, sceneWidth);

            synchronized (stripCache) {
                stripCache.put(key, strip);
            }

            return strip;

        } catch (InvalidRangeException e) {
            throw new IOException(e);
        }
    }

    private void readComplexBandTile(Variable variable, Band destBand, int sceneWidth, int sceneHeight,
                                     int sourceOffsetX, int sourceOffsetY, int sourceStepX, int sourceStepY,
                                     int destWidth, int destHeight, ProductData destBuffer) throws IOException {

        final boolean wantImag = Unit.IMAGINARY.equals(destBand.getUnit()) || destBand.getName().startsWith("q_");

        final StripData strip = getStrip(variable, sceneWidth, sceneHeight, sourceOffsetY);
        if (strip == null) return;

        final float[] stripData = wantImag ? strip.imag : strip.real;
        final float[] dest = (float[]) destBuffer.getElems();
        final int stripW = strip.width;

        if (sourceStepX == 1) {
            for (int tileY = 0; tileY < destHeight; tileY++) {
                final int srcRow = sourceOffsetY + tileY * sourceStepY;
                if (srcRow < strip.startRow || srcRow >= strip.endRow) break;
                final int stripOffset = (srcRow - strip.startRow) * stripW + sourceOffsetX;
                System.arraycopy(stripData, stripOffset, dest, tileY * destWidth, destWidth);
            }
        } else {
            int destIdx = 0;
            for (int tileY = 0; tileY < destHeight; tileY++) {
                final int srcRow = sourceOffsetY + tileY * sourceStepY;
                if (srcRow < strip.startRow || srcRow >= strip.endRow) break;
                final int stripRowOffset = (srcRow - strip.startRow) * stripW;
                for (int tileX = 0; tileX < destWidth; tileX++) {
                    dest[destIdx++] = stripData[stripRowOffset + sourceOffsetX + tileX * sourceStepX];
                }
            }
        }
    }
}