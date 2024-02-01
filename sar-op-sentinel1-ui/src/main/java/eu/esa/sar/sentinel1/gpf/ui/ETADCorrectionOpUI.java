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
import java.io.File;
import java.util.Map;

/**
 * User interface for ETADCorrectionOp
 */
public class ETADCorrectionOpUI extends BaseOperatorUI {

    private final JLabel etadFileLabel = new JLabel("ETAD File:");
    private final JTextField etadFile = new JTextField("");
    private final JButton etadFileBrowseButton = new JButton("...");
    private final JComboBox resamplingType = new JComboBox(ResamplingFactory.resamplingNames);

    @Override
    public JComponent CreateOpTab(String operatorName, Map<String, Object> parameterMap, AppContext appContext) {

        initializeOperatorUI(operatorName, parameterMap);
        final JComponent panel = createPanel();
        initParameters();

        etadFile.setColumns(20);

        etadFileBrowseButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                final File file = Dialogs.requestFileForOpen("ETAD File", false, null, "microwave.etad.aux.file");
                etadFile.setText(file.getAbsolutePath());
            }
        });

        return panel;
    }

    @Override
    public void initParameters() {

        final File extFile = (File) paramMap.get("etadFile");
        if (extFile != null) {
            etadFile.setText(extFile.getAbsolutePath());
        }

        resamplingType.setSelectedItem(paramMap.get("resamplingType"));
    }

    @Override
    public UIValidation validateParameters() {
        return new UIValidation(UIValidation.State.OK, "");
    }

    @Override
    public void updateParameters() {

        final String etadFileStr = etadFile.getText();
        if (!etadFileStr.isEmpty()) {
            paramMap.put("etadFile", new File(etadFileStr));
        }

        paramMap.put("resamplingType", resamplingType.getSelectedItem());
    }

    private JComponent createPanel() {

        final JPanel contentPane = new JPanel(new GridBagLayout());
        final GridBagConstraints gbc = DialogUtils.createGridBagConstraints();

        gbc.gridx = 0;
        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, "Resampling Type:", resamplingType);
        gbc.gridy++;
        DialogUtils.addInnerPanel(contentPane, gbc, etadFileLabel, etadFile, etadFileBrowseButton);

        DialogUtils.fillPanel(contentPane, gbc);

        return contentPane;
    }
}
