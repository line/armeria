/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import javax.annotation.Nonnull;

import com.linecorp.armeria.common.Flags;

/**
 * A {@link RuntimeException} raised when it is certain that a request has not been handled by a server and
 * thus can be retried safely. This exception is usually raised when a server sent an HTTP/2 GOAWAY frame with
 * the {@code lastStreamId} less than the stream ID of the request.
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7540#section-6.8">Section 6.8, RFC7540</a>
 */
public final class UnprocessedRequestException extends RuntimeException {

    private static final long serialVersionUID = 4679512839715213302L;

    /**
     * Returns a new {@link UnprocessedRequestException} with the specified {@code message} and
     * {@link Throwable}.
     */
    public static UnprocessedRequestException of(Throwable cause) {
        requireNonNull(cause, "cause");
        if (cause instanceof UnprocessedRequestException) {
            return (UnprocessedRequestException) cause;
        }
        return new UnprocessedRequestException(cause);
    }

    /**
     * Creates a new instance with the specified {@code cause}.
     */
    private UnprocessedRequestException(Throwable cause) {
        super(requireNonNull(cause, "cause").toString(), cause);
    }

    @Nonnull
    @Override
    public Throwable getCause() {
        return super.getCause();
    }

    @Override
    public Throwable fillInStackTrace() {
        if (Flags.verboseExceptionSampler().isSampled(getClass())) {
            super.fillInStackTrace();
        }
        return this;
    }
}
