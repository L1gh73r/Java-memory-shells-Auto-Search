package com.dynamictracer.utils;

import java.util.HashSet;
import java.util.Set;

public class SpringBootApplicationFinder {
    private static final Set<String> springBootApplicationClasses = new HashSet<>();

    public static void addSpringBootApplicationClass(String className) {
        springBootApplicationClasses.add(className);
    }

    public static Set<String> getSpringBootApplicationClasses() {
        return springBootApplicationClasses;
    }
}
