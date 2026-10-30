package com.chat.web;

/** Small helper so the filter can write the same JSON envelope as the exception handler. */
public final class SceneExceptionWriter {

    private SceneExceptionWriter() {
    }

    public static String toJson(String code, String message) {
        return "{\"code\":\"" + escape(code) + "\",\"message\":\"" + escape(message) + "\"}";
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
