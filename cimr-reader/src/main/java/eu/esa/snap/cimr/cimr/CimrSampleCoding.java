package eu.esa.snap.cimr.cimr;


public class CimrSampleCoding {


    public enum Type {
        INDEX
    }

    private Type type;
    private String name;
    private CimrSampleCodingEntry[] entries;


    public CimrSampleCoding() {
        this.type = null;
        this.name = "";
        this.entries = new CimrSampleCodingEntry[0];
    }

    public CimrSampleCoding(Type type, String name, CimrSampleCodingEntry[] entries) {
        this.type = type;
        this.name = name;
        this.entries = entries;
    }


    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public CimrSampleCodingEntry[] getEntries() {
        return entries;
    }

    public void setEntries(CimrSampleCodingEntry[] entries) {
        this.entries = entries;
    }
}
