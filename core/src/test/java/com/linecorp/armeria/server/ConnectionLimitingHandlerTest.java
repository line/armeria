/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import io.netty.channel.embedded.EmbeddedChannel;

class ConnectionLimitingHandlerTest {

    @Test
    void testExceedMaxNumConnections() {
        final ServerMetrics serverMetrics = new ServerMetrics();
        final ConnectionLimitingHandler handler =
                new ConnectionLimitingHandler(1, serverMetrics);

        final EmbeddedChannel ch1 = new EmbeddedChannel(handler);
        ch1.writeInbound(ch1);
        assertThat(handler.numConnections()).isEqualTo(1);
        assertThat(ch1.isActive()).isTrue();

        final EmbeddedChannel ch2 = new EmbeddedChannel(handler);
        ch2.writeInbound(ch2);
        assertThat(handler.numConnections()).isEqualTo(1);
        assertThat(ch2.isActive()).isFalse();

        ch1.close();
        assertThat(handler.numConnections()).isEqualTo(0);
    }

    @Test
    void testMaxNumConnectionsRange() {
        final ServerMetrics serverMetrics = new ServerMetrics();
        final ConnectionLimitingHandler handler = new ConnectionLimitingHandler(Integer.MAX_VALUE,
                                                                                serverMetrics);
        assertThat(handler.maxNumConnections()).isEqualTo(Integer.MAX_VALUE);

        assertThatThrownBy(() -> new ConnectionLimitingHandler(0, serverMetrics))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ConnectionLimitingHandler(-1, serverMetrics))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
