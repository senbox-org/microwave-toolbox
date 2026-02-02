package eu.esa.snap.cimr;

import eu.esa.snap.cimr.cimr.*;
import eu.esa.snap.cimr.grid.CimrGeometry;
import eu.esa.snap.cimr.grid.CimrGeometryBand;
import eu.esa.snap.cimr.grid.CimrGrid;
import eu.esa.snap.cimr.netcdf.NetcdfCimrBandFactory;
import eu.esa.snap.cimr.netcdf.NetcdfCimrFootprintFactory;
import eu.esa.snap.cimr.netcdf.NetcdfCimrGeometryFactory;
import org.esa.snap.core.datamodel.GeoPos;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import ucar.ma2.InvalidRangeException;
import ucar.nc2.NetcdfFile;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;


@RunWith(MockitoJUnitRunner.class)
public class CimrReaderContextFootprintTest {

    @Mock
    private NetcdfFile ncFile;

    @Mock
    private CimrDescriptorSet descriptorSet;

    @Mock
    private CimrGrid cimrGrid;

    @Mock
    private NetcdfCimrGeometryFactory geometryFactory;

    @Mock
    private NetcdfCimrBandFactory bandFactory;

    @Mock
    private NetcdfCimrFootprintFactory footprintFactory;

    @Mock
    private CimrBandDescriptor mainDesc;

    @Mock
    private CimrBandDescriptor minorDesc;

    @Mock
    private CimrBandDescriptor majorDesc;

    @Mock
    private CimrBandDescriptor angleDesc;

    @Mock
    private CimrGeometry mainGeom;

    @Mock
    private CimrGeometry minorGeom;

    @Mock
    private CimrGeometry majorGeom;

    @Mock
    private CimrGeometry angleGeom;

    @Mock
    private CimrGeometryBand mainGeomBand;

    @Mock
    private CimrGeometryBand minorGeomBand;

    @Mock
    private CimrGeometryBand majorGeomBand;

    @Mock
    private CimrGeometryBand angleGeomBand;

    private CimrReaderContext context;

    @Before
    public void setUp() throws Exception {
        context = new CimrReaderContext(ncFile, descriptorSet, cimrGrid, geometryFactory, bandFactory);

        Field ff = CimrReaderContext.class.getDeclaredField("footprintFactory");
        ff.setAccessible(true);
        ff.set(context, footprintFactory);

        when(mainDesc.getFootprintVars()).thenReturn(new String[]{
                "FOOT_MINOR",
                "FOOT_MAJOR",
                "FOOT_ANGLE"
        });
        when(mainDesc.getBand()).thenReturn(CimrFrequencyBand.C_BAND);
        when(mainDesc.getFeedIndex()).thenReturn(0);

        when(descriptorSet.getTpVariableByName("FOOT_MINOR")).thenReturn(minorDesc);
        when(descriptorSet.getTpVariableByName("FOOT_MAJOR")).thenReturn(majorDesc);
        when(descriptorSet.getTpVariableByName("FOOT_ANGLE")).thenReturn(angleDesc);

        when(minorDesc.getBand()).thenReturn(CimrFrequencyBand.C_BAND);
        when(minorDesc.getFeedIndex()).thenReturn(1);
        when(minorDesc.getValueVarName()).thenReturn("FOOT_MINOR");

        when(majorDesc.getBand()).thenReturn(CimrFrequencyBand.C_BAND);
        when(majorDesc.getFeedIndex()).thenReturn(1);
        when(majorDesc.getValueVarName()).thenReturn("FOOT_MAJOR");

        when(angleDesc.getBand()).thenReturn(CimrFrequencyBand.C_BAND);
        when(angleDesc.getFeedIndex()).thenReturn(1);
        when(angleDesc.getValueVarName()).thenReturn("FOOT_ANGLE");

        when(geometryFactory.getOrCreateGeometry(mainDesc)).thenReturn(mainGeom);
        when(geometryFactory.getOrCreateGeometry(minorDesc)).thenReturn(minorGeom);
        when(geometryFactory.getOrCreateGeometry(majorDesc)).thenReturn(majorGeom);
        when(geometryFactory.getOrCreateGeometry(angleDesc)).thenReturn(angleGeom);

        when(bandFactory.createGeometryBand(eq(mainDesc), eq(mainGeom))).thenReturn(mainGeomBand);
        when(bandFactory.createGeometryBand(eq(minorDesc), eq(minorGeom))).thenReturn(minorGeomBand);
        when(bandFactory.createGeometryBand(eq(majorDesc), eq(majorGeom))).thenReturn(majorGeomBand);
        when(bandFactory.createGeometryBand(eq(angleDesc), eq(angleGeom))).thenReturn(angleGeomBand);
    }

