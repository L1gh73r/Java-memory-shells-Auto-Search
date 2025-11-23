package com.decompiler.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ZipUtils {
    private static final Logger logger = LoggerFactory.getLogger(ZipUtils.class);
    private static final int BUFFER_SIZE = 8192;

    public static void unzip(Path jarFile, Path targetDir) throws IOException {
        FileUtils.ensureDirectory(targetDir);

        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(jarFile)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path entryPath = targetDir.resolve(entry.getName());

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());

                    try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(entryPath))) {
                        byte[] buffer = new byte[BUFFER_SIZE];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            os.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }

        logger.info("解压完成: {}", targetDir);
    }

    public static boolean isJarFile(Path file) {
        return file != null &&
                Files.exists(file) &&
                file.getFileName().toString().toLowerCase().endsWith(".jar");
    }
}