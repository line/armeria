/*
 * Copyright 2023 LINE Corporation
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
package com.linecorp.armeria.client.websocket;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.client.InvalidResponseException;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * An {@link InvalidResponseException} raised when a {@link WebSocketClient} client received a response with
 * invalid headers during the handshake.
 */
@UnstableApi
public final class WebSocketClientHandshakeException extends InvalidResponseException {

    private static final long serialVersionUID = -8521952766254225005L;

    private final ResponseHeaders headers;

    /**
     * Creates a new instance.
     */
    public WebSocketClientHandshakeException(String message, ResponseHeaders headers) {
        super(message);
        this.headers = requireNonNull(headers, "headers");
    }

    /**
     * Returns the {@link ResponseHeaders} of the handshake response.
     */
    public ResponseHeaders headers() {
        return headers;
    }
}
