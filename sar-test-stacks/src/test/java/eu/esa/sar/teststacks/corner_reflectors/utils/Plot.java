package eu.esa.sar.teststacks.corner_reflectors.utils;

import org.esa.snap.core.datamodel.PixelPos;
import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import org.knowm.xchart.XYSeries;
import org.knowm.xchart.style.Styler;
import org.knowm.xchart.style.markers.SeriesMarkers;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

public class Plot {

    private final XYChart chart;
    private double range = 150.0;

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

        chart.getStyler().setXAxisMin(-range);
        chart.getStyler().setXAxisMax(range);
        chart.getStyler().setYAxisMin(-range);
        chart.getStyler().setYAxisMax(range);
    }

    public void addData(double[] xData, double[] yData) {
        XYSeries series = chart.addSeries("Data Points", xData, yData);
        series.setMarker(SeriesMarkers.CIRCLE);
    }

    public void saveAsPng(String filePath) throws IOException {
        BitmapEncoder.saveBitmap(chart, filePath, BitmapEncoder.BitmapFormat.PNG);
    }

    public static void plotAndSaveImage(double[][] imageData, PixelPos expected, PixelPos detected, File file) {
        int width = imageData[0].length;
        int height = imageData.length;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();

        // Find min/max for normalization
        double minVal = Double.MAX_VALUE;
        double maxVal = Double.MIN_VALUE;
        for (double[] imageDatum : imageData) {
            for (int x = 0; x < width; x++) {
                if (imageDatum[x] < minVal) minVal = imageDatum[x];
                if (imageDatum[x] > maxVal) maxVal = imageDatum[x];
            }
        }

        // Draw image data
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int gray = (int) (255 * (imageData[y][x] - minVal) / (maxVal - minVal));
                gray = Math.max(0, Math.min(255, gray)); // Clamp values
                image.setRGB(x, y, new Color(gray, gray, gray).getRGB());
            }
        }

        // Draw markers
        if (expected != null) {
            g2d.setColor(Color.BLUE); // Expected in blue
            g2d.draw(new Ellipse2D.Double(expected.x - 2, expected.y - 2, 4, 4));
            g2d.drawString(String.format("Exp: (%.2f, %.2f)", expected.x, expected.y), (int)expected.x + 5, (int)expected.y);
        }
        if (detected != null) {
            g2d.setColor(Color.RED); // Detected in red
            g2d.draw(new Ellipse2D.Double(detected.x - 2, detected.y - 2, 4, 4));
            g2d.drawString(String.format("Det: (%.2f, %.2f)", detected.x, detected.y), (int)detected.x + 5, (int)detected.y + 10);
        }

        g2d.dispose();

        try {
            ImageIO.write(image, "PNG", file);
        } catch (IOException e) {
            System.err.println("Error saving image: " + e.getMessage());
        }
    }
}
