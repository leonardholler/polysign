package com.polysign.api.v1;

/**
 * Thrown when a {@code cursor} query parameter cannot be decoded.
 * Caught by {@link com.polysign.api.GlobalExceptionHandler} and returned as HTTP 400.
 */
public class InvalidCursorException extends RuntimeException {
    public InvalidCursorException(String message) {
        super(message);
    }
}
