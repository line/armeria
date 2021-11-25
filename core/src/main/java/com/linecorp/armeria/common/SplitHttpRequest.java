package com.linecorp.armeria.common;

import java.util.concurrent.CompletableFuture;

import com.google.errorprone.annotations.CheckReturnValue;

import com.linecorp.armeria.common.stream.StreamMessage;

/**
 * An {@link HttpRequest} which splits a stream of {@link HttpObject}s into HTTP headers and payloads.
 * {@link #headers()} returns a {@link RequestHeaders}.
 * {@link #trailers()} might not complete until the entire request body is consumed completely.
 */
public interface SplitHttpRequest {

    /**
     * Returns a {@link RequestHeaders}.
     */
    RequestHeaders headers();

    /**
     * Returns a {@link StreamMessage} publishes HTTP payloads as a stream of {@link HttpData}.
     */
    @CheckReturnValue
    StreamMessage<HttpData> body();

    /**
     * Returns a {@link CompletableFuture} completed with a {@linkplain HttpHeaders trailers}.
     * If an {@link HttpRequest} does not contain trailers, the returned {@link CompletableFuture} will be
     * completed with an {@linkplain HttpHeaders#of() empty headers}.
     */
    CompletableFuture<HttpHeaders> trailers();
}
