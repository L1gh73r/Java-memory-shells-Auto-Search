package com.decompiler.util;

import java.nio.file.Files;
import java.nio.file.Path;

public class PathUtils {


    public static Path resolveLibDirectory(Path extractedDir) {
        Path bootInfLib = extractedDir.resolve("BOOT-INF").resolve("lib");
        if (Files.exists(bootInfLib)) {
            return bootInfLib;
        }

        Path webInfLib = extractedDir.resolve("WEB-INF").resolve("lib");
        if (Files.exists(webInfLib)) {
            return webInfLib;
        }

        return null;
    }

    public static Path resolveClassesDirectory(Path extractedDir) {
        Path bootInfClasses = extractedDir.resolve("BOOT-INF").resolve("classes");
        if (Files.exists(bootInfClasses)) {
            return bootInfClasses;
        }

        Path webInfClasses = extractedDir.resolve("WEB-INF").resolve("classes");
        if (Files.exists(webInfClasses)) {
            return webInfClasses;
        }

        return null;
    }

    public static String getJarBaseName(Path jarPath) {
        String fileName = jarPath.getFileName().toString();
        if (fileName.endsWith(".jar")) {
            return fileName.substring(0, fileName.length() - 4);
        }
        return fileName;
    }
}