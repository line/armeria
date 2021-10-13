/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.armeria.server.websocket;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.ProtocolViolationException;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.websocket.WebSocketCloseStatus;
import com.linecorp.armeria.common.websocket.WebSocketFrame;

/**
 * A {@link RuntimeException} that is raised when it fails to decode a {@link WebSocketFrame}.
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5">Data framing</a>
 */
@UnstableApi
public final class WebSocketProtocolViolationException extends ProtocolViolationException {

    private static final long serialVersionUID = -3262132758775286866L;

    private final WebSocketCloseStatus closeStatus;

    /**
     * Creates a new instance with the {@code message}.
     */
    public WebSocketProtocolViolationException(@Nullable String message) {
        this(WebSocketCloseStatus.PROTOCOL_ERROR, message);
    }

    /**
     * Creates a new instance with the {@link WebSocketCloseStatus} and {@code message}.
     */
    public WebSocketProtocolViolationException(WebSocketCloseStatus closeStatus, @Nullable String message) {
        this(closeStatus, message, null);
    }

    /**
     * Creates a new instance with the {@link WebSocketCloseStatus} and {@code cause}.
     */
    public WebSocketProtocolViolationException(WebSocketCloseStatus closeStatus, @Nullable Throwable cause) {
        this(closeStatus, null, cause);
    }

    /**
     * Creates a new instance with the {@link WebSocketCloseStatus}, {@code message}, and {@code cause}.
     */
    public WebSocketProtocolViolationException(WebSocketCloseStatus closeStatus, @Nullable String message,
                                               @Nullable Throwable cause) {
        super(message != null ? message : closeStatus.reasonText(), cause);
        this.closeStatus = requireNonNull(closeStatus, "closeStatus");
    }

    /**
     * Returns the {@link WebSocketCloseStatus}.
     */
    public WebSocketCloseStatus closeStatus() {
        return closeStatus;
    }
}
