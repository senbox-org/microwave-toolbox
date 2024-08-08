package eu.esa.microwave.about;

import com.bc.ceres.annotation.STTM;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TestAboutBox {

    @Test
    @STTM("SNAP-3784")
    public void testGetReleaseNotesURLString() {

        String releaseNotesUrl = MicrowavetbxAboutBox.getReleaseNotesURLString("1.0.0");
        assertEquals("https://github.com/senbox-org/microwave-toolbox/blob/master/ReleaseNotes.md", releaseNotesUrl);

        releaseNotesUrl = MicrowavetbxAboutBox.getReleaseNotesURLString("10.0.0");
        assertEquals("https://step.esa.int/main/wp-content/releasenotes/Microwave/Microwave_10.0.0.html", releaseNotesUrl);
    }
}
