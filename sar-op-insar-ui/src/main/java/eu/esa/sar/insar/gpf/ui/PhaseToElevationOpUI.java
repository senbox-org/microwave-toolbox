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

import org.esa.snap.core.dataop.dem.ElevationModelDescriptor;
import org.esa.snap.core.dataop.dem.ElevationModelRegistry;
import org.esa.snap.core.dataop.resamp.ResamplingFactory;
import org.esa.snap.dem.dataio.DEMFactory;
import org.esa.snap.graphbuilder.gpf.ui.BaseOperatorUI;
import org.esa.snap.graphbuilder.gpf.ui.OperatorUIUtils;
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
 * User interface for PhaseToElevationOp
 */
public class PhaseToElevationOpUI extends BaseOperatorUI {

    private static final String METHOD_DEM_SEED = "DEM Seed";
    private static final String METHOD_SCHWABISCH = "Schwabisch";

    private final JList bandList = new JList();
    private final JComboBox<String> method = new JComboBox<>(new String[]{METHOD_DEM_SEED, METHOD_SCHWABISCH});
    private final JComboBox<String> demName = new JComboBox<>();
    private static final String externalDEMStr = "External DEM";

    private final JComboBox<String> demResamplingMethod = new JComboBox<>(ResamplingFactory.resamplingNames);

    private final JTextField externalDEMFile = new JTextField("");
    private final JTextField externalDEMNoDataValue = new JTextField("");
    private final JButton externalDEMBrowseButton = new JButton("...");
    private final JLabel externalDEMFileLabel = new JLabel("External DEM:");
    private final JLabel externalDEMNoDataValueLabel = new JLabel("DEM No Data Value:");
    private final JLabel demNameLabel = new JLabel("Digital Elevation Model:");
    private final JLabel demResamplingMethodLabel = new JLabel("DEM Resampling Method:");

    private final JComboBox<String> nPoints = new JComboBox<>(new String[]{"100", "200", "300", "400", "500"});
    private final JComboBox<String> nHeights = new JComboBox<>(new String[]{"2", "3", "4", "5"});
    private final JComboBox<String> degree1D = new JComboBox<>(new String[]{"1", "2", "3", "4", "5"});
    private final JComboBox<String> degree2D = new JComboBox<>(new String[]{"1", "2", "3", "4", "5", "6", "7", "8"});
    private final JComboBox<String> orbitDegree = new JComboBox<>(new String[]{"2", "3", "4", "5"});

    private final JLabel nPointsLabel = new JLabel("Number of estimation points:");
    private final JLabel nHeightsLabel = new JLabel("Number of height samples:");
    private final JLabel degree1DLabel = new JLabel("Degree of 1D polynomial:");
    private final JLabel degree2DLabel = new JLabel("Degree of 2D polynomial:");

    private Double extNoDataValue = 0.0;

    private final DialogUtils.TextAreaKeyListener textAreaKeyListener = new DialogUtils.TextAreaKeyListener();

