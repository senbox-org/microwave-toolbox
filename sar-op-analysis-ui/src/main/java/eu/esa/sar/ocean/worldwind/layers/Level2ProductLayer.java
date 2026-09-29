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
package eu.esa.sar.ocean.worldwind.layers;

import eu.esa.sar.ocean.worldwind.layers.ocn.ColorGradient;
import eu.esa.sar.ocean.worldwind.layers.ocn.OCNComponent;
import eu.esa.sar.ocean.worldwind.layers.ocn.OSW;
import eu.esa.sar.ocean.worldwind.layers.ocn.OWI;
import eu.esa.sar.ocean.worldwind.layers.ocn.RVL;
import gov.nasa.worldwind.avlist.AVKey;
import gov.nasa.worldwind.awt.WorldWindowGLCanvas;
import gov.nasa.worldwind.event.SelectEvent;
import gov.nasa.worldwind.render.*;
import gov.nasa.worldwindx.examples.util.DirectedPath;
import eu.esa.sar.ocean.toolviews.polarview.OceanSwellTopComponent;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.rcp.SnapApp;
import org.esa.snap.worldwind.ColorBarLegend;
import org.esa.snap.worldwind.ProductRenderablesInfo;
import org.esa.snap.worldwind.layers.BaseLayer;
import org.esa.snap.worldwind.layers.WWLayer;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.text.DecimalFormat;
import java.text.Format;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * S-1 L2 OCN visualization
 */
public class Level2ProductLayer extends BaseLayer implements WWLayer {

    private JPanel theControlLevel2Panel;

    private boolean theOWILimitChanged = false;
    private boolean theRVLLimitChanged = false;
    private final static int MAX_WIND_SPEED = 50;

    private JCheckBox theArrowsCB;
    private String theSelectedComp = null;

    final Map<String, ColorBarLegend> theColorBarLegendHash = new HashMap<>();
    private final Map<Object, String> theObjectInfoHash = new HashMap<>();
    private final Map<Object, Product> theSurfaceProductHash = new HashMap<>();
    private final Map<Object, Integer> theSurfaceSequenceHash = new HashMap<>();

    final Map<Product, ProductRenderablesInfo> theProductRenderablesInfoHash = new HashMap<>();

    final ScreenAnnotation theInfoAnnotation;

    private DirectedPath theLastSelectedDP = null;

    private final List<OCNComponent> ocnComponents = new ArrayList<>();

    public Level2ProductLayer() {
        this.setName("S-1 Level-2 OCN");

        //dpHighlightAttrs = new BasicShapeAttributes();
        //dpHighlightAttrs.setOutlineMaterial(Material.WHITE);
        //dpHighlightAttrs.setOutlineWidth(2d);

        ocnComponents.add(new OSW(this,
                theColorBarLegendHash, theObjectInfoHash, theSurfaceProductHash, theSurfaceSequenceHash));
        ocnComponents.add(new OWI(this,
                theColorBarLegendHash, theObjectInfoHash, theSurfaceProductHash, theSurfaceSequenceHash));
        ocnComponents.add(new RVL(this,
                theColorBarLegendHash, theObjectInfoHash, theSurfaceProductHash, theSurfaceSequenceHash));

        theInfoAnnotation = createInfoAnnotation();
    }

    private ScreenAnnotation createInfoAnnotation() {
        // this is copied from gov.nasa.worldwindx.examples.util.LayerManagerLayer
        ScreenAnnotation infoAnnotation = new ScreenAnnotation("", new Point(120, 520));

        // Set annotation so that it will not force text to wrap (large width) and will adjust its width to
        // that of the text. A height of zero will have the annotation height follow that of the text too.
        infoAnnotation.getAttributes().setSize(new Dimension(Integer.MAX_VALUE, 0));
        infoAnnotation.getAttributes().setAdjustWidthToText(AVKey.SIZE_FIT_TEXT);

        // Set appearance attributes
        infoAnnotation.getAttributes().setCornerRadius(0);
        //infoAnnotation.getAttributes().setFont(this.font);
        infoAnnotation.getAttributes().setHighlightScale(1);
        infoAnnotation.getAttributes().setTextColor(Color.WHITE);
        infoAnnotation.getAttributes().setBackgroundColor(new Color(0f, 0f, 0f, .5f));
        infoAnnotation.getAttributes().setInsets(new Insets(6, 6, 6, 6));
        infoAnnotation.getAttributes().setBorderWidth(1);

        infoAnnotation.getAttributes().setVisible(false);

        return infoAnnotation;
    }

