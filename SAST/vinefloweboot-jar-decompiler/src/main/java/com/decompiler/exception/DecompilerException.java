package com.decompiler.exception;

public class DecompilerException extends RuntimeException {

    public DecompilerException(String message) {
        super(message);
    }

    public DecompilerException(String message, Throwable cause) {
        super(message, cause);
    }

    public DecompilerException(Throwable cause) {
        super(cause);
    }
}