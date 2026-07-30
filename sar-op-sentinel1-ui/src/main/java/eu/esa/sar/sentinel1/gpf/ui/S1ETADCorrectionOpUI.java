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
    // Not "Option 1"/"Option 2": these are independent corrections and enabling BOTH is what the
    // geocode-first (GSLC) InSAR chain needs.
    // Package-private, like the correction-layer boxes below, so the mode state machine is testable.
    final JCheckBox resamplingImageCheckBox =
            new JCheckBox("Resample image (geometric correction)");
    final JCheckBox outputPhaseCorrectionsCheckBox =
            new JCheckBox("Apply range-delay phase correction");
    // Diagnostic: writes the applied phase as a band so the correction can be quantified without
    // reprocessing. Off by default - it adds a non-complex band, which changes what coregistration
    // and stacking see.
    final JCheckBox outputETADPhaseBandCheckBox =
            new JCheckBox("Also output the applied phase as a band (diagnostic)");
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

    private Boolean resamplingImage = true;
    private Boolean outputPhaseCorrections = false;
    private Boolean outputETADPhaseBand = false;
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

        // The two checkboxes are NOT alternatives. Enabling both - resample the image AND remove the
        // range-delay phase from the complex data - is the configuration the geocode-first (GSLC)
        // InSAR chain needs, because both corrections then live in the pixels and survive geocoding.
        // They used to clear each other, which made that combination unreachable from this dialog.
        //
        // The only invalid combination is neither: S1ETADCorrectionOp forces phase corrections on
        // when the image is not resampled, so the UI mirrors that rather than allowing a state the
        // operator would silently rewrite.
        resamplingImageCheckBox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                resamplingImage = (e.getStateChange() == ItemEvent.SELECTED);

                resamplingType.setEnabled(resamplingImage);

                if (resamplingImage) {
                    // The geometric correction needs at least one layer selected, or the operator
                    // rejects the run with "No correction layer is selected".
                    sumOfAzimuthCorrectionsCheckBox.setSelected(true);
                    sumOfRangeCorrectionsCheckBox.setSelected(true);
                } else {
                    // Mirror the operator: no resampling implies phase corrections.
                    outputPhaseCorrectionsCheckBox.setSelected(true);
                    outputPhaseCorrections = true;
                }
                updateCorrectionLayerStates();
            }
        });

        outputPhaseCorrectionsCheckBox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                outputPhaseCorrections = (e.getStateChange() == ItemEvent.SELECTED);

                if (!outputPhaseCorrections) {
                    // Neither correction is not a valid state, so turning the phase off implies
                    // resampling on.
                    resamplingImageCheckBox.setSelected(true);
                    resamplingType.setEnabled(true);
                    resamplingImage = true;
                }
                outputETADPhaseBandCheckBox.setEnabled(outputPhaseCorrections);
                updateCorrectionLayerStates();
            }
        });

        outputETADPhaseBandCheckBox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                outputETADPhaseBand = (e.getStateChange() == ItemEvent.SELECTED);
            }
        });

        troposphericCorrectionRgCheckBox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                troposphericCorrectionRg = (e.getStateChange() == ItemEvent.SELECTED);

                if (resamplingImage) {
                    if (troposphericCorrectionRg) {
                        sumOfRangeCorrectionsCheckBox.setEnabled(false);
                    } else if (!isIndividualRangeCorrectionLayerSelected()) {
                        sumOfRangeCorrectionsCheckBox.setEnabled(true);
                    }
                }
            }
        });

        ionosphericCorrectionRgCheckBox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                ionosphericCorrectionRg = (e.getStateChange() == ItemEvent.SELECTED);

                if (resamplingImage) {
                    if (ionosphericCorrectionRg) {
                        sumOfRangeCorrectionsCheckBox.setEnabled(false);
                    } else if (!isIndividualRangeCorrectionLayerSelected()) {
                        sumOfRangeCorrectionsCheckBox.setEnabled(true);
                    }
                }
            }
        });

        geodeticCorrectionRgCheckBox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                geodeticCorrectionRg = (e.getStateChange() == ItemEvent.SELECTED);

                if (resamplingImage) {
                    if (geodeticCorrectionRg) {
                        sumOfRangeCorrectionsCheckBox.setEnabled(false);
                    } else if (!isIndividualRangeCorrectionLayerSelected()) {
                        sumOfRangeCorrectionsCheckBox.setEnabled(true);
                    }
                }
            }
        });

        dopplerShiftCorrectionRgCheckBox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                dopplerShiftCorrectionRg = (e.getStateChange() == ItemEvent.SELECTED);

                if (resamplingImage) {
                    if (dopplerShiftCorrectionRg) {
                        sumOfRangeCorrectionsCheckBox.setEnabled(false);
                    } else if (!isIndividualRangeCorrectionLayerSelected()) {
                        sumOfRangeCorrectionsCheckBox.setEnabled(true);
                    }
                }
            }
        });

        geodeticCorrectionAzCheckBox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                geodeticCorrectionAz = (e.getStateChange() == ItemEvent.SELECTED);

                if (resamplingImage) {
                    if (geodeticCorrectionAz) {
                        sumOfAzimuthCorrectionsCheckBox.setEnabled(false);
                    } else if (!isIndividualAzimuthCorrectionLayerSelected()) {
                        sumOfAzimuthCorrectionsCheckBox.setEnabled(true);
                    }
                }
            }
        });

        bistaticShiftCorrectionAzCheckBox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                bistaticShiftCorrectionAz = (e.getStateChange() == ItemEvent.SELECTED);

                if (resamplingImage) {
                    if (bistaticShiftCorrectionAz) {
                        sumOfAzimuthCorrectionsCheckBox.setEnabled(false);
                    } else if (!isIndividualAzimuthCorrectionLayerSelected()) {
                        sumOfAzimuthCorrectionsCheckBox.setEnabled(true);
                    }
                }
            }
        });

        fmMismatchCorrectionAzCheckBox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                fmMismatchCorrectionAz = (e.getStateChange() == ItemEvent.SELECTED);

                if (resamplingImage) {
                    if (fmMismatchCorrectionAz) {
                        sumOfAzimuthCorrectionsCheckBox.setEnabled(false);
                    } else if (!isIndividualAzimuthCorrectionLayerSelected()) {
                        sumOfAzimuthCorrectionsCheckBox.setEnabled(true);
                    }
                }
            }
        });

        sumOfAzimuthCorrectionsCheckBox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                sumOfAzimuthCorrections = (e.getStateChange() == ItemEvent.SELECTED);

                if (resamplingImage) {
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
            }
        });

        sumOfRangeCorrectionsCheckBox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                sumOfRangeCorrections = (e.getStateChange() == ItemEvent.SELECTED);

                if (resamplingImage) {
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
            }
        });

        return panel;
    }

    /**
     * Refresh the correction-layer panel from the current mode. Single authority for that state, so
     * the two mode checkboxes cannot leave it inconsistent.
     * <p>
     * The per-layer selections drive the GEOMETRIC correction, so they apply whenever the image is
     * resampled — including the combined mode (resample + phase). They do NOT govern the
     * range-delay phase term, which always covers the full delay regardless of what is ticked here.
     * <p>
     * Previously the layer boxes were disabled and deselected whenever phase corrections were
     * enabled. With the checkboxes no longer mutually exclusive that would drive every layer to
     * false while the image was still being resampled, and the operator would then reject the run
     * with "No correction layer is selected".
     */
    private void updateCorrectionLayerStates() {
        updateCorrectionLayerStates(true);
    }

    /**
     * @param clearWhenInactive when true, also deselect the layers in grids-only mode. Pass false
     *                          from {@link #initParameters()}: the selections there were just loaded
     *                          from the graph, and clearing them would have {@link #updateParameters()}
     *                          silently write the user's saved layer choices back as false.
     */
    private void updateCorrectionLayerStates(final boolean clearWhenInactive) {

        // resamplingImage is a Boolean and may be null before initParameters has run.
        final boolean layersApply = Boolean.TRUE.equals(resamplingImage);

        sumOfRangeCorrectionsCheckBox.setEnabled(layersApply);
        sumOfAzimuthCorrectionsCheckBox.setEnabled(layersApply);

        // Individual layers are subsumed by, and so disabled by, their sum-of counterpart.
        final boolean individualRange = layersApply && !sumOfRangeCorrectionsCheckBox.isSelected();
        troposphericCorrectionRgCheckBox.setEnabled(individualRange);
        ionosphericCorrectionRgCheckBox.setEnabled(individualRange);
        geodeticCorrectionRgCheckBox.setEnabled(individualRange);
        dopplerShiftCorrectionRgCheckBox.setEnabled(individualRange);

        final boolean individualAz = layersApply && !sumOfAzimuthCorrectionsCheckBox.isSelected();
        geodeticCorrectionAzCheckBox.setEnabled(individualAz);
        bistaticShiftCorrectionAzCheckBox.setEnabled(individualAz);
        fmMismatchCorrectionAzCheckBox.setEnabled(individualAz);

        if (!layersApply && clearWhenInactive) {
            // Grids-only mode: the layer selections have no effect, so clear them rather than
            // leaving stale ticks in a disabled panel.
            troposphericCorrectionRgCheckBox.setSelected(false);
            ionosphericCorrectionRgCheckBox.setSelected(false);
            geodeticCorrectionRgCheckBox.setSelected(false);
            dopplerShiftCorrectionRgCheckBox.setSelected(false);
            sumOfRangeCorrectionsCheckBox.setSelected(false);
            geodeticCorrectionAzCheckBox.setSelected(false);
            bistaticShiftCorrectionAzCheckBox.setSelected(false);
            fmMismatchCorrectionAzCheckBox.setSelected(false);
            sumOfAzimuthCorrectionsCheckBox.setSelected(false);
        }
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
        resamplingImage = (Boolean)paramMap.get("resamplingImage");
        outputPhaseCorrections = (Boolean)paramMap.get("outputPhaseCorrections");
        if(resamplingImage != null) {
            resamplingImageCheckBox.setSelected(resamplingImage);
        }
        if(outputPhaseCorrections != null) {
            outputPhaseCorrectionsCheckBox.setSelected(outputPhaseCorrections);
        }
        outputETADPhaseBand = (Boolean)paramMap.get("outputETADPhaseBand");
        if(outputETADPhaseBand != null) {
            outputETADPhaseBandCheckBox.setSelected(outputETADPhaseBand);
        }
        // Only meaningful when a phase correction is actually applied.
        outputETADPhaseBandCheckBox.setEnabled(Boolean.TRUE.equals(outputPhaseCorrections));

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

        // Derive the layer panel's enabled state from the FINAL selections. The mode checkboxes are
        // set above before the layer selections, so their listeners evaluated the panel against
        // stale values; this makes the displayed state consistent with what will be submitted.
        // clearWhenInactive=false: do not touch the selections just loaded from the graph.
        updateCorrectionLayerStates(false);
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
        paramMap.put("resamplingImage", resamplingImage);
        paramMap.put("outputPhaseCorrections", outputPhaseCorrections);
        paramMap.put("outputETADPhaseBand", outputETADPhaseBand);

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
        contentPane.add(resamplingImageCheckBox, gbc);
        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, "Resampling Type:", resamplingType);

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

        gbc.gridwidth = 2;
        contentPane.add(correctionLayerSelectionPanel, gbc);

        gbc.gridx = 0;
        gbc.gridy++;
        contentPane.add(outputPhaseCorrectionsCheckBox, gbc);
        gbc.gridy++;
        contentPane.add(outputETADPhaseBandCheckBox, gbc);
        gbc.gridy++;
        contentPane.add(new JTextArea("PhaseCorrection = -2 * \u03c0 * f * (troposphericCorrectionRg + geodeticCorrectionRg" +
                " \n                               - ionosphericCorrectionRg + instrumentTimingCalibrationRange)" +
                "\nwhere f is the radar frequency. This phase term always covers the full range delay," +
                "\nregardless of which correction layers are selected above - those govern the" +
                "\ngeometric correction only. Sentinel-1 IW/SM SLC only." +
                "\n" +
                "\nFor the geocode-first (GSLC) InSAR chain, enable this TOGETHER with 'Resample" +
                "\nimage' so both corrections are baked into the complex data and survive geocoding." +
                "\nCombining them is supported for IW (TOPS) only."), gbc);

        DialogUtils.fillPanel(contentPane, gbc);

        return contentPane;
    }
}
