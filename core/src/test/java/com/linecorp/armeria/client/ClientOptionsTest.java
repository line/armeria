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

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;

class ClientOptionsTest {

    @Test
    void allDefaultOptionsArePresent() throws Exception {
        final int expectedModifiers = Modifier.PUBLIC | Modifier.STATIC | Modifier.FINAL;
        final Set<ClientOption<Object>> options =
                Arrays.stream(ClientOption.class.getDeclaredFields())
                      .filter(f -> (f.getModifiers() & expectedModifiers) == expectedModifiers)
                      .map(f -> {
                          try {
                              @SuppressWarnings("unchecked")
                              final ClientOption<Object> opt = (ClientOption<Object>) f.get(null);
                              return opt;
                          } catch (IllegalAccessException e) {
                              throw new Error(e);
                          }
                      })
                      .collect(toImmutableSet());

        assertThat(ClientOptions.of().asMap().keySet()).isEqualTo(options);
    }

    @Test
    void testSetHttpHeader() {
        final HttpHeaders httpHeader = HttpHeaders.of(HttpHeaderNames.of("x-user-defined"), "HEADER_VALUE");

        final ClientOptions options = ClientOptions.of(ClientOption.HTTP_HEADERS.newValue(httpHeader));
        assertThat(options.get(ClientOption.HTTP_HEADERS)).isEqualTo(httpHeader);

        final ClientOptions options2 = ClientOptions.of();
        assertThat(options2.get(ClientOption.HTTP_HEADERS)).isEqualTo(HttpHeaders.of());
    }

    @Test
    void testSetBlackListHeader() {
        assertThatThrownBy(() -> {
            ClientOptions.of(ClientOption.HTTP_HEADERS.newValue(
                    HttpHeaders.of(HttpHeaderNames.HOST, "localhost")));
        }).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testInvalidWriteTimeoutMillis() {
        assertThatThrownBy(() -> {
            ClientOptions.of(ClientOption.WRITE_TIMEOUT_MILLIS.newValue(null));
        }).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testInvalidResponseTimeoutMillis() {
        assertThatThrownBy(() -> {
            ClientOptions.of(ClientOption.RESPONSE_TIMEOUT_MILLIS.newValue(null));
        }).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testInvalidMaxResponseLength() {
        assertThatThrownBy(() -> {
            ClientOptions.of(ClientOption.MAX_RESPONSE_LENGTH.newValue(null));
        }).isInstanceOf(NullPointerException.class);
    }
}
