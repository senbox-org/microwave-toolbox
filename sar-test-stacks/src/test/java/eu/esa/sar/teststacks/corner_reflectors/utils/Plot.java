package eu.esa.sar.teststacks.corner_reflectors.utils;

import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import org.knowm.xchart.XYSeries;
import org.knowm.xchart.style.Styler;
import org.knowm.xchart.style.markers.SeriesMarkers;

import java.io.IOException;

public class Plot {

    private final XYChart chart;

    public Plot(String title) {
        this.chart = new XYChartBuilder()
                .width(800)
                .height(600)
                .title(title)
                .xAxisTitle("xShift")
                .yAxisTitle("yShift")
                .build();

        // Customize the chart style
        chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Scatter);
        chart.getStyler().setChartTitleVisible(true);
        chart.getStyler().setLegendPosition(Styler.LegendPosition.InsideNW);
        chart.getStyler().setMarkerSize(5);

        chart.getStyler().setXAxisMin(-200.0);
        chart.getStyler().setXAxisMax(200.0);
        chart.getStyler().setYAxisMin(-200.0);
        chart.getStyler().setYAxisMax(200.0);
    }

    public void addData(double[] xData, double[] yData) {
        XYSeries series = chart.addSeries("Data Points", xData, yData);
        series.setMarker(SeriesMarkers.CIRCLE);
    }

    public void saveAsPng(String filePath) throws IOException {
        BitmapEncoder.saveBitmap(chart, filePath, BitmapEncoder.BitmapFormat.PNG);
    }
}