    @Override
    public void updateInfoAnnotation(final SelectEvent event) {
        //SystemUtils.LOG.info("updateInfoAnnotation " + event.getTopObject() + " " + theObjectInfoHash.get(event.getTopObject()));
        if (event.getEventAction().equals(SelectEvent.ROLLOVER) && theObjectInfoHash.get(event.getTopObject()) != null) {

            String info = theObjectInfoHash.get(event.getTopObject());
            if (event.getTopObject() instanceof DirectedPath) {
                //SystemUtils.LOG.info("DirectedPath:::");
                DirectedPath dp = (DirectedPath) event.getTopObject();
                //dp.getAttributes().setOutlineMaterial(Material.WHITE);
                dp.setHighlighted(true);
                //dp.setAttributes(productLayer.dpHighlightAttrs);
                //theSelectedObjectLabel.setText("" + productLayer.theObjectInfoHash.get(dp));
                theLastSelectedDP = dp;
            }

            theInfoAnnotation.setText(info);
            theInfoAnnotation.getAttributes().setVisible(true);

            //SystemUtils.LOG.info("selectedProduct " + getSelectedProduct());
            //final ExecCommand command = datApp.getCommandManager().getExecCommand("showPolarWaveView");
            //command.execute(2);
        } else if (event.getEventAction().equals(SelectEvent.LEFT_CLICK) &&
                theSurfaceProductHash.get(event.getTopObject()) != null &&
                theSurfaceSequenceHash.get(event.getTopObject()) != null) {
            //SystemUtils.LOG.info("click " + event.getTopObject());
            OceanSwellTopComponent.setOSWRecord(theSurfaceProductHash.get(event.getTopObject()),
                    theSurfaceSequenceHash.get(event.getTopObject()));

        } else {

            if (theLastSelectedDP != null) {
                theLastSelectedDP.setHighlighted(false);
            }
            theInfoAnnotation.getAttributes().setVisible(false);
            //theSelectedObjectLabel.setText("");
        }
    }

    @Override
    public Suitability getSuitability(Product product) {
        if (product.getProductType().equalsIgnoreCase("OCN")) {
            return Suitability.INTENDED;
        }
        return Suitability.UNSUITABLE;
    }

    @Override
    public void addProduct(final Product product, final WorldWindowGLCanvas wwd) {

        if (!product.getProductType().equalsIgnoreCase("OCN")) {
            return;
        }

        // if the product has already been added, just return
        if (theProductRenderablesInfoHash.get(product) != null) {
            return;
        }

        addRenderable(theInfoAnnotation);

        final String text = "First line<br />Second line";
        theInfoAnnotation.setText(text);
        theInfoAnnotation.getAttributes().setVisible(false);

        //theColorBarLegendProduct = product;
        final ProductRenderablesInfo productRenderablesInfo = new ProductRenderablesInfo();
        // There is code in LayerManagerLayer that updates the size
        //  it's re-rendered
        // Update current size and adjust annotation draw offset according to it's width
        //this.size = theInfoAnnotation.getPreferredSize(dc);
        //this.annotation.getAttributes().setDrawOffset(new Point(this.size.width / 2, 0));

        //SystemUtils.LOG.info("product " + product.getName());

        try {
            for(OCNComponent component : ocnComponents) {
                component.addProduct(product, productRenderablesInfo);
            }

            theProductRenderablesInfoHash.put(product, productRenderablesInfo);
            if (theControlLevel2Panel != null) {
                theControlLevel2Panel.setVisible(true);
            }
            setComponentVisible(theSelectedComp, wwd);

        } catch (Exception e) {
            SnapApp.getDefault().handleError("L2ProductLayer unable to add product " + product.getName(), e);
        }
    }





    private void recreateColorBarAndGradient(double minValue, double maxValue, String comp, WorldWindowGLCanvas wwd, boolean redraw) {
        //SystemUtils.LOG.info("recreateColorBarAndGradient " + minValue + " " + maxValue + " " + comp + " " + theColorBarLegendHash.get(comp));

        String title = "";
        if (comp.equalsIgnoreCase("owi")) {
            title = "OWI Wind Speed";
        } else if (comp.equalsIgnoreCase("osw")) {
            title = "OSW Wave Height.";
        } else if (comp.equalsIgnoreCase("rvl")) {
            title = "RVL Rad. Vel.";
        }

        if (redraw) {
            removeRenderable(theColorBarLegendHash.get(comp));
        }
        createColorBarLegend(minValue, maxValue, title, comp);

        if (redraw) {
            addRenderable(theColorBarLegendHash.get(comp));
        }
        for (ProductRenderablesInfo productRenderablesInfo : theProductRenderablesInfoHash.values()) {
            //createColorGradient(minValue, maxValue, false, theProductRenderablesInfoHash.get(theColorBarLegendProduct), comp);
            ColorGradient.createColorGradient(minValue, maxValue, false, productRenderablesInfo, comp);
        }

        if (redraw) {
            wwd.redrawNow();
        }
    }

