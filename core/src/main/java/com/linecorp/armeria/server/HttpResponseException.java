/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpStatus;

/**
 * A {@link RuntimeException} that is raised when an armeria internal http exception has occurred.
 * This class is the general class of exceptions produced by a failed request or a reset stream.
 */
public abstract class HttpResponseException extends RuntimeException {
    private static final long serialVersionUID = 3487991462085151316L;

    private final HttpStatus httpStatus;

    /**
     * Creates a new instance.
     */
    protected HttpResponseException(HttpStatus httpStatus) {
        requireNonNull(httpStatus, "httpStatus");
        if (100 <= httpStatus.code() && httpStatus.code() < 400) {
            throw new IllegalArgumentException("httpStatus: " + httpStatus +
                                               " (expected: code < 100 || code >= 400)");
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
    public synchronized Throwable fillInStackTrace() {
        if (Flags.verboseExceptions()) {
            return super.fillInStackTrace();
        }
        return this;
    }
}