    @Override
    public JComponent CreateOpTab(String operatorName, Map<String, Object> parameterMap, AppContext appContext) {

        final String[] demNames = DEMFactory.getDEMNameList();
        for (String name : demNames) {
            demName.addItem(name);
        }
        demName.addItem(externalDEMStr);

        initializeOperatorUI(operatorName, parameterMap);

        final JComponent panel = createPanel();
        initParameters();

        demName.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent event) {
                if (((String) demName.getSelectedItem()).startsWith(externalDEMStr)) {
                    enableExternalDEM(true);
                } else {
                    externalDEMFile.setText("");
                    enableExternalDEM(false);
                }
            }
        });
        externalDEMFile.setColumns(30);
        enableExternalDEM(((String) demName.getSelectedItem()).startsWith(externalDEMStr));

        method.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent event) {
                enableMethodComponents();
            }
        });
        enableMethodComponents();

        externalDEMBrowseButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                final File file = Dialogs.requestFileForOpen("External DEM File", false, null, DEMFactory.LAST_EXTERNAL_DEM_DIR_KEY);
                externalDEMFile.setText(file.getAbsolutePath());
                extNoDataValue = OperatorUIUtils.getNoDataValue(file);
                externalDEMNoDataValue.setText(String.valueOf(extNoDataValue));
            }
        });

        externalDEMNoDataValue.addKeyListener(textAreaKeyListener);

        return new JScrollPane(panel);
    }

    @Override
    public void initParameters() {

        OperatorUIUtils.initParamList(bandList, getBandNames());

        final String methodParam = (String) paramMap.get("method");
        if (methodParam != null) {
            method.setSelectedItem(methodParam);
        }

        setComboValue(nPoints, paramMap.get("nPoints"));
        setComboValue(nHeights, paramMap.get("nHeights"));
        setComboValue(degree1D, paramMap.get("degree1D"));
        setComboValue(degree2D, paramMap.get("degree2D"));
        setComboValue(orbitDegree, paramMap.get("orbitDegree"));

        final String demNameParam = (String) paramMap.get("demName");
        if (demNameParam != null) {
            ElevationModelDescriptor descriptor = ElevationModelRegistry.getInstance().getDescriptor(demNameParam);
            if(descriptor != null) {
                demName.setSelectedItem(DEMFactory.getDEMDisplayName(descriptor));
            } else {
                demName.setSelectedItem(demNameParam);
            }
        }
        demResamplingMethod.setSelectedItem(paramMap.get("demResamplingMethod"));

        final File extFile = (File) paramMap.get("externalDEMFile");
        if (extFile != null) {
            externalDEMFile.setText(extFile.getAbsolutePath());
            extNoDataValue = (Double) paramMap.get("externalDEMNoDataValue");
            if (extNoDataValue != null && !textAreaKeyListener.isChangedByUser()) {
                externalDEMNoDataValue.setText(String.valueOf(extNoDataValue));
            }
        }

        enableMethodComponents();
    }

    @Override
    public UIValidation validateParameters() {

        if (isSchwabisch()) {
            // The 2D polynomial has (d+1)(d+2)/2 coefficients and each estimation
            // point contributes one observation.
            final int d = Integer.parseInt((String) degree2D.getSelectedItem());
            final int numUnknowns = (d + 1) * (d + 2) / 2;
            final int numPoints = Integer.parseInt((String) nPoints.getSelectedItem());
            if (numPoints < numUnknowns) {
                return new UIValidation(UIValidation.State.ERROR,
                        "A degree-" + d + " 2D polynomial needs at least " + numUnknowns
                                + " estimation points. Increase the number of estimation points "
                                + "or decrease the 2D polynomial degree.");
            }
        }
        return new UIValidation(UIValidation.State.OK, "");
    }

    @Override
    public void updateParameters() {

        OperatorUIUtils.updateParamList(bandList, paramMap, OperatorUIUtils.SOURCE_BAND_NAMES);

        paramMap.put("method", method.getSelectedItem());
        paramMap.put("orbitDegree", Integer.parseInt((String) orbitDegree.getSelectedItem()));

        if (isSchwabisch()) {
            paramMap.put("nPoints", Integer.parseInt((String) nPoints.getSelectedItem()));
            paramMap.put("nHeights", Integer.parseInt((String) nHeights.getSelectedItem()));
            paramMap.put("degree1D", Integer.parseInt((String) degree1D.getSelectedItem()));
            paramMap.put("degree2D", Integer.parseInt((String) degree2D.getSelectedItem()));
            return;
        }

        paramMap.put("demName", (DEMFactory.getProperDEMName((String) demName.getSelectedItem())));
        paramMap.put("demResamplingMethod", demResamplingMethod.getSelectedItem());

        final String extFileStr = externalDEMFile.getText();
        if (!extFileStr.isEmpty()) {
            paramMap.put("externalDEMFile", new File(extFileStr));
            paramMap.put("externalDEMNoDataValue", Double.parseDouble(externalDEMNoDataValue.getText()));
        }
    }

    private boolean isSchwabisch() {
        return METHOD_SCHWABISCH.equals(method.getSelectedItem());
    }

    private static void setComboValue(final JComboBox<String> combo, final Object value) {
        if (value != null) {
            combo.setSelectedItem(String.valueOf(value));
        }
    }

    private JComponent createPanel() {

        final JPanel contentPane = new JPanel(new GridBagLayout());
        final GridBagConstraints gbc = DialogUtils.createGridBagConstraints();

        gbc.gridx = 0;
        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, "Method:", method);
        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, "Orbit Interpolation Degree:", orbitDegree);

        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, demNameLabel, demName);
        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, externalDEMFileLabel, externalDEMFile);
        gbc.gridx = 2;
        contentPane.add(externalDEMBrowseButton, gbc);
        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, externalDEMNoDataValueLabel, externalDEMNoDataValue);
        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, demResamplingMethodLabel, demResamplingMethod);

        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, nPointsLabel, nPoints);
        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, nHeightsLabel, nHeights);
        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, degree1DLabel, degree1D);
        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, degree2DLabel, degree2D);

        DialogUtils.fillPanel(contentPane, gbc);

        return contentPane;
    }

    private void enableExternalDEM(boolean flag) {
        final boolean demInUse = flag && !isSchwabisch();
        DialogUtils.enableComponents(externalDEMFileLabel, externalDEMFile, demInUse);
        DialogUtils.enableComponents(externalDEMNoDataValueLabel, externalDEMNoDataValue, demInUse);
        externalDEMBrowseButton.setVisible(demInUse);
    }

    /**
     * The two methods take disjoint parameter sets: Schwabisch works from the
     * orbits alone, the seed method needs a DEM. Show only what applies.
     */
    private void enableMethodComponents() {

        final boolean schwabisch = isSchwabisch();

        DialogUtils.enableComponents(demNameLabel, demName, !schwabisch);
        DialogUtils.enableComponents(demResamplingMethodLabel, demResamplingMethod, !schwabisch);
        enableExternalDEM(((String) demName.getSelectedItem()).startsWith(externalDEMStr));

        DialogUtils.enableComponents(nPointsLabel, nPoints, schwabisch);
        DialogUtils.enableComponents(nHeightsLabel, nHeights, schwabisch);
        DialogUtils.enableComponents(degree1DLabel, degree1D, schwabisch);
        DialogUtils.enableComponents(degree2DLabel, degree2D, schwabisch);
    }
}
