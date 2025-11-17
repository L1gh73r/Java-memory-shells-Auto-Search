package com.dynamictracer.core;

public interface ClassModifier {
    byte[] modify(ClassLoader loader, String className, Class<?> classBeingRedefined, byte[] classfileBuffer);
}