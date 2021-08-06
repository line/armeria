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

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A {@link RuntimeException} that is raised to send an HTTP response with the content specified
 * by a user. This class holds an {@link HttpResponse} which would be sent back to the client who
 * sent the corresponding request.
 *
 * @see HttpStatusException
 */
public final class HttpResponseException extends RuntimeException {

    /**
     * Returns a new {@link HttpResponseException} instance with the specified HTTP status code.
     *
     * @deprecated Use {@link HttpStatusException#of(int)} instead.
     */
    @Deprecated
    public static HttpResponseException of(int statusCode) {
        return of(HttpStatus.valueOf(statusCode));
    }

    /**
     * Returns a new {@link HttpResponseException} instance with the specified {@link HttpStatus}.
     *
     * @deprecated Use {@link HttpStatusException#of(HttpStatus)} instead.
     */
    @Deprecated
    public static HttpResponseException of(HttpStatus httpStatus) {
        requireNonNull(httpStatus, "httpStatus");
        return new HttpResponseException(HttpResponse.of(httpStatus), null);
    }

    /**
     * Returns a new {@link HttpResponseException} instance with the specified {@link AggregatedHttpResponse}.
     */
    public static HttpResponseException of(AggregatedHttpResponse aggregatedResponse) {
        return of(requireNonNull(aggregatedResponse, "aggregatedResponse").toHttpResponse(), null);
    }

    /**
     * Returns a new {@link HttpResponseException} instance with the specified {@link AggregatedHttpResponse}
     * and {@link Throwable}.
     */
    public static HttpResponseException of(AggregatedHttpResponse aggregatedResponse,
                                           @Nullable Throwable cause) {
        return of(requireNonNull(aggregatedResponse, "aggregatedResponse").toHttpResponse(), cause);
    }

    /**
     * Returns a new {@link HttpResponseException} instance with the specified {@link HttpResponse}.
     */
    public static HttpResponseException of(HttpResponse httpResponse) {
        return new HttpResponseException(httpResponse, null);
    }

    /**
     * Returns a new {@link HttpResponseException} instance with the specified {@link HttpResponse} and
     * {@link Throwable}.
     */
    public static HttpResponseException of(HttpResponse httpResponse, @Nullable Throwable cause) {
        return new HttpResponseException(httpResponse, cause);
    }

    private static final long serialVersionUID = 3487991462085151316L;

    private final HttpResponse httpResponse;

    /**
     * Creates a new instance with the specified {@link HttpResponse} and {@link Throwable}.
     */
    private HttpResponseException(HttpResponse httpResponse, @Nullable Throwable cause) {
        super(requireNonNull(httpResponse, "httpResponse").toString(), cause);
        this.httpResponse = httpResponse;
    }

    /**
     * Returns the {@link HttpResponse} which would be sent back to the client who sent the
     * corresponding request.
     */
    public HttpResponse httpResponse() {
        return httpResponse;
    }

    @Override
    public Throwable fillInStackTrace() {
        if (Flags.verboseExceptionSampler().isSampled(getClass())) {
            return super.fillInStackTrace();
        }
        return this;
    }
}
