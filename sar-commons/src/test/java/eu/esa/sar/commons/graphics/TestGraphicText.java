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
package eu.esa.sar.commons.graphics;

import org.junit.Before;
import org.junit.Test;

import java.awt.*;
import java.awt.image.BufferedImage;

import static org.junit.Assert.*;

public class TestGraphicText {

    private Graphics2D g2d;

    @Before
    public void setUp() {
        BufferedImage image = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
        g2d = image.createGraphics();
    }

    // --- padString ---

    @Test
    public void testPadStringNarrowText() {
        String result = GraphicText.padString("42", 10);
        // padString pads to width, then appends text
        // width - text.length() + 1 spaces, then text
        assertTrue(result.endsWith("42"));
        assertTrue(result.length() > "42".length());
        assertTrue(result.startsWith(" "));
    }

    @Test
    public void testPadStringExactWidth() {
        String result = GraphicText.padString("abcde", 5);
        assertEquals("abcde", result);
    }

    @Test
    public void testPadStringLongerThanWidth() {
        String result = GraphicText.padString("abcdefgh", 5);
        assertEquals("abcdefgh", result);
    }

    @Test
    public void testPadStringNaN() {
        String result = GraphicText.padString("NaN", 10);
        assertEquals("NaN", result);
    }

    @Test
    public void testPadStringNaNIgnoresCase() {
        String result = GraphicText.padString("nan", 10);
        assertEquals("nan", result);
    }

    @Test
    public void testPadStringWidth1() {
        String result = GraphicText.padString("A", 1);
        assertEquals("A", result);
    }

    @Test
    public void testPadStringEmpty() {
        String result = GraphicText.padString("", 5);
        assertNotNull(result);
        assertTrue(result.length() > 0);
        assertTrue(result.trim().isEmpty());
    }

    // --- setHighQuality ---

    @Test
    public void testSetHighQuality() {
        GraphicText.setHighQuality(g2d);
        assertEquals(RenderingHints.VALUE_ANTIALIAS_ON,
                g2d.getRenderingHint(RenderingHints.KEY_ANTIALIASING));
        assertEquals(RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
                g2d.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING));
        assertEquals(RenderingHints.VALUE_RENDER_QUALITY,
                g2d.getRenderingHint(RenderingHints.KEY_RENDERING));
    }

    // --- shadowText ---

    @Test
    public void testShadowTextSetsColor() {
        GraphicText.shadowText(g2d, Color.RED, "test", 10, 20);
        // After drawing, the last color set should be the foreground color
        assertEquals(Color.RED, g2d.getColor());
    }

    // --- outlineText ---

    @Test
    public void testOutlineTextSetsColor() {
        GraphicText.outlineText(g2d, Color.BLUE, "test", 10, 20);
        assertEquals(Color.BLUE, g2d.getColor());
    }

    // --- highlightText ---

    @Test
    public void testHighlightTextSetsColor() {
        GraphicText.highlightText(g2d, Color.GREEN, "test", 10, 20, Color.YELLOW);
        assertEquals(Color.GREEN, g2d.getColor());
    }

    @Test
    public void testHighlightTextDoesNotThrow() {
        // Verify methods don't throw with various inputs
        GraphicText.shadowText(g2d, Color.WHITE, "", 0, 0);
        GraphicText.outlineText(g2d, Color.WHITE, "long text here", 100, 100);
        GraphicText.highlightText(g2d, Color.WHITE, "text", -5, -5, Color.GRAY);
    }
}
