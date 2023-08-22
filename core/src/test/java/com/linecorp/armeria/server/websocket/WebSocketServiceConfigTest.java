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

package com.linecorp.armeria.server.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.internal.common.websocket.WebSocketUtil;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.websocket.WebSocketServiceTest.AbstractWebSocketHandler;

class WebSocketServiceConfigTest {

    @Test
    void webSocketServiceDefaultConfigValues() {
        final WebSocketService webSocketService = WebSocketService.of(new AbstractWebSocketHandler());
        final Server server = Server.builder().service("/", webSocketService).build();
        assertThat(server.config().serviceConfigs()).hasSize(1);
        ServiceConfig serviceConfig = server.config().serviceConfigs().get(0);
        assertThat(serviceConfig.requestTimeoutMillis()).isEqualTo(
                WebSocketUtil.DEFAULT_REQUEST_RESPONSE_TIMEOUT_MILLIS);
        assertThat(serviceConfig.maxRequestLength()).isEqualTo(
                WebSocketUtil.DEFAULT_MAX_REQUEST_RESPONSE_LENGTH);
        assertThat(serviceConfig.requestAutoAbortDelayMillis()).isEqualTo(
                WebSocketUtil.DEFAULT_REQUEST_AUTO_ABORT_DELAY_MILLIS);

        server.reconfigure(sb -> sb.requestAutoAbortDelayMillis(1000)
                                   .route()
                                   .get("/")
                                   .requestTimeoutMillis(2000)
                                   .build(webSocketService));

        assertThat(server.config().serviceConfigs()).hasSize(1);
        serviceConfig = server.config().serviceConfigs().get(0);
        assertThat(serviceConfig.requestTimeoutMillis()).isEqualTo(2000);
        assertThat(serviceConfig.maxRequestLength()).isEqualTo(
                WebSocketUtil.DEFAULT_MAX_REQUEST_RESPONSE_LENGTH);
        assertThat(serviceConfig.requestAutoAbortDelayMillis()).isEqualTo(1000);
    }
}
