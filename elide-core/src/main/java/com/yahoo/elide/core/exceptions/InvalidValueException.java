/*
 * Copyright 2015, Yahoo Inc.
 * Licensed under the Apache License, Version 2.0
 * See LICENSE file in project root for terms.
 */
package com.yahoo.elide.core.exceptions;
import com.yahoo.elide.core.HttpStatus;

/**
 * Exception when an invalid value is used.
 *
 * {@link com.yahoo.elide.core.HttpStatus#SC_BAD_REQUEST invalid}
 */
public class InvalidValueException extends HttpStatusException {

    public InvalidValueException(Object value) {
        super("Invalid value: " + value.toString());
    }

    public InvalidValueException(Object value, String verboseMessage) {
        super("Invalid value: " + value.toString(), verboseMessage);
    }

    public InvalidValueException(String message, Throwable cause) {
        super(message, null, cause);
    }

    @Override
    public int getStatus() {
        return HttpStatus.SC_BAD_REQUEST;
    }
}
