package org.csa.rstb.polarimetric.gpf.support;

public interface PolarimetricProcessor {

    /**
     * True when the band name starts with {@code pre} (e.g. "i_") and carries the polarisation token {@code post}
     * (e.g. "_HH") either at the end ("i_HH") or followed by a stack suffix ("i_HH_ref_13Jun2012", "i_HH_sec1_07Jul2012").
     */
    default boolean contains(String name, String pre, String post) {
        return name.startsWith(pre) && (name.endsWith(post) || name.contains(post + '_'));
    }
}
