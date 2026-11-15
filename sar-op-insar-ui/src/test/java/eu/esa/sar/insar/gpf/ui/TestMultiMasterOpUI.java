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

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.swing.SwingUtilities;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * The outputFolder of a loaded graph must survive the dialog round trip (initParameters then
 * updateParameters); user.home is only the fallback for an empty value.
 */
public class TestMultiMasterOpUI {

    private static final String ALIAS = "MultiMasterStackGenerator";

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    /**
     * Runs on the EDT, as in Desktop: JFileChooser loads directories in the background and posts
     * model updates to the EDT, so changing its directory from another thread races those updates
     * (IndexOutOfBoundsException in DefaultRowSorter).
     */
    private static Object roundTrip(final Map<String, Object> paramMap) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final MultiMasterOpUI ui = new MultiMasterOpUI();
            ui.CreateOpTab(ALIAS, paramMap, null);
            ui.updateParameters();
        });
        return paramMap.get("outputFolder");
    }

    @Test
    public void graphOutputFolderIsKept() throws Exception {
        final String folder = tmp.newFolder("pairs").getPath();
        final Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("outputFolder", folder);
        assertEquals(folder, roundTrip(paramMap));
    }

    @Test
    public void emptyOutputFolderFallsBackToUserHome() throws Exception {
        final Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("outputFolder", "");
        assertEquals(new File(System.getProperty("user.home")).getPath(), roundTrip(paramMap));
    }

    @Test
    public void freshDialogFallsBackToUserHome() throws Exception {
        assertEquals(new File(System.getProperty("user.home")).getPath(), roundTrip(new HashMap<>()));
    }

    @Test
    public void dialogsDoNotShareTheirFolder() throws Exception {
        final String folder = tmp.newFolder("first").getPath();
        final Map<String, Object> first = new HashMap<>();
        first.put("outputFolder", folder);
        assertEquals(folder, roundTrip(first));
        // a second, fresh dialog must not inherit the first dialog's selection
        assertEquals(new File(System.getProperty("user.home")).getPath(), roundTrip(new HashMap<>()));
    }

    /**
     * The menu graph must ship an empty outputFolder: GraphNode copies the graph's value into the
     * paramMap, so any placeholder there (it was /tmp/testing) hides the user.home fallback.
     */
    @Test
    public void menuGraphShipsEmptyOutputFolder() throws Exception {
        final String resource = "/eu/esa/sar/insar/graphs/internal/coregistration/MultiMasterStackGenerator.xml";
        final Document doc;
        try (InputStream in = TestMultiMasterOpUI.class.getResourceAsStream(resource)) {
            assertNotNull(resource, in);
            doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
        }
        Element params = null;
        final NodeList nodes = doc.getElementsByTagName("node");
        for (int i = 0; i < nodes.getLength(); i++) {
            final Element node = (Element) nodes.item(i);
            final NodeList ops = node.getElementsByTagName("operator");
            if (ops.getLength() > 0 && ALIAS.equals(ops.item(0).getTextContent().trim())) {
                params = (Element) node.getElementsByTagName("parameters").item(0);
            }
        }
        assertNotNull("graph has no " + ALIAS + " node", params);
        final NodeList folder = params.getElementsByTagName("outputFolder");
        assertEquals("graph must declare outputFolder", 1, folder.getLength());
        assertEquals("", folder.item(0).getTextContent().trim());
    }
}
