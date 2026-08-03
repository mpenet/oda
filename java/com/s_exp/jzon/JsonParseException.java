package com.s_exp.jzon;

public final class JsonParseException extends RuntimeException {
    public final int position;

    public JsonParseException(String message, int position) {
        super(message + " at offset " + position);
        this.position = position;
    }
}
