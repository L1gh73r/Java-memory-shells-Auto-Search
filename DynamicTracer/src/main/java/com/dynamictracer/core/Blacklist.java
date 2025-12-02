package com.dynamictracer.core;

import java.util.Arrays;
import java.util.List;

public class Blacklist {
    public static final List<String> CLASS_BLACKLIST = Arrays.asList(
            "java.util.Scanner",
            "java.lang.ProcessBuilder"
            // more black list
    );
}