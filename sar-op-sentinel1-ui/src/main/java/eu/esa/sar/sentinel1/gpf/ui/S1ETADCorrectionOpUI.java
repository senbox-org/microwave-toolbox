/*
 * Copyright (C) 2023 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.sentinel1.gpf.ui;

import org.esa.snap.core.dataop.resamp.ResamplingFactory;
import org.esa.snap.graphbuilder.gpf.ui.BaseOperatorUI;
import org.esa.snap.graphbuilder.gpf.ui.UIValidation;
import org.esa.snap.graphbuilder.rcp.utils.DialogUtils;
import org.esa.snap.rcp.util.Dialogs;
import org.esa.snap.ui.AppContext;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.io.File;
import java.util.Map;

/**
 * User interface for S1ETADCorrectionOp
 */
public class S1ETADCorrectionOpUI extends BaseOperatorUI {

    private final JRadioButton autoDownloadRadioButton = new JRadioButton("Auto-download ETAD File");
    private final JRadioButton manualETADRadioButton = new JRadioButton("Specify ETAD File:");
    private final ButtonGroup etadButtonGroup = new ButtonGroup();

    private final JLabel etadFileLabel = new JLabel("");
    private final JTextField etadFile = new JTextField("");
    private final JButton etadFileBrowseButton = new JButton("...");
    private final JComboBox resamplingType = new JComboBox(ResamplingFactory.resamplingNames);
    final JCheckBox troposphericCorrectionRgCheckBox = new JCheckBox("Tropospheric Correction (Range)");
    final JCheckBox ionosphericCorrectionRgCheckBox = new JCheckBox("Ionospheric Correction (Range)");
    final JCheckBox geodeticCorrectionRgCheckBox = new JCheckBox("Geodetic Correction (Range)");
    final JCheckBox dopplerShiftCorrectionRgCheckBox = new JCheckBox("Doppler Shift Correction (Range)");
    final JCheckBox geodeticCorrectionAzCheckBox = new JCheckBox("Geodetic Correction (Azimuth)");
    final JCheckBox bistaticShiftCorrectionAzCheckBox = new JCheckBox("Bistatic Shift Correction (Azimuth)");
    final JCheckBox fmMismatchCorrectionAzCheckBox = new JCheckBox("FM Mismatch Correction (Azimuth)");
    final JCheckBox sumOfAzimuthCorrectionsCheckBox = new JCheckBox("Sum Of Azimuth Corrections");
    final JCheckBox sumOfRangeCorrectionsCheckBox = new JCheckBox("Sum Of Range Corrections");

    private Boolean troposphericCorrectionRg = false;
    private Boolean ionosphericCorrectionRg = false;
    private Boolean geodeticCorrectionRg = false;
    private Boolean dopplerShiftCorrectionRg = false;
    private Boolean geodeticCorrectionAz = false;
    private Boolean bistaticShiftCorrectionAz = false;
    private Boolean fmMismatchCorrectionAz = false;
    private Boolean sumOfAzimuthCorrections = false;
    private Boolean sumOfRangeCorrections = false;

