package eu.esa.sar.io.stac.internal;

import org.json.simple.JSONObject;

public class Modifier implements STACUtils {

    public DownloadModifier planetaryComputer() {
        String signingURL = "https://planetarycomputer.microsoft.com/api/sas/v1/sign?href=";
        return input -> {
            JSONObject signedObject = getJSONFromURL(signingURL + input);
            return (String) signedObject.get("href");
        };
    }

}
