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

import com.linecorp.armeria.client.encoding.DecodingClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.util.Unwrappable;

class HttpClientUnwrapTest {

    @Test
    void test() {
        final WebClient client =
                WebClient.builder()
                         .decorator(LoggingClient.newDecorator())
                         .decorator(RetryingClient.newDecorator(RetryRule.builder().thenNoRetry()))
                         .build();

        assertThat(client.as(WebClient.class)).isSameAs(client);

        assertThat(client.as(RetryingClient.class)).isInstanceOf(RetryingClient.class);
        assertThat(client.as(LoggingClient.class)).isInstanceOf(LoggingClient.class);

        // The outermost decorator of the client must be returned,
        // because the search begins from outside to inside.
        // In the current setup, the outermost `Unwrappable` and `Client` are
        // `client` and `RetryingClient` respectively.
        assertThat(client.as(Unwrappable.class)).isSameAs(client);
        assertThat(client.as(Client.class)).isInstanceOf(RetryingClient.class);

        assertThat(client.as(String.class)).isNull();

        final ClientFactory factory = client.options().factory();

        assertThat(factory.unwrap(client, WebClient.class)).isSameAs(client);

        assertThat(factory.unwrap(client, RetryingClient.class))
                .isInstanceOf(RetryingClient.class);
        assertThat(factory.unwrap(client, LoggingClient.class)).isInstanceOf(LoggingClient.class);

        assertThat(factory.unwrap(client, Unwrappable.class)).isSameAs(client);
        assertThat(factory.unwrap(client, Client.class)).isInstanceOf(RetryingClient.class);

        assertThat(factory.unwrap(client, DecodingClient.class)).isNull();
    }
}
