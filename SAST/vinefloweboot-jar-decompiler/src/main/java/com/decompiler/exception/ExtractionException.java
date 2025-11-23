package com.decompiler.exception;

public class ExtractionException extends DecompilerException {

    public ExtractionException(String message) {
        super(message);
    }

    public ExtractionException(String message, Throwable cause) {
        super(message, cause);
    }

    public ExtractionException(Throwable cause) {
        super(cause);
    }
}