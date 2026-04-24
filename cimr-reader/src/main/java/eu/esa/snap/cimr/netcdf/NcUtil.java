package eu.esa.snap.cimr.netcdf;

import ucar.nc2.Group;
import ucar.nc2.NetcdfFile;
import ucar.nc2.Variable;


public class NcUtil {

    public static Group findGroupOrThrow(NetcdfFile ncFile, String path) {
        Group g = ncFile.findGroup(path);
        if (g == null) {
            throw new IllegalArgumentException("Group not found: " + path);
        }
        return g;
    }

    public static Variable findVarOrThrow(Group group, String name) {
        Variable v = group.findVariable(name);
        if (v == null) {
            throw new IllegalArgumentException(
                    "Variable '" + name + "' not found in group " + group.getFullName()
            );
        }
        return v;
    }
}