    @Test
    public void testGetOrCreateFootprints_createsFromDependenciesAndCaches() throws InvalidRangeException, IOException {
        CimrFootprintShape dummyFp = new CimrFootprintShape(new GeoPos(10.f, 20.f), 45.0, 1000.0, 2000.0);
        List<CimrFootprintShape> expectedList = Collections.singletonList(dummyFp);

        when(footprintFactory.createFootprintShapes(mainGeomBand, minorGeomBand, majorGeomBand, angleGeomBand))
                .thenReturn(expectedList);

        CimrFootprints first = context.getOrCreateFootprints(mainDesc);

        assertSame(expectedList, first.getShapes());

        verify(descriptorSet).getTpVariableByName("FOOT_MINOR");
        verify(descriptorSet).getTpVariableByName("FOOT_MAJOR");
        verify(descriptorSet).getTpVariableByName("FOOT_ANGLE");

        verify(geometryFactory).getOrCreateGeometry(mainDesc);
        verify(geometryFactory).getOrCreateGeometry(minorDesc);
        verify(geometryFactory).getOrCreateGeometry(majorDesc);
        verify(geometryFactory).getOrCreateGeometry(angleDesc);

        verify(bandFactory).createGeometryBand(mainDesc, mainGeom);
        verify(bandFactory).createGeometryBand(minorDesc, minorGeom);
        verify(bandFactory).createGeometryBand(majorDesc, majorGeom);
        verify(bandFactory).createGeometryBand(angleDesc, angleGeom);

        verify(footprintFactory, times(1))
                .createFootprintShapes(mainGeomBand, minorGeomBand, majorGeomBand, angleGeomBand);

        CimrFootprints second = context.getOrCreateFootprints(mainDesc);

        assertSame(first.getShapes(), second.getShapes());
        assertNotSame(first, second);
        verify(footprintFactory, times(1)).createFootprintShapes(mainGeomBand, minorGeomBand, majorGeomBand, angleGeomBand);
        verify(footprintFactory, times(2)).getFootprintValues(mainGeomBand);

        assertNotSame(first.getValues(), second.getValues());
    }

    @Test
    public void testGetOrCreateFootprints_usesFootprintKeyBasedOnBandAndFeed() {
        CimrBandDescriptor otherDesc = mock(CimrBandDescriptor.class);

        when(otherDesc.getBand()).thenReturn(CimrFrequencyBand.C_BAND);
        when(otherDesc.getFeedIndex()).thenReturn(0);

        CimrFootprintShape fp = new CimrFootprintShape(new GeoPos(0.f, 0.f), 0.0, 500.0, 1000.0);
        List<CimrFootprintShape> expected = Collections.singletonList(fp);

        when(footprintFactory.createFootprintShapes(
                mainGeomBand, minorGeomBand, majorGeomBand, angleGeomBand))
                .thenReturn(expected);

        List<CimrFootprintShape> list1 = context.getOrCreateFootprints(mainDesc).getShapes();
        List<CimrFootprintShape> list2 = context.getOrCreateFootprints(otherDesc).getShapes();

        assertSame(list1, list2);

        verify(footprintFactory, times(1)).createFootprintShapes(mainGeomBand, minorGeomBand, majorGeomBand, angleGeomBand);
    }
}
