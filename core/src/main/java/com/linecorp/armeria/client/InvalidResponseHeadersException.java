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

import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * An {@link InvalidResponseException} raised when a client received a response with invalid headers.
 */
public final class InvalidResponseHeadersException extends InvalidResponseException {

    private static final long serialVersionUID = -1349209911680323202L;

    private final ResponseHeaders headers;

    /**
     * Creates a new instance with the specified {@link ResponseHeaders}.
     */
    public InvalidResponseHeadersException(ResponseHeaders headers) {
        super(requireNonNull(headers, "headers").toString());
        this.headers = headers;
    }

    /**
     * Creates a new instance with the specified {@link ResponseHeaders} and {@code cause}.
     */
    public InvalidResponseHeadersException(ResponseHeaders headers, @Nullable Throwable cause) {
        super(requireNonNull(headers, "headers").toString(), cause);
        this.headers = headers;
    }

    /**
     * Returns the {@link ResponseHeaders} which triggered this exception.
     */
    public ResponseHeaders headers() {
        return headers;
    }
}
