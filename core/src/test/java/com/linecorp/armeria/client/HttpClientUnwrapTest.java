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

import com.linecorp.armeria.client.encoding.HttpDecodingClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.client.retry.RetryStrategy;
import com.linecorp.armeria.client.retry.RetryingHttpClient;
import com.linecorp.armeria.common.util.Unwrappable;

class HttpClientUnwrapTest {

    @Test
    void test() {
        final HttpClient client = new HttpClientBuilder()
                .decorator(LoggingClient.newDecorator())
                .decorator(RetryingHttpClient.newDecorator(RetryStrategy.never()))
                .build();

        assertThat(client.as(HttpClient.class)).containsSame(client);

        assertThat(client.as(RetryingHttpClient.class)).containsInstanceOf(RetryingHttpClient.class);
        assertThat(client.as(LoggingClient.class)).containsInstanceOf(LoggingClient.class);

        // The outermost decorator of the client must be returned,
        // because the search begins from outside to inside.
        // In the current setup, the outermost `Unwrappable` and `Client` are
        // `client` and `RetryingHttpClient` respectively.
        assertThat(client.as(Unwrappable.class)).containsSame(client);
        assertThat(client.as(Client.class)).containsInstanceOf(RetryingHttpClient.class);

        assertThat(client.as(String.class)).isEmpty();

        final ClientFactory factory = client.factory();

        assertThat(factory.unwrap(client, HttpClient.class)).containsSame(client);

        assertThat(factory.unwrap(client, RetryingHttpClient.class))
                .containsInstanceOf(RetryingHttpClient.class);
        assertThat(factory.unwrap(client, LoggingClient.class)).containsInstanceOf(LoggingClient.class);

        assertThat(factory.unwrap(client, Unwrappable.class)).containsSame(client);
        assertThat(factory.unwrap(client, Client.class)).containsInstanceOf(RetryingHttpClient.class);

        assertThat(factory.unwrap(client, HttpDecodingClient.class)).isEmpty();
    }
}
