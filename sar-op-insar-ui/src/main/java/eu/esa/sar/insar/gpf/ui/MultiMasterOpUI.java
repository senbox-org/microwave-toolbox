/*
 * Copyright (C) 2014 by Array Systems Computing Inc. http://www.array.ca
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
package eu.esa.sar.insar.gpf.ui;

import org.esa.snap.graphbuilder.gpf.ui.BaseOperatorUI;
import org.esa.snap.graphbuilder.gpf.ui.UIValidation;
import org.esa.snap.graphbuilder.rcp.utils.DialogUtils;
import org.esa.snap.ui.AppContext;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.util.Map;

/**
 * User interface for MultiReferenceOp
 */
public class MultiMasterOpUI extends BaseOperatorUI {

    private static final String OUTPUT_FOLDER = "outputFolder";

    // one chooser per dialog, created with the tab: a static one was shared between dialogs and
    // was built at class-initialisation time
    private JFileChooser outputDirectory;


    @Override
    public JComponent CreateOpTab(String operatorName, Map<String, Object> parameterMap, AppContext appContext) {
        outputDirectory = new JFileChooser();
        outputDirectory.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        outputDirectory.setDialogType(JFileChooser.OPEN_DIALOG);


        initializeOperatorUI(operatorName, parameterMap);
        final JComponent panel = createPanel();
        initParameters();

        return new JScrollPane(panel);
    }

    /**
     * Shows the graph's outputFolder; falls back to user.home only when the graph has none, so a
     * loaded graph's folder is not replaced.
     */
    @Override
    public void initParameters() {
        final Object value = paramMap.get(OUTPUT_FOLDER);
        final String folder = value == null ? "" : value.toString().trim();
        outputDirectory.setSelectedFile(new File(folder.isEmpty() ? System.getProperty("user.home") : folder));
    }


    @Override
    public UIValidation validateParameters() {

        return new UIValidation(UIValidation.State.OK, "");
    }

    @Override
    public void updateParameters() {

        final File selected = outputDirectory.getSelectedFile();
        if (selected != null) {
            // getPath keeps a loaded graph's (possibly relative) folder as written
            paramMap.put(OUTPUT_FOLDER, selected.getPath());
        }
    }

    private JComponent createPanel() {

        final JPanel contentPane = new JPanel();
        contentPane.setLayout(new GridBagLayout());
        final GridBagConstraints gbc = DialogUtils.createGridBagConstraints();

        contentPane.add(new JLabel(" "), gbc);
        gbc.gridy++;

        final JPanel gridPanel = new JPanel(new GridBagLayout());
        final GridBagConstraints gbc2 = DialogUtils.createGridBagConstraints();
        gridPanel.setBorder(BorderFactory.createTitledBorder("Output parameters"));


        DialogUtils.addComponent(contentPane, gbc, "Output folder", outputDirectory);
        gbc.gridy++;

        DialogUtils.fillPanel(contentPane, gbc);

        return contentPane;
    }

}
