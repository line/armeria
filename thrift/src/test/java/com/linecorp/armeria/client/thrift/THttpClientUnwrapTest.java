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
package com.linecorp.armeria.client.thrift;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.client.retry.RetryingRpcClient;
import com.linecorp.armeria.common.util.Unwrappable;
import com.linecorp.armeria.service.test.thrift.main.HelloService;

class THttpClientUnwrapTest {

    @Test
    void test() {
        final HelloService.Iface client = new ClientBuilder("tbinary+http://127.0.0.1:1/")
                .decorator(LoggingClient.newDecorator())
                .rpcDecorator(RetryingRpcClient.newDecorator(
                        (ctx, response) -> CompletableFuture.completedFuture(null)))
                .build(HelloService.Iface.class);

        assertThat(Clients.unwrap(client, HelloService.Iface.class)).containsSame(client);

        assertThat(Clients.unwrap(client, RetryingRpcClient.class)).containsInstanceOf(RetryingRpcClient.class);
        assertThat(Clients.unwrap(client, LoggingClient.class)).containsInstanceOf(LoggingClient.class);

        assertThat(Clients.unwrap(client, Unwrappable.class))
                .containsInstanceOf(THttpClientInvocationHandler.class);
        assertThat(Clients.unwrap(client, Client.class)).containsInstanceOf(RetryingRpcClient.class);

        assertThat(Clients.unwrap(client, String.class)).isEmpty();
    }
}
