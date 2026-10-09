package eu.esa.snap.cimr.dddb.descriptor;


public class CimrSampleCodingEntry {


    private String name;
    private int value;
    private String description;


    public CimrSampleCodingEntry() {
        this.name = "";
        this.value = 0;
        this.description = "";
    }

    public CimrSampleCodingEntry(String name, int value, String description) {
        this.name = name;
        this.value = value;
        this.description = description;
    }


    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
