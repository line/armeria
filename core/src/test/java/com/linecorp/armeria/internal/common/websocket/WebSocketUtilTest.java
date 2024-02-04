/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.armeria.internal.common.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.websocket.CloseWebSocketFrame;
import com.linecorp.armeria.server.websocket.WebSocketProtocolViolationException;

import joptsimple.internal.Strings;

class WebSocketUtilTest {

    @Test
    void reasonPhraseTruncate() {
        final String reasonPhrase = Strings.repeat('a', 126);
        final WebSocketProtocolViolationException exception =
                new WebSocketProtocolViolationException(reasonPhrase);
        final CloseWebSocketFrame closeWebSocketFrame = WebSocketUtil.newCloseWebSocketFrame(exception);
        assertThat(closeWebSocketFrame.reasonPhrase()).isEqualTo(
                reasonPhrase.substring(0, 111) + "...(truncated)");
    }
}
