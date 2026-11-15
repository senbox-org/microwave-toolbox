/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc.
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

import org.esa.snap.core.gpf.GPF;
import org.esa.snap.graphbuilder.gpf.ui.OperatorUI;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Every entry under OperatorUIs in this module's layer.xml must name a loadable OperatorUI class and a registered
 * operator alias; otherwise the custom UI silently never attaches and the generic dialog is shown instead.
 */
public class TestLayerOperatorUIBindings {

    private static final String LAYER = "/eu/esa/sar/insar/layer.xml";

    @Test
    public void testOperatorUIsBindToExistingClassesAndAliases() throws Exception {
        final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        final Document doc;
        try (InputStream in = TestLayerOperatorUIBindings.class.getResourceAsStream(LAYER)) {
            assertNotNull(LAYER + " not on classpath", in);
            doc = f.newDocumentBuilder().parse(in);
        }

        Element operatorUIs = null;
        final NodeList folders = doc.getElementsByTagName("folder");
        for (int i = 0; i < folders.getLength(); i++) {
            final Element e = (Element) folders.item(i);
            if ("OperatorUIs".equals(e.getAttribute("name"))) {
                operatorUIs = e;
            }
        }
        assertNotNull("OperatorUIs folder missing", operatorUIs);

        final List<String> problems = new ArrayList<>();
        final NodeList files = operatorUIs.getElementsByTagName("file");
        for (int i = 0; i < files.getLength(); i++) {
            final Element file = (Element) files.item(i);
            final String uiClass = attr(file, "operatorUIClass");
            final String opName = attr(file, "operatorName");
            try {
                // initialize=false: checking the binding must not run a UI's static initialisers
                final Class<?> c = Class.forName(uiClass, false, TestLayerOperatorUIBindings.class.getClassLoader());
                if (!OperatorUI.class.isAssignableFrom(c)) {
                    problems.add(uiClass + " is not an OperatorUI");
                }
            } catch (ClassNotFoundException e) {
                problems.add("operatorUIClass not found: " + uiClass);
            }
            if (opName == null || GPF.getDefaultInstance().getOperatorSpiRegistry().getOperatorSpi(opName) == null) {
                problems.add("operatorName is not a registered operator alias: " + opName + " (UI " + uiClass + ")");
            }
        }
        assertTrue(String.join("\n", problems), problems.isEmpty());
    }

    private static String attr(final Element file, final String name) {
        final NodeList attrs = file.getElementsByTagName("attr");
        for (int i = 0; i < attrs.getLength(); i++) {
            final Element a = (Element) attrs.item(i);
            if (name.equals(a.getAttribute("name"))) {
                return a.getAttribute("stringvalue");
            }
        }
        return null;
    }
}
