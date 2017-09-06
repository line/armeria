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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.util.Exceptions;

/**
 * A {@link RuntimeException} that is raised when an armeria internal http exception has occurred.
 * This class is the general class of exceptions produced by a failed request or a reset stream.
 */
public class HttpResponseException extends RuntimeException {

    private static final Map<Integer, HttpResponseException> EXCEPTIONS = new ConcurrentHashMap<>();

    /**
     * Returns a new {@link HttpResponseException} instance with the HTTP status code.
     */
    public static HttpResponseException of(int statusCode) {
        return of(HttpStatus.valueOf(statusCode));
    }

    /**
     * Returns a new {@link HttpResponseException} instance with the {@link HttpStatus}.
     */
    public static HttpResponseException of(HttpStatus httpStatus) {
        requireNonNull(httpStatus, "httpStatus");
        if (Flags.verboseExceptions()) {
            return new HttpResponseException(httpStatus);
        } else {
            final int statusCode = httpStatus.code();
            return EXCEPTIONS.computeIfAbsent(statusCode, code ->
                    Exceptions.clearTrace(new HttpResponseException(code)));
        }
    }

    private static final long serialVersionUID = 3487991462085151316L;

    private final HttpStatus httpStatus;

    /**
     * Creates a new instance with HTTP status code.
     */
    public HttpResponseException(int statusCode) {
        this(HttpStatus.valueOf(statusCode));
    }

    /**
     * Creates a new instance.
     */
    public HttpResponseException(HttpStatus httpStatus) {
        super(requireNonNull(httpStatus, "httpStatus").toString());
        if (100 <= httpStatus.code() && httpStatus.code() < 400) {
            throw new IllegalArgumentException(
                    "httpStatus: " + httpStatus +
                    " (expected: a status that's neither informational, success nor redirection)");
        }
        this.httpStatus = httpStatus;
    }

    /**
     * Returns the {@link HttpStatus} that will be sent to a client.
     */
    public HttpStatus httpStatus() {
        return httpStatus;
    }

    @Override
    public Throwable fillInStackTrace() {
        if (Flags.verboseExceptions()) {
            return super.fillInStackTrace();
        }
        return this;
    }
}
