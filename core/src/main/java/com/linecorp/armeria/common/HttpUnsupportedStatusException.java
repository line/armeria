package com.linecorp.armeria.common;

import javax.annotation.Nullable;

public final class HttpUnsupportedStatusException extends RuntimeException {

    private static final long serialVersionUID = 6796708550902928078L;
    private final HttpStatus httpStatus;

    /**
     * Constructs new {@link HttpUnsupportedStatusException}.
     * @param httpStatus A {@link HttpStatus}
     */
    public HttpUnsupportedStatusException(HttpStatus httpStatus) {
        this(httpStatus, null);
    }

    /**
     * Constructs new {@link HttpUnsupportedStatusException}.
     * @param httpStatus A {@link HttpStatus}
     * @param message A exception message
     */
    public HttpUnsupportedStatusException(HttpStatus httpStatus, @Nullable String message) {
        super(makeExceptionMessage(httpStatus, message));
        this.httpStatus = httpStatus;
    }

    /**
     * Constructs new {@link HttpUnsupportedStatusException}.
     * @param httpStatus A {@link HttpStatus}
     * @param message A exception message
     * @param cause the cause (which is saved for later retrieval by the {@link #getCause()} method).
     *              (A {@code null} value is permitted, and indicates that the cause is nonexistent or unknown.)
     */
    public HttpUnsupportedStatusException(HttpStatus httpStatus, @Nullable String message,
                                          @Nullable Throwable cause) {
        super(makeExceptionMessage(httpStatus, message), cause);
        this.httpStatus = httpStatus;
    }

    /**
     * A {@link HttpStatus}.
     */
    public HttpStatus httpStatus() {
        return httpStatus;
    }

    private static String makeExceptionMessage(HttpStatus httpStatus, @Nullable String message) {
        final StringBuilder builder = new StringBuilder();
        builder.append("Status '")
               .append(httpStatus)
               .append("' not supported.");

        return (message == null) ? builder.toString() : builder.append(" : ").append(message).toString();
    }

}
