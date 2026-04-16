package eu.esa.sar.commons.products;

import eu.esa.sar.commons.product.Missions;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class TestMissions {

    @Test
    public void testGetList() {
        String[] list = Missions.getList();
        assertNotNull(list);
        assertTrue(list.length > 0);
    }

    @Test
    public void testGetListContainsKnownMissions() {
        String[] list = Missions.getList();
        Set<String> missions = new HashSet<>(Arrays.asList(list));

        assertTrue(missions.contains(Missions.SENTINEL1));
        assertTrue(missions.contains(Missions.SENTINEL2));
        assertTrue(missions.contains(Missions.RADARSAT2));
        assertTrue(missions.contains(Missions.RCM));
        assertTrue(missions.contains(Missions.ICEYE));
        assertTrue(missions.contains(Missions.CAPELLA));
        assertTrue(missions.contains(Missions.NISAR));
        assertTrue(missions.contains(Missions.SAOCOM));
    }

    @Test
    public void testGetListFirstAndLast() {
        String[] list = Missions.getList();
        assertEquals(Missions.CAPELLA, list[0]);
        assertEquals(Missions.WORLDVIEW4, list[list.length - 1]);
    }

    @Test
    public void testGetListNoDuplicates() {
        String[] list = Missions.getList();
        Set<String> unique = new HashSet<>(Arrays.asList(list));
        assertEquals(list.length, unique.size());
    }

    @Test
    public void testMissionConstants() {
        assertEquals("Sentinel-1", Missions.SENTINEL1);
        assertEquals("Sentinel-2", Missions.SENTINEL2);
        assertEquals("Sentinel-3", Missions.SENTINEL3);
        assertEquals("RS1", Missions.RADARSAT1);
        assertEquals("RS2", Missions.RADARSAT2);
        assertEquals("RCM", Missions.RCM);
        assertEquals("Iceye", Missions.ICEYE);
        assertEquals("Capella", Missions.CAPELLA);
        assertEquals("NISAR", Missions.NISAR);
        assertEquals("SAOCOM", Missions.SAOCOM);
    }

    @Test
    public void testGetListSize() {
        String[] list = Missions.getList();
        assertEquals(24, list.length);
    }

    @Test
    public void testGetListReturnsNewArray() {
        String[] list1 = Missions.getList();
        String[] list2 = Missions.getList();
        assertNotSame(list1, list2);
        assertArrayEquals(list1, list2);
    }
}