    @Override
    public JComponent CreateOpTab(String operatorName, Map<String, Object> parameterMap, AppContext appContext) {

        initializeOperatorUI(operatorName, parameterMap);
        final JComponent panel = createPanel();
        initParameters();

        etadButtonGroup.add(autoDownloadRadioButton);
        etadButtonGroup.add(manualETADRadioButton);
        autoDownloadRadioButton.setSelected(true); // Default to auto-download

        etadFile.setEnabled(false);
        etadFileBrowseButton.setEnabled(false);

        autoDownloadRadioButton.addActionListener(e -> {
            etadFile.setEnabled(false);
            etadFileBrowseButton.setEnabled(false);
        });

        manualETADRadioButton.addActionListener(e -> {
            etadFile.setEnabled(true);
            etadFileBrowseButton.setEnabled(true);
        });

        etadFile.setColumns(20);

        etadFileBrowseButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                final File file = Dialogs.requestFileForOpen("ETAD File", false, null, "microwave.etad.aux.file");
                etadFile.setText(file.getAbsolutePath());
            }
        });

        troposphericCorrectionRgCheckBox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                troposphericCorrectionRg = (e.getStateChange() == ItemEvent.SELECTED);

                if (troposphericCorrectionRg) {
                    sumOfRangeCorrectionsCheckBox.setEnabled(false);
                } else if (!isIndividualRangeCorrectionLayerSelected()) {
                    sumOfRangeCorrectionsCheckBox.setEnabled(true);
                }
            }
        });

        ionosphericCorrectionRgCheckBox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                ionosphericCorrectionRg = (e.getStateChange() == ItemEvent.SELECTED);

                if (ionosphericCorrectionRg) {
                    sumOfRangeCorrectionsCheckBox.setEnabled(false);
                } else if (!isIndividualRangeCorrectionLayerSelected()) {
                    sumOfRangeCorrectionsCheckBox.setEnabled(true);
                }
            }
        });

        geodeticCorrectionRgCheckBox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                geodeticCorrectionRg = (e.getStateChange() == ItemEvent.SELECTED);

                if (geodeticCorrectionRg) {
                    sumOfRangeCorrectionsCheckBox.setEnabled(false);
                } else if (!isIndividualRangeCorrectionLayerSelected()) {
                    sumOfRangeCorrectionsCheckBox.setEnabled(true);
                }
            }
        });

        dopplerShiftCorrectionRgCheckBox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                dopplerShiftCorrectionRg = (e.getStateChange() == ItemEvent.SELECTED);

                if (dopplerShiftCorrectionRg) {
                    sumOfRangeCorrectionsCheckBox.setEnabled(false);
                } else if (!isIndividualRangeCorrectionLayerSelected()) {
                    sumOfRangeCorrectionsCheckBox.setEnabled(true);
                }
            }
        });

        geodeticCorrectionAzCheckBox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                geodeticCorrectionAz = (e.getStateChange() == ItemEvent.SELECTED);

                if (geodeticCorrectionAz) {
                    sumOfAzimuthCorrectionsCheckBox.setEnabled(false);
                } else if (!isIndividualAzimuthCorrectionLayerSelected()) {
                    sumOfAzimuthCorrectionsCheckBox.setEnabled(true);
                }
            }
        });

        bistaticShiftCorrectionAzCheckBox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                bistaticShiftCorrectionAz = (e.getStateChange() == ItemEvent.SELECTED);

                if (bistaticShiftCorrectionAz) {
                    sumOfAzimuthCorrectionsCheckBox.setEnabled(false);
                } else if (!isIndividualAzimuthCorrectionLayerSelected()) {
                    sumOfAzimuthCorrectionsCheckBox.setEnabled(true);
                }
            }
        });

        fmMismatchCorrectionAzCheckBox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                fmMismatchCorrectionAz = (e.getStateChange() == ItemEvent.SELECTED);

                if (fmMismatchCorrectionAz) {
                    sumOfAzimuthCorrectionsCheckBox.setEnabled(false);
                } else if (!isIndividualAzimuthCorrectionLayerSelected()) {
                    sumOfAzimuthCorrectionsCheckBox.setEnabled(true);
                }
            }
        });

        sumOfAzimuthCorrectionsCheckBox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                sumOfAzimuthCorrections = (e.getStateChange() == ItemEvent.SELECTED);

                if (sumOfAzimuthCorrections) {
                    geodeticCorrectionAzCheckBox.setEnabled(false);
                    bistaticShiftCorrectionAzCheckBox.setEnabled(false);
                    fmMismatchCorrectionAzCheckBox.setEnabled(false);
                } else {
                    geodeticCorrectionAzCheckBox.setEnabled(true);
                    bistaticShiftCorrectionAzCheckBox.setEnabled(true);
                    fmMismatchCorrectionAzCheckBox.setEnabled(true);
                }
            }
        });

        sumOfRangeCorrectionsCheckBox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                sumOfRangeCorrections = (e.getStateChange() == ItemEvent.SELECTED);

                if (sumOfRangeCorrections) {
                    troposphericCorrectionRgCheckBox.setEnabled(false);
                    ionosphericCorrectionRgCheckBox.setEnabled(false);
                    geodeticCorrectionRgCheckBox.setEnabled(false);
                    dopplerShiftCorrectionRgCheckBox.setEnabled(false);
                } else {
                    troposphericCorrectionRgCheckBox.setEnabled(true);
                    ionosphericCorrectionRgCheckBox.setEnabled(true);
                    geodeticCorrectionRgCheckBox.setEnabled(true);
                    dopplerShiftCorrectionRgCheckBox.setEnabled(true);
                }
            }
        });

        return panel;
    }

    private boolean isIndividualRangeCorrectionLayerSelected() {
        return (troposphericCorrectionRg != null && troposphericCorrectionRg) ||
                (ionosphericCorrectionRg != null && ionosphericCorrectionRg) ||
                (geodeticCorrectionRg != null && geodeticCorrectionRg) ||
                (dopplerShiftCorrectionRg != null && dopplerShiftCorrectionRg);
    }

    private boolean isIndividualAzimuthCorrectionLayerSelected() {
        return (geodeticCorrectionAz != null && geodeticCorrectionAz) ||
                (bistaticShiftCorrectionAz != null && bistaticShiftCorrectionAz) ||
                (fmMismatchCorrectionAz != null && fmMismatchCorrectionAz);
    }

    @Override
    public void initParameters() {

        final File extFile = (File) paramMap.get("etadFile");
        if (extFile != null) {
            etadFile.setText(extFile.getAbsolutePath());
        }

        resamplingType.setSelectedItem(paramMap.get("resamplingType"));

        troposphericCorrectionRg = (Boolean)paramMap.get("troposphericCorrectionRg");
        ionosphericCorrectionRg = (Boolean)paramMap.get("ionosphericCorrectionRg");
        geodeticCorrectionRg = (Boolean)paramMap.get("geodeticCorrectionRg");
        dopplerShiftCorrectionRg = (Boolean)paramMap.get("dopplerShiftCorrectionRg");
        geodeticCorrectionAz = (Boolean)paramMap.get("geodeticCorrectionAz");
        bistaticShiftCorrectionAz = (Boolean)paramMap.get("bistaticShiftCorrectionAz");
        fmMismatchCorrectionAz = (Boolean)paramMap.get("fmMismatchCorrectionAz");
        sumOfAzimuthCorrections = (Boolean)paramMap.get("sumOfAzimuthCorrections");
        sumOfRangeCorrections = (Boolean)paramMap.get("sumOfRangeCorrections");

        if(troposphericCorrectionRg != null) {
            troposphericCorrectionRgCheckBox.setSelected(troposphericCorrectionRg);
        }

        if(ionosphericCorrectionRg != null) {
            ionosphericCorrectionRgCheckBox.setSelected(ionosphericCorrectionRg);
        }

        if(geodeticCorrectionRg != null) {
            geodeticCorrectionRgCheckBox.setSelected(geodeticCorrectionRg);
        }

        if(dopplerShiftCorrectionRg != null) {
            dopplerShiftCorrectionRgCheckBox.setSelected(dopplerShiftCorrectionRg);
        }

        if(geodeticCorrectionAz != null) {
            geodeticCorrectionAzCheckBox.setSelected(geodeticCorrectionAz);
        }

        if(bistaticShiftCorrectionAz != null) {
            bistaticShiftCorrectionAzCheckBox.setSelected(bistaticShiftCorrectionAz);
        }

        if(fmMismatchCorrectionAz != null) {
            fmMismatchCorrectionAzCheckBox.setSelected(fmMismatchCorrectionAz);
        }

        if(sumOfAzimuthCorrections != null) {
            sumOfAzimuthCorrectionsCheckBox.setSelected(sumOfAzimuthCorrections);
            if (sumOfAzimuthCorrections) {
                geodeticCorrectionAzCheckBox.setEnabled(false);
                bistaticShiftCorrectionAzCheckBox.setEnabled(false);
                fmMismatchCorrectionAzCheckBox.setEnabled(false);
            }
        }

        if(sumOfRangeCorrections != null) {
            sumOfRangeCorrectionsCheckBox.setSelected(sumOfRangeCorrections);
            if (sumOfRangeCorrections) {
                troposphericCorrectionRgCheckBox.setEnabled(false);
                ionosphericCorrectionRgCheckBox.setEnabled(false);
                geodeticCorrectionRgCheckBox.setEnabled(false);
                dopplerShiftCorrectionRgCheckBox.setEnabled(false);
            }
        }
    }

    @Override
    public UIValidation validateParameters() {
        return new UIValidation(UIValidation.State.OK, "");
    }

    @Override
    public void updateParameters() {

        if (paramMap.containsKey("etadFile")) {
            manualETADRadioButton.setSelected(true);
            etadFile.setEnabled(true);
            etadFileBrowseButton.setEnabled(true);
        } else {
            autoDownloadRadioButton.setSelected(true);
        }

        final String etadFileStr = etadFile.getText();
        if (!etadFileStr.isEmpty()) {
            paramMap.put("etadFile", new File(etadFileStr));
        }

        paramMap.put("resamplingType", resamplingType.getSelectedItem());

        paramMap.put("troposphericCorrectionRg", troposphericCorrectionRg);
        paramMap.put("ionosphericCorrectionRg", ionosphericCorrectionRg);
        paramMap.put("geodeticCorrectionRg", geodeticCorrectionRg);
        paramMap.put("dopplerShiftCorrectionRg", dopplerShiftCorrectionRg);
        paramMap.put("geodeticCorrectionAz", geodeticCorrectionAz);
        paramMap.put("bistaticShiftCorrectionAz", bistaticShiftCorrectionAz);
        paramMap.put("fmMismatchCorrectionAz", fmMismatchCorrectionAz);
        paramMap.put("sumOfAzimuthCorrections", sumOfAzimuthCorrections);
        paramMap.put("sumOfRangeCorrections", sumOfRangeCorrections);
    }

    private JComponent createPanel() {

        final JPanel contentPane = new JPanel(new GridBagLayout());
        final GridBagConstraints gbc = DialogUtils.createGridBagConstraints();

        gbc.gridx = 0;
        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, "Resampling Type:", resamplingType);

        gbc.gridx = 0;
        gbc.gridy++;
        contentPane.add(autoDownloadRadioButton, gbc);
        gbc.gridy++;
        contentPane.add(manualETADRadioButton, gbc);

        final JPanel innerPane = new JPanel(new GridBagLayout());
        final GridBagConstraints gbc3 = DialogUtils.createGridBagConstraints();
        innerPane.add(etadFile, gbc3);
        gbc3.gridx = 1;
        innerPane.add(etadFileBrowseButton, gbc3);

        gbc.gridx = 1;
        contentPane.add(innerPane, gbc);

        gbc.gridx = 0;
        gbc.gridy++;
        final JPanel correctionLayerSelectionPanel = new JPanel(new GridBagLayout());
        final GridBagConstraints gbc2 = DialogUtils.createGridBagConstraints();
        correctionLayerSelectionPanel.setBorder(BorderFactory.createTitledBorder("Select correction layers:"));

        gbc2.gridx = 0;
        correctionLayerSelectionPanel.add(sumOfRangeCorrectionsCheckBox, gbc2);
        gbc2.gridx = 1;
        correctionLayerSelectionPanel.add(sumOfAzimuthCorrectionsCheckBox, gbc2);

        gbc2.gridy++;
        gbc2.gridx = 0;
        correctionLayerSelectionPanel.add(troposphericCorrectionRgCheckBox, gbc2);
        gbc2.gridx = 1;
        correctionLayerSelectionPanel.add(geodeticCorrectionAzCheckBox, gbc2);

        gbc2.gridy++;
        gbc2.gridx = 0;
        correctionLayerSelectionPanel.add(ionosphericCorrectionRgCheckBox, gbc2);
        gbc2.gridx = 1;
        correctionLayerSelectionPanel.add(bistaticShiftCorrectionAzCheckBox, gbc2);

        gbc2.gridy++;
        gbc2.gridx = 0;
        correctionLayerSelectionPanel.add(geodeticCorrectionRgCheckBox, gbc2);
        gbc2.gridx = 1;
        correctionLayerSelectionPanel.add(fmMismatchCorrectionAzCheckBox, gbc2);

        gbc2.gridy++;
        gbc2.gridx = 0;
        correctionLayerSelectionPanel.add(dopplerShiftCorrectionRgCheckBox, gbc2);
        gbc2.gridx = 1;
//        correctionLayerSelectionPanel.add(interferometricPhaseCorrectionRgCheckBox, gbc2);

        gbc.gridwidth = 2;
        contentPane.add(correctionLayerSelectionPanel, gbc);

        DialogUtils.fillPanel(contentPane, gbc);

        return contentPane;
    }
}
