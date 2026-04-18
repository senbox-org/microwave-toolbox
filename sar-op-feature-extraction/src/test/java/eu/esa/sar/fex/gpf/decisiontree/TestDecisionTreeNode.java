/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.fex.gpf.decisiontree;

import org.junit.Test;

import java.awt.Point;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link DecisionTreeNode}.
 */
public class TestDecisionTreeNode {

    @Test
    public void testDefaultConstructorProducesLeaf() {
        final DecisionTreeNode node = new DecisionTreeNode();
        assertTrue(node.isLeaf());
        assertFalse(node.isTwig());
        assertNull(node.getTrueNode());
        assertNull(node.getFalseNode());
        assertEquals("", node.getExpression());
    }

    @Test
    public void testSetExpressionStoresValueAndInvalidatesPosition() {
        final DecisionTreeNode node = new DecisionTreeNode();
        node.setPosition(1, 2);
        assertTrue(node.isPositionSet());

        node.setExpression("x > 0");

        assertEquals("x > 0", node.getExpression());
        assertFalse("setExpression must invalidate position", node.isPositionSet());
    }

    @Test
    public void testAddBranchMakesParentNonLeafAndConnectsChildren() {
        final DecisionTreeNode root = new DecisionTreeNode();
        final DecisionTreeNode yes = new DecisionTreeNode();
        final DecisionTreeNode no = new DecisionTreeNode();

        root.addBranch(yes, no);

        assertFalse(root.isLeaf());
        assertTrue("root with two leaf children is a twig", root.isTwig());
        assertSame(yes, root.getTrueNode());
        assertSame(no, root.getFalseNode());
    }

    @Test
    public void testDeleteBranchReturnsToLeaf() {
        final DecisionTreeNode root = new DecisionTreeNode();
        root.addBranch(new DecisionTreeNode(), new DecisionTreeNode());
        assertFalse(root.isLeaf());

        root.deleteBranch();

        assertTrue(root.isLeaf());
        assertNull(root.getTrueNode());
        assertNull(root.getFalseNode());
    }

    @Test
    public void testToArrayCapturesAllNodesAndDepthMetrics() {
        // Build:   root
        //         /    \
        //       a       b
        //     /  \
        //    c    d
        final DecisionTreeNode root = new DecisionTreeNode();
        root.setExpression("r");
        final DecisionTreeNode a = new DecisionTreeNode();
        a.setExpression("a");
        final DecisionTreeNode b = new DecisionTreeNode();
        final DecisionTreeNode c = new DecisionTreeNode();
        final DecisionTreeNode d = new DecisionTreeNode();
        root.addBranch(a, b);
        a.addBranch(c, d);

        final DecisionTreeNode[] nodes = root.toArray();

        assertNotNull(nodes);
        assertEquals(5, nodes.length);
        assertEquals("depth = longest path from root", 2, root.getDepth());
        // breadth counts leaves - 1 (per implementation).
        assertEquals(2, root.getBreadth());
        assertTrue("total expression length tracked across paths",
                root.getExpressionLength() >= 2);
    }

    @Test
    public void testMoveTreeShiftsSubtreePositions() {
        final DecisionTreeNode root = new DecisionTreeNode();
        final DecisionTreeNode yes = new DecisionTreeNode();
        final DecisionTreeNode no = new DecisionTreeNode();
        root.addBranch(yes, no);
        root.setPosition(10, 20);
        yes.setPosition(5, 30);
        no.setPosition(15, 30);

        root.moveTree(3, -5);

        assertEquals(13, root.getX());
        assertEquals(15, root.getY());
        assertEquals(8, yes.getX());
        assertEquals(25, yes.getY());
        assertEquals(18, no.getX());
        assertEquals(25, no.getY());
    }

    @Test
    public void testSetDimensionAndIsWithin() {
        final DecisionTreeNode node = new DecisionTreeNode();
        node.setPosition(10, 20);
        node.setDimension(40, 30);

        assertEquals(10, node.getX());
        assertEquals(20, node.getY());
        assertEquals(40, node.getWidth());
        assertEquals(30, node.getHeight());
        assertTrue(node.isWithin(new Point(20, 30)));
        assertFalse("boundary is exclusive", node.isWithin(new Point(10, 20)));
        assertFalse(node.isWithin(new Point(100, 100)));
    }

    @Test
    public void testCreateDefaultTreeIsTwig() {
        final DecisionTreeNode tree = DecisionTreeNode.createDefaultTree();
        assertNotNull(tree);
        assertFalse(tree.isLeaf());
        assertTrue(tree.isTwig());
    }

    @Test
    public void testToStringContainsIdAndExpression() {
        final DecisionTreeNode root = new DecisionTreeNode();
        root.setExpression("alpha");
        root.addBranch(new DecisionTreeNode(), new DecisionTreeNode());
        root.toArray(); // assigns ids
        final String repr = root.toString();
        assertTrue(repr.contains("id=node0"));
        assertTrue(repr.contains("expression=alpha"));
    }

    @Test
    public void testParseAndConnectNodesRebuildsLinks() throws Exception {
        final DecisionTreeNode root = new DecisionTreeNode();
        root.setExpression("root-expr");
        final DecisionTreeNode yes = new DecisionTreeNode();
        yes.setExpression("yes-expr");
        final DecisionTreeNode no = new DecisionTreeNode();
        no.setExpression("no-expr");
        root.addBranch(yes, no);

        final DecisionTreeNode[] serialized = root.toArray();
        assertEquals(3, serialized.length);

        // Round-trip through parse / connectNodes.
        final DecisionTreeNode[] rebuilt = new DecisionTreeNode[serialized.length];
        for (int i = 0; i < serialized.length; i++) {
            rebuilt[i] = DecisionTreeNode.parse(serialized[i].toString());
        }
        DecisionTreeNode.connectNodes(rebuilt);

        // The first parsed node should match root's id "node0".
        final DecisionTreeNode parsedRoot = DecisionTreeNode.findNode(rebuilt, "node0");
        assertNotNull(parsedRoot);
        assertEquals("root-expr", parsedRoot.getExpression());
        assertNotNull("root must have a true branch after reconnection",
                parsedRoot.getTrueNode());
        assertNotNull("root must have a false branch after reconnection",
                parsedRoot.getFalseNode());
    }

    @Test
    public void testFindNodeReturnsNullWhenIdMissing() throws Exception {
        final DecisionTreeNode root = new DecisionTreeNode();
        root.addBranch(new DecisionTreeNode(), new DecisionTreeNode());
        final DecisionTreeNode[] array = root.toArray();

        assertNull(DecisionTreeNode.findNode(array, "non-existent"));
    }

    @Test
    public void testIsConnectedTrueForFreshlyBuiltNode() {
        final DecisionTreeNode node = new DecisionTreeNode();
        // trueNodeID is null, trueNode is null, so (trueNode == null && trueNodeID != null) is false → connected.
        assertTrue(node.isConnected());
    }

    @Test
    public void testToArrayIsMemoizedUntilInvalidatedByBranchChange() {
        final DecisionTreeNode root = new DecisionTreeNode();
        root.addBranch(new DecisionTreeNode(), new DecisionTreeNode());
        final DecisionTreeNode[] first = root.toArray();
        final DecisionTreeNode[] second = root.toArray();
        assertSame("second call returns memoized array", first, second);

        // Refresh and verify contents are equivalent (array still length 3).
        root.update();
        final DecisionTreeNode[] refreshed = root.toArray();
        assertEquals(first.length, refreshed.length);
        assertArrayEquals(first, refreshed);
    }
}