    void createColorBarLegend(double minValue, double maxValue, String title, String comp) {
        //SystemUtils.LOG.info("createColorBarLegend " + minValue + " " + maxValue);

        String unit = "m/s";
        if (comp.equalsIgnoreCase("osw")) {
            unit = "m";
        }
        final Format legendLabelFormat = new DecimalFormat("# " + unit);

        final ColorBarLegend colorBarLegend = new ColorBarLegend();
        colorBarLegend.setColorGradient(32, 256, minValue, maxValue, ColorGradient.HUE_RED, ColorGradient.HUE_MAX_RED,
                Color.WHITE,
                ColorBarLegend.createDefaultColorGradientLabels(minValue, maxValue, legendLabelFormat),
                ColorBarLegend.createDefaultTitle(title),
                comp.equalsIgnoreCase("rvl"));

        colorBarLegend.setOpacity(0.8);
        colorBarLegend.setScreenLocation(new Point(900, 320));
        //addRenderable(colorBarLegend);

        theColorBarLegendHash.put(comp, colorBarLegend);
    }

    void setComponentVisible(String comp, WorldWindowGLCanvas wwd) {
        //SystemUtils.LOG.info("setComponentVisible " + comp);
        //SystemUtils.LOG.info("theColorBarLegendHash " + theColorBarLegendHash);
        for (String currComp : theColorBarLegendHash.keySet()) {
            if (theColorBarLegendHash.get(currComp) != null) {
                removeRenderable(theColorBarLegendHash.get(currComp));
                if (currComp.equals(comp)) {
                    addRenderable(theColorBarLegendHash.get(currComp));
                }

                //ProductRenderablesInfo productRenderablesInfo = theProductRenderablesInfoHash.get(theColorBarLegendProduct);
                for (ProductRenderablesInfo productRenderablesInfo : theProductRenderablesInfoHash.values()) {
                    //SystemUtils.LOG.info("::: productRenderablesInfo " + productRenderablesInfo);
                    if (productRenderablesInfo != null) {
                        List<Renderable> renderableList = productRenderablesInfo.theRenderableListHash.get(currComp);
                        for (Renderable renderable : renderableList) {
                            removeRenderable(renderable);
                            if (currComp.equals(comp)) {

                                addRenderable(renderable);
                            }
                        }
                    }
                }
            }
        }

        wwd.redrawNow();
    }

    @Override
    public void removeProduct(final Product product) {

        final ProductRenderablesInfo productRenderablesInfo = theProductRenderablesInfoHash.get(product);

        if (productRenderablesInfo != null) {

            for (List<Renderable> renderableList : productRenderablesInfo.theRenderableListHash.values()) {
                //SystemUtils.LOG.info(":: renderableList " + renderableList);
                for (Renderable renderable : renderableList) {
                    //SystemUtils.LOG.info(":: renderable " + renderable);
                    removeRenderable(renderable);
                    if (renderable instanceof DirectedPath) {
                        theObjectInfoHash.remove(renderable);
                        theSurfaceProductHash.remove(renderable);
                        theSurfaceSequenceHash.remove(renderable);
                    }
                }
                renderableList.clear();
            }
            theProductRenderablesInfoHash.remove(product);
        }

        if (theProductRenderablesInfoHash.isEmpty()) {
            theControlLevel2Panel.setVisible(false);
            for (ColorBarLegend colorBarLegend : theColorBarLegendHash.values()) {
                removeRenderable(colorBarLegend);
            }
        }
    }

