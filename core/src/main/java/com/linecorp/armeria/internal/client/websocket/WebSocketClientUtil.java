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

package com.linecorp.armeria.internal.client.websocket;

import java.net.URI;

import com.linecorp.armeria.client.websocket.WebSocketClient;

public final class WebSocketClientUtil {

    /**
     * An undefined {@link URI} to create {@link WebSocketClient} without specifying {@link URI}.
     */
    public static final URI UNDEFINED_WEBSOCKET_URI = URI.create("ws://undefined");

    private WebSocketClientUtil() {}
}
