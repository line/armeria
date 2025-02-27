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

import static com.linecorp.armeria.internal.client.SessionProtocolUtil.defaultSessionProtocol;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.Endpoint;

class WebSocketClientBuilderTest {

    @CsvSource({
            "http,     ws+http",
            "https,    ws+https",
            "h1,       ws+h1",
            "h1c,      ws+h1c",
            "h2,       ws+h2",
            "h2c,      ws+h2c",
            "http,     ws+http",
            "https,    ws+https",
            "ws,       ws+http",
            "wss,      ws+https",
            "ws+h1,    ws+h1",
            "ws+h1c,   ws+h1c",
            "ws+h2,    ws+h2",
            "ws+h2c,   ws+h2c",
            "ws+http,  ws+http",
            "ws+https, ws+https",
    })
    @ParameterizedTest
    void uriWithWsPlusProtocol(String scheme, String convertedScheme) {
        final WebSocketClient client = WebSocketClient.builder(scheme + "://google.com/").build();
        assertThat(client.uri().toString()).isEqualTo(convertedScheme + "://google.com/");
    }

    @CsvSource({
            "http,     ws+http",
            "https,    ws+https",
            "h1,       ws+h1",
            "h1c,      ws+h1c",
            "h2,       ws+h2",
            "h2c,      ws+h2c",
            "http,     ws+http",
            "https,    ws+https",
            "ws,       ws+http",
            "wss,      ws+https",
            "ws+h1,    ws+h1",
            "ws+h1c,   ws+h1c",
            "ws+h2,    ws+h2",
            "ws+h2c,   ws+h2c",
            "ws+http,  ws+http",
            "ws+https, ws+https",
    })
    @ParameterizedTest
    void endpointWithoutPath(String scheme, String convertedScheme) {
        final WebSocketClient client = WebSocketClient.builder(scheme, Endpoint.of("127.0.0.1")).build();
        assertThat(client.uri().toString()).isEqualTo(convertedScheme + "://127.0.0.1/");
    }

    @CsvSource({
            "http,     ws+http",
            "https,    ws+https",
            "h1,       ws+h1",
            "h1c,      ws+h1c",
            "h2,       ws+h2",
            "h2c,      ws+h2c",
            "http,     ws+http",
            "https,    ws+https",
            "ws,       ws+http",
            "wss,      ws+https",
            "ws+h1,    ws+h1",
            "ws+h1c,   ws+h1c",
            "ws+h2,    ws+h2",
            "ws+h2c,   ws+h2c",
            "ws+http,  ws+http",
            "ws+https, ws+https",
    })
    @ParameterizedTest
    void endpointWithPath(String scheme, String convertedScheme) {
        final WebSocketClient client = WebSocketClient.builder(scheme, Endpoint.of("127.0.0.1"), "/foo")
                                                      .build();
        assertThat(client.uri().toString()).isEqualTo(convertedScheme + "://127.0.0.1/foo");
    }

    @Test
    void webSocketClientDefaultOptions() {
        final WebSocketClient client = WebSocketClient.builder("wss://google.com/").build();
        assertThat(client.options().get(ClientOptions.RESPONSE_TIMEOUT_MILLIS)).isEqualTo(0);
        assertThat(client.options().get(ClientOptions.MAX_RESPONSE_LENGTH)).isEqualTo(0);
        assertThat(client.options().get(ClientOptions.REQUEST_AUTO_ABORT_DELAY_MILLIS)).isEqualTo(5000);
        assertThat(client.options().get(ClientOptions.AUTO_FILL_ORIGIN_HEADER)).isTrue();
    }

    private static Stream<Arguments> withoutScheme_args() throws Exception {
        return Stream.of(
                Arguments.of(WebSocketClient.of("//google.com")),
                Arguments.of(WebSocketClient.builder("//google.com").build()),
                Arguments.of(WebSocketClient.of(new URI(null, "google.com", null, null))),
                Arguments.of(WebSocketClient.builder(new URI(null, "google.com", null, null)).build()),
                Arguments.of(WebSocketClient.of(Endpoint.of("google.com"))),
                Arguments.of(WebSocketClient.of(Endpoint.of("google.com"), "/")),
                Arguments.of(WebSocketClient.builder(Endpoint.of("google.com")).build()),
                Arguments.of(WebSocketClient.builder(Endpoint.of("google.com"), "/").build())
        );
    }

    @ParameterizedTest
    @MethodSource("withoutScheme_args")
    void withoutScheme(WebSocketClient client) {
        assertThat(client.scheme().sessionProtocol()).isEqualTo(defaultSessionProtocol());
        assertThat(client.uri().toString()).isEqualTo("ws+http://google.com/");
    }
}
