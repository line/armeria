/*
 * Copyright 2020 LINE Corporation
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

class WebClientBuilderTest {

    @Test
    void keepCustomFactory() {
        final ClientFactory factory = ClientFactory.builder()
                                                   .option(ClientFactoryOption.HTTP1_MAX_CHUNK_SIZE, 100)
                                                   .build();
        final ClientOptions options = ClientOptions.of(ClientOption.RESPONSE_TIMEOUT_MILLIS.newValue(200L));
        final WebClient webClient = WebClient.builder("http://foo")
                                             .factory(factory)
                                             .options(options)
                                             .build();

        final ClientOptions clientOptions = webClient.options();
        assertThat(clientOptions.get(ClientOption.RESPONSE_TIMEOUT_MILLIS)).isEqualTo(200);
        final ClientFactory clientFactory = clientOptions.factory();
        assertThat(clientFactory.options().get(ClientFactoryOption.HTTP1_MAX_CHUNK_SIZE)).isEqualTo(100);
    }

    @Test
    void keepLastFactory_by_options() {
        final ClientFactory optionClientFactory =
                ClientFactory.builder()
                             .option(ClientFactoryOption.HTTP1_MAX_CHUNK_SIZE, 200)
                             .build();
        final ClientOptions options = ClientOptions.of(ClientOption.RESPONSE_TIMEOUT_MILLIS.newValue(200L),
                                                       ClientOption.FACTORY.newValue(optionClientFactory));

        final ClientFactory factory = ClientFactory.builder()
                                                   .option(ClientFactoryOption.HTTP1_MAX_CHUNK_SIZE, 100)
                                                   .build();

        final WebClient webClient = WebClient.builder("http://foo")
                                             .factory(factory)
                                             .options(options)
                                             .build();

        final ClientOptions clientOptions = webClient.options();
        assertThat(clientOptions.get(ClientOption.RESPONSE_TIMEOUT_MILLIS)).isEqualTo(200);
        final ClientFactory clientFactory = clientOptions.factory();
        assertThat(clientFactory.options().get(ClientFactoryOption.HTTP1_MAX_CHUNK_SIZE)).isEqualTo(200);
    }

    @Test
    void keepLastFactory_by_factory() {
        final ClientFactory optionClientFactory =
                ClientFactory.builder()
                             .option(ClientFactoryOption.HTTP1_MAX_CHUNK_SIZE, 200)
                             .build();
        final ClientOptions options = ClientOptions.of(ClientOption.RESPONSE_TIMEOUT_MILLIS.newValue(200L),
                                                       ClientOption.FACTORY.newValue(optionClientFactory));

        final ClientFactory factory = ClientFactory.builder()
                                                   .option(ClientFactoryOption.HTTP1_MAX_CHUNK_SIZE, 100)
                                                   .build();

        final WebClient webClient = WebClient.builder("http://foo")
                                             .options(options)
                                             .factory(factory)
                                             .build();

        final ClientOptions clientOptions = webClient.options();
        assertThat(clientOptions.get(ClientOption.RESPONSE_TIMEOUT_MILLIS)).isEqualTo(200);
        final ClientFactory clientFactory = clientOptions.factory();
        assertThat(clientFactory.options().get(ClientFactoryOption.HTTP1_MAX_CHUNK_SIZE)).isEqualTo(100);
    }
}
