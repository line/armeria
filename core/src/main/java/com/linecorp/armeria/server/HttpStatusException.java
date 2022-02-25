/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A {@link RuntimeException} that is raised to send a simplistic HTTP response with minimal content
 * by a {@link Service}. It is a general exception raised by a failed request or a reset stream.
 *
 * <p>Note that an {@link HttpStatusException} raised may not be applied to the next decorators if the
 * {@link HttpStatusException} is not recovered before passed to the next decorator chain.
 * For that reason, you need to properly handle the thrown {@link HttpStatus} into a normal
 * {@link HttpResponse} using {@link HttpResponse#recover(Function)} or
 * {@link HttpStatusException#httpStatus()}.
 * For example:
 * <pre>{@code
 * // Catch an HttpStatusException and convert into an HttpResponse
 * try {
 *     throwableService();
 * } catch (HttpStatusException ex) {
 *     return HttpResponse.of(ex.httpStatus());
 * }
 *
 * // Recover the HttpStatusException using HttpResponse.recover()
 * HttpResponse response = ...;
 * response.recover(ex -> {
 *     if (ex instanceof HttpStatusException) {
 *         return HttpResponse.of(((HttpStatusException) ex).httpStatus());
 *     } else {
 *         return HttpResponse.ofFailure(ex);
 *     }
 * })
 * }</pre>
 *
 * <p>An unhandled {@link HttpStatusException} will be recovered by the default {@link ServerErrorHandler}
 * in the end. If you want to mutate the {@link HttpResponse} made of the {@link HttpStatusException}, you can
 * add a custom {@link ServerErrorHandler}.
 *
 * @see HttpResponseException
 */
public final class HttpStatusException extends RuntimeException {

    private static final HttpStatusException[] EXCEPTIONS = new HttpStatusException[1000];

    static {
        for (int i = 0; i < EXCEPTIONS.length; i++) {
            EXCEPTIONS[i] = new HttpStatusException(HttpStatus.valueOf(i), false, null);
        }
    }

    /**
     * Returns a new {@link HttpStatusException} instance with the specified HTTP status code.
     */
    public static HttpStatusException of(int statusCode) {
        return of(HttpStatus.valueOf(statusCode));
    }

    /**
     * Returns a new {@link HttpStatusException} instance with the specified HTTP status code and {@code cause}.
     */
    public static HttpStatusException of(int statusCode, Throwable cause) {
        requireNonNull(cause, "cause");
        return of(HttpStatus.valueOf(statusCode), cause);
    }

    /**
     * Returns a new {@link HttpStatusException} instance with the specified {@link HttpStatus}.
     */
    public static HttpStatusException of(HttpStatus status) {
        return of0(requireNonNull(status, "status"), null);
    }

    /**
     * Returns a new {@link HttpStatusException} instance with the specified {@link HttpStatus} and
     * {@code cause}.
     */
    public static HttpStatusException of(HttpStatus status, Throwable cause) {
        return of0(requireNonNull(status, "status"), requireNonNull(cause, "cause"));
    }

    private static HttpStatusException of0(HttpStatus status, @Nullable Throwable cause) {
        final boolean sampled = Flags.verboseExceptionSampler().isSampled(HttpStatusException.class);
        if (sampled || cause != null) {
            return new HttpStatusException(status, sampled, cause);
        }

        final int statusCode = status.code();
        if (statusCode >= 0 && statusCode < 1000) {
            return EXCEPTIONS[statusCode];
        } else {
            return new HttpStatusException(HttpStatus.valueOf(statusCode), false, null);
        }
    }

    private static final long serialVersionUID = 3341744805097308847L;

    private final HttpStatus httpStatus;

    /**
     * Creates a new instance.
     */
    private HttpStatusException(HttpStatus httpStatus, boolean withStackTrace, @Nullable Throwable cause) {
        super(requireNonNull(httpStatus, "httpStatus").toString(), cause, withStackTrace, withStackTrace);
        this.httpStatus = httpStatus;
    }

    /**
     * Returns the {@link HttpStatus} which would be sent back to the client who sent the
     * corresponding request.
     */
    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
