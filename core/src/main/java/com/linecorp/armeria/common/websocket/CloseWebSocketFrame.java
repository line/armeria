/*
 * Copyright 2022 LINE Corporation
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

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A close {@link WebSocketFrame}.
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.5.1">Close</a>
 */
@UnstableApi
public interface CloseWebSocketFrame extends WebSocketFrame {

    /**
     * Returns the {@link WebSocketCloseStatus}.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-7.4">Status Codes</a>
     */
    WebSocketCloseStatus status();

    /**
     * Returns the text that indicates the reason for closure.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-7.4">Status Codes</a>
     */
    @Nullable
    String reasonPhrase();
}
