/*
 * Copyright (C) 2023 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.microwave.about;

import com.bc.ceres.core.runtime.Version;
import org.esa.snap.rcp.about.AboutBox;
import org.esa.snap.rcp.util.BrowserUtils;
import org.openide.modules.ModuleInfo;
import org.openide.modules.Modules;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

@AboutBox(displayName = "Microwave", position = 10)
public class MicrowavetbxAboutBox extends JPanel {

    private final static String defaultReleaseNotesHTTP = "https://github.com/senbox-org/microwave-toolbox/blob/master/ReleaseNotes.md";
    private final static String stepReleaseNotesHTTP = "https://step.esa.int/main/wp-content/releasenotes/Microwave/Microwave_<version>.html";


    public MicrowavetbxAboutBox() {
        super(new BorderLayout(4, 4));
        setBorder(new EmptyBorder(4, 4, 4, 4));
        ImageIcon aboutImage = new ImageIcon(MicrowavetbxAboutBox.class.getResource("about_microwave_tbx.jpg"));
        JLabel iconLabel = new JLabel(aboutImage);
        add(iconLabel, BorderLayout.CENTER);
        add(createVersionPanel(), BorderLayout.SOUTH);
    }

    private JPanel createVersionPanel() {
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ENGLISH);
        int year = utc.get(Calendar.YEAR);
        JLabel copyRightLabel = new JLabel("<html><b>© 2018-" + year + " SkyWatch and contributors</b>", SwingConstants.CENTER);

        final ModuleInfo moduleInfo = Modules.getDefault().ownerOf(MicrowavetbxAboutBox.class);
        JLabel versionLabel = new JLabel("<html><b>Microwave Toolbox version " + moduleInfo.getImplementationVersion() + "</b>", SwingConstants.CENTER);

        Version specVersion = Version.parseVersion(moduleInfo.getSpecificationVersion().toString());
        String versionString = String.format("%s.%s.%s", specVersion.getMajor(), specVersion.getMinor(), specVersion.getMicro());
        String changelogUrl = getReleaseNotesURLString(versionString);
        final JLabel releaseNoteLabel = new JLabel("<html><a href=\"" + changelogUrl + "\">Release Notes</a>", SwingConstants.CENTER);
        releaseNoteLabel.setCursor(new Cursor(Cursor.HAND_CURSOR));
        releaseNoteLabel.addMouseListener(new BrowserUtils.URLClickAdaptor(changelogUrl));

        final JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.add(copyRightLabel);
        mainPanel.add(versionLabel);
        mainPanel.add(releaseNoteLabel);
        return mainPanel;
    }

    static String getReleaseNotesURLString(String versionString){
        String changelogUrl = stepReleaseNotesHTTP.replace("<version>", versionString);
        try {
            URL url = new URL(changelogUrl);
            HttpURLConnection huc = (HttpURLConnection) url.openConnection();
            huc.setRequestMethod("HEAD");

            int responseCode = huc.getResponseCode();
            if(responseCode != HttpURLConnection.HTTP_OK) {
                changelogUrl = defaultReleaseNotesHTTP;
            }
        } catch (IOException e) {
            changelogUrl = defaultReleaseNotesHTTP;
        }
        return changelogUrl;
    }
}
