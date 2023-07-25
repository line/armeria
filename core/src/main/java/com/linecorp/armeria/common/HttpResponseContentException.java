package com.linecorp.armeria.common;

import javax.annotation.Nullable;

public final class HttpResponseContentException extends RuntimeException {

    private static final long serialVersionUID = 8348993826614065333L;
    private final String content;

    /**
     * Constructs new {@link HttpResponseContentException}
     * @param content A {@code content} of response
     * @param message A exception message
     */
    public HttpResponseContentException(String content, @Nullable String message) {
        super(makeExceptionMessage(content, message));
        this.content = content;
    }

    /**
     * Constructs new {@link HttpResponseContentException}
     * @param content A {@code content} of response
     * @param message A exception message
     * @param cause the cause (which is saved for later retrieval by the {@link #getCause()} method).
     *              (A {@code null} value is permitted, and indicates that the cause is nonexistent or unknown.)
     */
    public HttpResponseContentException(String content, @Nullable String message,
                                        @Nullable Throwable cause) {
        super(makeExceptionMessage(content, message), cause);
        this.content = content;
    }

    /**
     * A content of response
     */
    public String content() {
        return content;
    }


    private static String makeExceptionMessage(String content, @Nullable String message) {
        final StringBuilder builder = new StringBuilder();
        builder.append("Response content - ")
               .append(content);
        return (message == null) ? builder.toString() :
               builder.append(": ").append(message).toString();
    }
}
