package org.csa.rstb.polarimetric.gpf.support;

public interface PolarimetricProcessor {

    default boolean contains(String name, String pre, String post) {
        return name.startsWith(pre) && name.endsWith(post);
    }
}
