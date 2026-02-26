package com.jparlant.service.exception;

public class IntentNotMatchedException extends RuntimeException {
    public IntentNotMatchedException(String message) {
        super(message);
    }
}
