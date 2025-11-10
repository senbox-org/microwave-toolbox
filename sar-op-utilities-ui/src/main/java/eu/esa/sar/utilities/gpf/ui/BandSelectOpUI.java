/*
 * Copyright (C) 2024 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.utilities.gpf.ui;

import eu.esa.sar.utilities.gpf.BandSelectOp;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.graphbuilder.gpf.ui.BaseOperatorUI;
import org.esa.snap.graphbuilder.gpf.ui.OperatorUIUtils;
import org.esa.snap.graphbuilder.gpf.ui.UIValidation;
import org.esa.snap.graphbuilder.rcp.utils.DialogUtils;
import org.esa.snap.ui.AppContext;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * User interface for Band Select
 */
public class BandSelectOpUI extends BaseOperatorUI {

    private final JList<String> polList = new JList<>();
    private final JLabel polLabel = new JLabel("Polarisations:");
    private final JList<String> subImageList = new JList<>();
    private final JLabel subImageLabel = new JLabel("Imagette:");
    private final JList bandList = new JList();
    private final JTextField bandNamePattern = new JTextField();
    private final JList maskList = new JList();
    private final JLabel maskLabel = new JLabel("Source Masks:");

    @Override
    public JComponent CreateOpTab(String operatorName, Map<String, Object> parameterMap, AppContext appContext) {

        initializeOperatorUI(operatorName, parameterMap);
        final JComponent panel = createPanel();
        initParameters();
        return new JScrollPane(panel);
    }

    @Override
    public void initParameters() {

        if (sourceProducts != null && sourceProducts.length > 0) {
            final String[] srcBandNames = sourceProducts[0].getBandNames();
            final String[] polarizations = getPolarizations(srcBandNames);

            boolean hasPols = polarizations.length > 0;
            polList.setVisible(hasPols);
            polLabel.setVisible(hasPols);

            OperatorUIUtils.initParamList(polList, polarizations,
                    (String[])paramMap.get("selectedPolarisations"));

            // Add ListSelectionListener to polList
            polList.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) {
                    onUpdateBandList();
                }
            });

            final String[] subImages = BandSelectOp.getSubImages(srcBandNames);

            boolean hasSubImages = subImages.length > 0;
            subImageList.setVisible(hasSubImages);
            subImageLabel.setEnabled(hasSubImages);

            OperatorUIUtils.initParamList(subImageList, subImages,
                    (String[])paramMap.get("selectedSubImages"));

            // Add ListSelectionListener to subImageList
            subImageList.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) {
                    onUpdateBandList();
                }
            });
        }

        OperatorUIUtils.initParamList(bandList, getBandNames());

        bandNamePattern.setText((String)paramMap.get("bandNamePattern"));

        String[] maskNames = (sourceProducts != null && sourceProducts.length > 0) ? sourceProducts[0].getMaskGroup().getNodeNames() : new String[0];
        OperatorUIUtils.initParamList(maskList, maskNames, (String[])paramMap.get("sourceMasks"));

        boolean hasMasks = maskNames.length > 0;
        maskList.setVisible(hasMasks);
        maskLabel.setEnabled(hasMasks);
    }

    private void onUpdateBandList() {
        List<String> selectedPolarizations = polList.getSelectedValuesList();
        List<String> selectedSubImages = subImageList.getSelectedValuesList();
        List<String> bandNamesList = new ArrayList<>(Arrays.asList(getBandNames()));

        if (!selectedPolarizations.isEmpty()) {
            bandNamesList.removeIf(name -> selectedPolarizations.stream().noneMatch(name::contains));
        }
        if (!selectedSubImages.isEmpty()) {
            bandNamesList.removeIf(name -> selectedSubImages.stream().noneMatch(name::contains));
        }

        OperatorUIUtils.initParamList(bandList, bandNamesList.toArray(new String[0]));
    }

    String[] getPolarizations(String[] bandNames) {
        final Set<String> pols = new TreeSet<>();
        for(String name : bandNames) {
            final String pol = OperatorUtils.getPolarizationFromBandName(name);
            if(pol != null)
                pols.add(pol.toUpperCase());
        }
        return pols.toArray(new String[0]);
    }

    @Override
    public UIValidation validateParameters() {
        return new UIValidation(UIValidation.State.OK, "");
    }

    @Override
    public void updateParameters() {

        OperatorUIUtils.updateParamList(polList, paramMap, "selectedPolarisations");
        OperatorUIUtils.updateParamList(subImageList, paramMap, "selectedSubImages");
        OperatorUIUtils.updateParamList(bandList, paramMap, OperatorUIUtils.SOURCE_BAND_NAMES);
        OperatorUIUtils.updateParamList(maskList, paramMap, "sourceMasks");

        paramMap.put("bandNamePattern", bandNamePattern.getText());
    }

    private JComponent createPanel() {

        final JPanel contentPane = new JPanel(new GridBagLayout());
        final GridBagConstraints gbc = DialogUtils.createGridBagConstraints();

        DialogUtils.addComponent(contentPane, gbc, polLabel, polList);
        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, subImageLabel, new JScrollPane(subImageList));

        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, "Source Bands:", new JScrollPane(bandList));

        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, "Band Name Pattern:", bandNamePattern);

        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, maskLabel, new JScrollPane(maskList));

        DialogUtils.fillPanel(contentPane, gbc);

        return contentPane;
    }
}
