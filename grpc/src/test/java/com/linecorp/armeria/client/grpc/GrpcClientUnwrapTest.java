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
package com.linecorp.armeria.client.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.encoding.HttpDecodingClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.client.retry.RetryStrategy;
import com.linecorp.armeria.client.retry.RetryingHttpClient;
import com.linecorp.armeria.common.util.Unwrappable;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceBlockingStub;

class GrpcClientUnwrapTest {

    @Test
    void test() {
        final TestServiceBlockingStub client = new ClientBuilder("gproto+http://127.0.0.1:1/")
                .decorator(LoggingClient.newDecorator())
                .decorator(RetryingHttpClient.newDecorator(RetryStrategy.never()))
                .build(TestServiceBlockingStub.class);

        assertThat(Clients.unwrap(client, TestServiceBlockingStub.class)).containsSame(client);

        assertThat(Clients.unwrap(client, RetryingHttpClient.class))
                .containsInstanceOf(RetryingHttpClient.class);
        assertThat(Clients.unwrap(client, LoggingClient.class)).containsInstanceOf(LoggingClient.class);

        // The outermost decorator of the client must be returned,
        // because the search begins from outside to inside.
        // In the current setup, the outermost `Unwrappable` and `Client` are
        // `ArmeriaChannel` and `RetryingClient` respectively.
        assertThat(Clients.unwrap(client, Unwrappable.class)).containsInstanceOf(ArmeriaChannel.class);
        assertThat(Clients.unwrap(client, Client.class)).containsInstanceOf(RetryingHttpClient.class);

        assertThat(Clients.unwrap(client, HttpDecodingClient.class)).isEmpty();
    }
}
