/*
 * Copyright 2015 LINE Corporation
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

import static com.linecorp.armeria.client.ClientOptions.DECORATION;
import static com.linecorp.armeria.client.ClientOptions.ENDPOINT_REMAPPER;
import static com.linecorp.armeria.client.ClientOptions.HEADERS;
import static com.linecorp.armeria.client.ClientOptions.MAX_RESPONSE_LENGTH;
import static com.linecorp.armeria.client.ClientOptions.PREPROCESSORS;
import static com.linecorp.armeria.client.ClientOptions.REQUEST_ID_GENERATOR;
import static com.linecorp.armeria.client.ClientOptions.RESPONSE_TIMEOUT_MILLIS;
import static com.linecorp.armeria.client.ClientOptions.WRITE_TIMEOUT_MILLIS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestId;

class ClientOptionsTest {

    @Test
    void testAsMap() {
        final HttpHeaders httpHeader = HttpHeaders.of(HttpHeaderNames.of("x-user-defined"), "HEADER_VALUE");
        final ClientOptions options = ClientOptions.of(HEADERS.newValue(httpHeader));
        final Map<ClientOption<Object>, ClientOptionValue<Object>> map = options.asMap();
        assertThat(map).hasSize(1);
        assertThat(map.get(HEADERS).value()).isEqualTo(httpHeader);
    }

    @Test
    void testSetHeader() {
        final HttpHeaders httpHeader = HttpHeaders.of(HttpHeaderNames.of("x-user-defined"), "HEADER_VALUE");

        final ClientOptions options = ClientOptions.of(HEADERS.newValue(httpHeader));
        assertThat(options.get(HEADERS)).isEqualTo(httpHeader);

        final ClientOptions options2 = ClientOptions.of();
        assertThat(options2.get(HEADERS)).isEqualTo(HttpHeaders.of());
    }

    @Test
    void testInvalidWriteTimeoutMillis() {
        assertThatThrownBy(() -> {
            ClientOptions.of(WRITE_TIMEOUT_MILLIS.newValue(null));
        }).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testInvalidResponseTimeoutMillis() {
        assertThatThrownBy(() -> {
            ClientOptions.of(RESPONSE_TIMEOUT_MILLIS.newValue(null));
        }).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testInvalidMaxResponseLength() {
        assertThatThrownBy(() -> {
            ClientOptions.of(MAX_RESPONSE_LENGTH.newValue(null));
        }).isInstanceOf(NullPointerException.class);
    }

    @Test
    void allowOnlyConnectionHeader() {
        assertThatCode(() -> {
            ClientOptions.of(HEADERS.newValue(
                    HttpHeaders.of(HttpHeaderNames.CONNECTION, "close")));
            ClientOptions.of(HEADERS.newValue(
                    HttpHeaders.of(HttpHeaderNames.CONNECTION, "Close")));
            ClientOptions.of(HEADERS.newValue(
                    HttpHeaders.of(HttpHeaderNames.CONNECTION, "CLOSE")));
        }).doesNotThrowAnyException();

        assertThatThrownBy(() -> {
            ClientOptions.of(HEADERS.newValue(
                    HttpHeaders.of(HttpHeaderNames.CONNECTION, "others")));
        }).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ArgumentsSource(ClientOptionsProvider.class)
    void shouldKeepSpecifiedOption(ClientOption<Object> option, Object value) {
        final ClientOptions first = ClientOptions.of(option.newValue(value));
        final ClientOptions second = ClientOptions.of();
        final ClientOptions merged = ClientOptions.of(first, second);
        assertThat(merged.get(option)).isEqualTo(value);
    }

    private static class ClientOptionsProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            final Supplier<RequestId> requestIdGenerator = () -> () -> "1";
            return Stream.of(
                    arguments(WRITE_TIMEOUT_MILLIS, 10),
                    arguments(RESPONSE_TIMEOUT_MILLIS, 20),
                    arguments(MAX_RESPONSE_LENGTH, 123),
                    arguments(HEADERS, HttpHeaders.of(HttpHeaderNames.USER_AGENT, "armeria")),
                    arguments(DECORATION, ClientDecoration.of(LoggingClient.newDecorator())),
                    arguments(REQUEST_ID_GENERATOR, requestIdGenerator),
                    arguments(ENDPOINT_REMAPPER, Function.identity()),
                    arguments(PREPROCESSORS, ClientPreprocessors.of(
                            (delegate, ctx, req) -> HttpResponse.of(200))));
        }
    }
}
