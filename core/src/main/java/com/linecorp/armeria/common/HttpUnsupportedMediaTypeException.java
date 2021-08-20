package com.linecorp.armeria.common;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

public final class HttpUnsupportedMediaTypeException extends RuntimeException {
    private static final long serialVersionUID = 2703217963774780290L;

    @Nullable
    private final MediaType contentType;

    private final List<MediaType> supportedMediaTypes;

    /**
     * Constructs new {@link HttpUnsupportedMediaTypeException}.
     * @param contentType A {@code content} of response.
     */
    public HttpUnsupportedMediaTypeException(@Nullable MediaType contentType) {
        this(contentType, Collections.emptyList());
    }

    /**
     * Constructs new {@link HttpUnsupportedMediaTypeException}.
     * @param contentType A {@code content} of response.
     * @param supportedMediaTypes Supported {@link MediaType}
     */
    public HttpUnsupportedMediaTypeException(@Nullable MediaType contentType,
                                             List<MediaType> supportedMediaTypes) {
        super(makeExceptionMessage(contentType, supportedMediaTypes));
        this.contentType = contentType;
        this.supportedMediaTypes = supportedMediaTypes;
    }

    /**
     * Constructs new {@link HttpUnsupportedMediaTypeException}.
     * @param contentType A {@code content} of response.
     * @param supportedMediaTypes Supported {@link MediaType}
     * @param cause the cause (which is saved for later retrieval by the {@link #getCause()} method).
     *              (A {@code null} value is permitted, and indicates that the cause is nonexistent or unknown.)
     */
    public HttpUnsupportedMediaTypeException(@Nullable MediaType contentType,
                                             List<MediaType> supportedMediaTypes,
                                             @Nullable Throwable cause) {
        super(makeExceptionMessage(contentType, supportedMediaTypes), cause);
        this.contentType = contentType;
        this.supportedMediaTypes = supportedMediaTypes;
    }

    /**
     * A content of response
     */
    @Nullable
    public MediaType contentType() {
        return contentType;
    }

    /**
     * A content of response
     */
    public List<MediaType> supportedMediaTypes() {
        return supportedMediaTypes;
    }

    private static String makeExceptionMessage(@Nullable MediaType contentType,
                                               List<MediaType> supportedMediaTypes) {
        final List<String> supportedMediaTypeNames = supportedMediaTypes.stream().map(
                MediaType::nameWithoutParameters).collect(
                Collectors.toList());

        final StringBuilder builder = new StringBuilder();
        builder.append("Content type '")
               .append(contentType != null ? contentType : "")
               .append("' not supported.");

        if (!supportedMediaTypes.isEmpty()) {
            builder.append(" Expected one of '")
                   .append(String.join(", ", supportedMediaTypeNames))
                   .append("'.");
        }
        return builder.toString();
    }
}