    @Override
    public JPanel getControlPanel(final WorldWindowGLCanvas wwd) {
        theControlLevel2Panel = new JPanel(new GridLayout(7, 1, 5, 5));
        theControlLevel2Panel.setVisible(false);
        final JRadioButton owiBtn = new JRadioButton("OWI");
        owiBtn.addActionListener(actionEvent -> {
            theSelectedComp = "owi";
            setComponentVisible("owi", wwd);
            theArrowsCB.setEnabled(true);
        });

        final JRadioButton oswBtn = new JRadioButton("OSW");
        oswBtn.addActionListener(actionEvent -> {
            theSelectedComp = "osw";
            setComponentVisible("osw", wwd);
            theArrowsCB.setEnabled(false);

            //SystemUtils.LOG.info("theSurfaceProductHash " + theSurfaceProductHash);
            //SystemUtils.LOG.info("theSurfaceSequenceHash " + theSurfaceSequenceHash);
        });

        final JRadioButton rvlBtn = new JRadioButton("RVL");
        rvlBtn.addActionListener(actionEvent -> {
            theSelectedComp = "rvl";
            //System.out.println("rvl:");
            //setComponentVisible("owi", false, getWwd());
            //setComponentVisible("osw", false, getWwd());
            setComponentVisible("rvl", wwd);
            theArrowsCB.setEnabled(false);
        });

        final ButtonGroup group = new ButtonGroup();
        group.add(owiBtn);
        group.add(oswBtn);
        group.add(rvlBtn);
        owiBtn.setSelected(true);

        theSelectedComp = "owi";

        final JPanel componentTypePanel = new JPanel(new GridLayout(1, 4, 5, 5));
        componentTypePanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        componentTypePanel.add(new JLabel("Component:"));
        componentTypePanel.add(owiBtn);
        componentTypePanel.add(oswBtn);
        componentTypePanel.add(rvlBtn);
        theControlLevel2Panel.add(componentTypePanel);

        final JPanel arrowDisplayPanel = new JPanel(new GridLayout(1, 2, 5, 5));

        theArrowsCB = new JCheckBox(new AbstractAction() {
            public void actionPerformed(ActionEvent actionEvent) {
                for(OCNComponent component : ocnComponents) {
                    component.setArrowsDisplayed(theArrowsCB.isSelected());
                }
                wwd.redrawNow();
            }
        });

        arrowDisplayPanel.add(new JLabel("Display Wind Vectors:"));
        arrowDisplayPanel.add(theArrowsCB);
        theControlLevel2Panel.add(arrowDisplayPanel);

        /*
        final JPanel subsectionPanel = new JPanel(new GridLayout(1, 2, 5, 5));
        JComboBox sectionDropDown = new JComboBox();
        sectionDropDown.addItem("001");
        sectionDropDown.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                SystemUtils.LOG.info("drop down changed");
            }
        });

        subsectionPanel.add(new JLabel("Subsection:"));
        subsectionPanel.add(sectionDropDown);

        theControlLevel2Panel.add(subsectionPanel);
        */

        final JPanel maxPanel = new JPanel(new GridLayout(1, 2, 5, 5));
        maxPanel.add(new JLabel("Max OWI Wind Speed:"));

        final JSpinner maxSP = new JSpinner(new SpinnerNumberModel(10, 0, MAX_WIND_SPEED, 1));
        maxSP.addChangeListener(e -> {
            int newValue = (Integer) ((JSpinner) e.getSource()).getValue();

            theOWILimitChanged = true;
        });
        maxPanel.add(maxSP);
        theControlLevel2Panel.add(maxPanel);

        final JPanel minPanel = new JPanel(new GridLayout(1, 2, 5, 5));
        minPanel.add(new JLabel("Min OWI Wind Speed:"));

        final JSpinner minSP = new JSpinner(new SpinnerNumberModel(0, 0, 10, 1));
        minSP.addChangeListener(e -> theOWILimitChanged = true);
        minPanel.add(minSP);
        theControlLevel2Panel.add(minPanel);

        final JPanel maxRVLPanel = new JPanel(new GridLayout(1, 2, 5, 5));
        maxRVLPanel.add(new JLabel("Max RVL Rad Vel.:"));

        final JSpinner maxRVLSP = new JSpinner(new SpinnerNumberModel(6, 0, 10, 1));
        maxRVLSP.addChangeListener(e -> {
            int newValue = (Integer) ((JSpinner) e.getSource()).getValue();
            theRVLLimitChanged = true;
        });
        maxRVLPanel.add(maxRVLSP);
        theControlLevel2Panel.add(maxRVLPanel);

        final JButton updateButton = new JButton("Update");
        updateButton.addActionListener(actionEvent -> {

            if (theOWILimitChanged) {

                //double minValue = ((Integer) minSP.getValue()) * 1.0e4;
                //double maxValue = ((Integer) maxSP.getValue()) * 1.0e4;
                double minValue = ((Integer) minSP.getValue());
                double maxValue = ((Integer) maxSP.getValue());
                recreateColorBarAndGradient(minValue, maxValue, "owi", wwd, theSelectedComp.equalsIgnoreCase("owi"));
            }

            if (theRVLLimitChanged) {
                //SystemUtils.LOG.info("theRVLLimitChanged");

                //double minValue = ((Integer) minSP.getValue()) * 1.0e4;
                //double maxValue = ((Integer) maxSP.getValue()) * 1.0e4;

                double maxValue = ((Integer) maxRVLSP.getValue());
                double minValue = -1 * maxValue;

                recreateColorBarAndGradient(minValue, maxValue, "rvl", wwd, theSelectedComp.equalsIgnoreCase("rvl"));
            }

            theOWILimitChanged = false;
            theRVLLimitChanged = false;
        });
        theControlLevel2Panel.add(updateButton);

        createColorBarLegend(0, MAX_WIND_SPEED, "OWI Wind Speed", "owi");
        createColorBarLegend(0, 10, "OSW Wave Height.", "osw");
        createColorBarLegend(-6, 5, "RVL Rad. Vel.", "rvl");
        //addRenderable(theColorBarLegendHash.get("owi"));

        return theControlLevel2Panel;
    }
}