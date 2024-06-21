/*
 * Copyright (C) 2020 by Microwave Remote Sensing Lab, IITBombay http://www.mrslab.in
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
package org.csa.rstb.polarimetric.gpf.ui;

import org.esa.snap.graphbuilder.gpf.ui.BaseOperatorUI;
import org.esa.snap.graphbuilder.gpf.ui.UIValidation;
import org.esa.snap.graphbuilder.rcp.utils.DialogUtils;
import org.esa.snap.ui.AppContext;

import javax.swing.*;
import java.awt.*;
import java.util.Map;

public class CPRVIOpUI extends BaseOperatorUI {


    private final JComboBox<String> windowSizeStr =
            new JComboBox(new String[]{"3", "5", "7", "9", "11", "13", "15", "17", "19"});


    @Override
    public JComponent CreateOpTab(String operatorName, Map<String, Object> parameterMap, AppContext appContext) {

        initializeOperatorUI(operatorName, parameterMap);
        final JComponent panel = createPanel();


        initParameters();

        return panel;
    }

    @Override
    public void initParameters() {

        windowSizeStr.setSelectedItem(paramMap.get("windowSizeStr"));
        /* targetWindowSize.setSelectedItem(paramMap.get("targetWindowSizeStr")); */

    }

    @Override
    public UIValidation validateParameters() {

        return new UIValidation(UIValidation.State.OK, "");
    }

    @Override
    public void updateParameters() {

        paramMap.put("windowSizeStr", windowSizeStr.getSelectedItem());
/*         paramMap.put("targetWindowSizeStr", targetWindowSize.getSelectedItem()); */

    }

    private JComponent createPanel() {

        final JPanel contentPane = new JPanel(new GridBagLayout());
        final GridBagConstraints gbc = DialogUtils.createGridBagConstraints();

        DialogUtils.addComponent(contentPane, gbc, "Window Size", windowSizeStr);




        DialogUtils.fillPanel(contentPane, gbc);

        return contentPane;
    }

}
