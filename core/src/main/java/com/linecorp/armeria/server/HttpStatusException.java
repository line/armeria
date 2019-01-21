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

import javax.annotation.Nullable;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.util.Exceptions;

/**
 * A {@link RuntimeException} that is raised to send a simplistic HTTP response with minimal content
 * by a {@link Service}. It is a general exception raised by a failed request or a reset stream.
 *
 * @see HttpResponseException
 */
public final class HttpStatusException extends RuntimeException {

    private static final Map<Integer, HttpStatusException> EXCEPTIONS = new ConcurrentHashMap<>();

    /**
     * Returns a new {@link HttpStatusException} instance with the specified HTTP status code.
     */
    public static HttpStatusException of(int statusCode) {
        return of(HttpStatus.valueOf(statusCode));
    }

    /**
     * Returns a new {@link HttpStatusException} instance with the specified {@link HttpStatus}.
     */
    public static HttpStatusException of(HttpStatus httpStatus) {
        requireNonNull(httpStatus, "httpStatus");
        if (Flags.verboseExceptions()) {
            return new HttpStatusException(httpStatus);
        } else {
            final int statusCode = httpStatus.code();
            return EXCEPTIONS.computeIfAbsent(statusCode, code ->
                    Exceptions.clearTrace(new HttpStatusException(HttpStatus.valueOf(code), null)));
        }
    }

    private static final long serialVersionUID = 3341744805097308847L;

    private final HttpStatus httpStatus;

    /**
     * Creates a new instance with the specified {@link HttpStatus}.
     */
    private HttpStatusException(HttpStatus httpStatus) {
        super(requireNonNull(httpStatus, "httpStatus").toString());
        this.httpStatus = httpStatus;
    }

    /**
     * Creates a new instance with the specified {@link HttpStatus} and {@code cause}.
     */
    private HttpStatusException(HttpStatus httpStatus, @Nullable Throwable cause) {
        super(requireNonNull(httpStatus, "httpStatus").toString(), cause);
        this.httpStatus = httpStatus;
    }

    /**
     * Returns the {@link HttpStatus} which would be sent back to the client who sent the
     * corresponding request.
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
