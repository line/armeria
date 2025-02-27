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

package com.linecorp.armeria.client;

import static com.linecorp.armeria.internal.client.SessionProtocolUtil.defaultSessionProtocol;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RestClientBuilderTest {

    private static Stream<Arguments> withoutScheme_args() throws Exception {
        return Stream.of(
                Arguments.of(RestClient.of("//google.com")),
                Arguments.of(RestClient.builder("//google.com").build()),
                Arguments.of(RestClient.of(new URI(null, "google.com", null, null))),
                Arguments.of(RestClient.builder(new URI(null, "google.com", null, null)).build()),
                Arguments.of(RestClient.of(Endpoint.of("google.com"))),
                Arguments.of(RestClient.of(Endpoint.of("google.com"), "/")),
                Arguments.of(RestClient.builder(Endpoint.of("google.com")).build()),
                Arguments.of(RestClient.builder(Endpoint.of("google.com"), "/").build())
        );
    }

    @ParameterizedTest
    @MethodSource("withoutScheme_args")
    void withoutScheme(RestClient client) {
        assertThat(client.scheme().sessionProtocol()).isEqualTo(defaultSessionProtocol());
        assertThat(client.uri().toString()).isEqualTo("http://google.com/");
    }
}
