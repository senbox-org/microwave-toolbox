/*
 * Copyright (C) 2025 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.io.ceos.alos4;

import eu.esa.sar.io.binary.BinaryDBReader;
import eu.esa.sar.io.binary.BinaryFileReader;
import eu.esa.sar.io.binary.BinaryRecord;
import eu.esa.sar.io.ceos.CeosRecordHeader;
import eu.esa.sar.io.ceos.alos.AlosPalsarConstants;
import eu.esa.sar.io.ceos.alos.AlosPalsarLeaderFile;
import org.esa.snap.core.util.SystemUtils;
import org.jdom2.Document;

import javax.imageio.stream.ImageInputStream;
import java.io.IOException;

/**
 * Leader file for ALOS-4 PALSAR-3 products.
 * Uses the same CEOS record structure as ALOS-2 with ALOS-4 specific definitions.
 */
public class Alos4LeaderFile extends AlosPalsarLeaderFile {

    private static final String mission = "alos4";
    private static final String leader_recordDefinitionFile = "leader_file.xml";
    private static final String facility_record1_5DefinitionFile = "facility_record1_5.xml";

    private static final Document leaderXML = BinaryDBReader.loadDefinitionFile(mission, leader_recordDefinitionFile);
    private static final Document sceneXML = BinaryDBReader.loadDefinitionFile(mission, scene_recordDefinitionFile);
    private static final Document mapProjXML = BinaryDBReader.loadDefinitionFile(mission, mapproj_recordDefinitionFile);
    private static final Document platformXML = BinaryDBReader.loadDefinitionFile(mission, platformPosition_recordDefinitionFile);
    private static final Document attitudeXML = BinaryDBReader.loadDefinitionFile(mission, attitude_recordDefinitionFile);
    private static final Document radiometricXML = BinaryDBReader.loadDefinitionFile(mission, radiometric_recordDefinitionFile);
    private static final Document dataQualityXML = BinaryDBReader.loadDefinitionFile(mission, dataQuality_recordDefinitionFile);
    private static final Document facilityXML = BinaryDBReader.loadDefinitionFile(mission, facility_recordDefinitionFile);
    private static final Document facility1_5XML = BinaryDBReader.loadDefinitionFile(mission, facility_record1_5DefinitionFile);

    public Alos4LeaderFile(final ImageInputStream stream) throws IOException {
        super(stream, leaderXML, sceneXML, mapProjXML, platformXML, attitudeXML, radiometricXML, dataQualityXML);
    }

    @Override
    protected void readFacilityRelatedRecords(final BinaryFileReader reader) {
        for (int i = 0; i < leaderFDR.getAttributeInt("Number of facility data records"); ++i) {
            try {
                CeosRecordHeader header = new CeosRecordHeader(reader);
                int level = getProductLevel();
                if (level == AlosPalsarConstants.LEVEL1_0 || level == AlosPalsarConstants.LEVEL1_1) {
                    int facilityRecordNum = 11;

                    while (header.getRecordNum() < facilityRecordNum && header.getRecordLength() > 0) {
                        header.seekToEnd();
                        header = new CeosRecordHeader(reader);
                    }

                    facilityRecord = new BinaryRecord(reader, -1, facilityXML, facility_recordDefinitionFile);
                    header.seekToEnd();
                } else {
                    facilityRecord = new BinaryRecord(reader, -1, facility1_5XML, facility_record1_5DefinitionFile);
                    header.seekToEnd();
                }
            } catch (Exception e) {
                SystemUtils.LOG.warning("Unable to read ALOS-4 facility record: " + e.getMessage());
            }
        }
    }
}
