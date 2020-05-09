/*
 * Copyright 2019 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Test for {@link ClientBuilder}.
 */
class ClientBuilderTest {

    @Test
    void uriWithNonePlusProtocol() throws Exception {
        final WebClient client = Clients.builder("none+https://google.com/").build(WebClient.class);
        assertThat(client.uri().toString()).isEqualTo("https://google.com/");
    }

    @Test
    void uriWithoutNone() {
        final WebClient client = Clients.builder("https://google.com/").build(WebClient.class);
        assertThat(client.uri().toString()).isEqualTo("https://google.com/");
    }

    @Test
    void endpointWithoutPath() {
        final WebClient client = Clients.builder("http", Endpoint.of("127.0.0.1"))
                                        .build(WebClient.class);
        assertThat(client.uri().toString()).isEqualTo("http://127.0.0.1/");
    }

    @Test
    void endpointWithPath() {
        final WebClient client = Clients.builder("http", Endpoint.of("127.0.0.1"), "/foo")
                                        .build(WebClient.class);
        assertThat(client.uri().toString()).isEqualTo("http://127.0.0.1/foo");
    }
}
