package com.dynamictracer.exception;

public class BlacklistedClassException extends RuntimeException {
    public BlacklistedClassException(String className) {
        super("Attempted to load blacklisted class: " + className);
    }
}
