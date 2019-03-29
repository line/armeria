/*
 * Copyright 2019 LINE Corporation
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

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpHeaders;

/**
 * An {@link InvalidResponseException} raised when a client received a response with invalid headers.
 */
public class InvalidResponseHeadersException extends InvalidResponseException {

    private static final long serialVersionUID = -1349209911680323202L;

    private final HttpHeaders headers;

    /**
     * Creates a new instance with the specified response {@link HttpHeaders}.
     */
    public InvalidResponseHeadersException(HttpHeaders headers) {
        super(requireNonNull(headers, "headers").toString());
        this.headers = HttpHeaders.copyOf(headers).asImmutable();
    }

    /**
     * Creates a new instance with the specified response {@link HttpHeaders} and {@code cause}.
     */
    public InvalidResponseHeadersException(HttpHeaders headers, @Nullable Throwable cause) {
        super(requireNonNull(headers, "headers").toString(), cause);
        this.headers = HttpHeaders.copyOf(headers).asImmutable();
    }

    /**
     * Creates a new instance with the specified response {@link HttpHeaders}, {@code cause},
     * suppression enabled or disabled, and writable stack trace enabled or disabled.
     */
    protected InvalidResponseHeadersException(HttpHeaders headers, @Nullable Throwable cause,
                                              boolean enableSuppression, boolean writableStackTrace) {
        super(requireNonNull(headers, "headers").toString(), cause,
              enableSuppression, writableStackTrace);
        this.headers = HttpHeaders.copyOf(headers).asImmutable();
    }

    /**
     * Returns the response {@link HttpHeaders} which triggered this exception.
     */
    public HttpHeaders headers() {
        return headers;
    }
}
