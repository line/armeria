/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.armeria.common.websocket;

import com.linecorp.armeria.common.TimeoutException;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A {@link TimeoutException} raised when a WebSocket exceeds the configured idle timeout.
 *
 * <p>Specifically, this indicates that no inbound frame was received within the configured idle interval.</p>
 */
@UnstableApi
public final class WebSocketIdleTimeoutException extends TimeoutException {
    private static final long serialVersionUID = 216107322771109906L;

    /**
     * Creates a new instance with the specified {@code message}.
     */
    public WebSocketIdleTimeoutException(@Nullable String message) {
        super(message);
    }

    /**
     * Creates a new instance with the specified {@code message} and {@code cause}.
     */
    public WebSocketIdleTimeoutException(@Nullable String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
