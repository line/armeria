/*
 * Copyright 2016 LINE Corporation
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

import com.linecorp.armeria.common.HttpStatus;

import io.netty.handler.codec.http2.Http2Error;

/**
 * A {@link RuntimeException} that is raised when an armeria internal http exception has occurred.
 * This class is the general class of exceptions produced by a failed request or a reset stream.
 */
public abstract class HttpResponseException extends RuntimeException {
    private static final long serialVersionUID = 3487991462085151316L;

    private final HttpStatus httpStatus;
    private final Http2Error http2error;

    /**
     * Creates a new instance.
     */
    protected HttpResponseException(HttpStatus httpStatus, Http2Error http2error) {
        this.httpStatus = httpStatus;
        this.http2error = http2error;
    }

    /**
     * Returns the {@link HttpStatus} that will be sent to a client.
     */
    HttpStatus httpStatus() {
        return httpStatus;
    }

    /**
     * Returns the {@link Http2Error} that will be sent to a stream.
     */
    Http2Error http2error() {
        return http2error;
    }
}
