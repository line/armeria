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
package com.linecorp.armeria.internal.client.thrift;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRpcClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.client.retry.RetryRuleWithContent;
import com.linecorp.armeria.client.retry.RetryingRpcClient;
import com.linecorp.armeria.client.thrift.ThriftClients;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.util.Unwrappable;

import testing.thrift.main.HelloService;

class THttpClientUnwrapTest {

    @Test
    void test() {
        final HelloService.Iface client =
                ThriftClients.builder("tbinary+http://127.0.0.1:1/")
                             .decorator(LoggingClient.newDecorator())
                             .rpcDecorator(RetryingRpcClient.newDecorator(
                                     RetryRuleWithContent.<RpcResponse>builder().thenNoRetry()))
                             .build(HelloService.Iface.class);

        assertThat(Clients.unwrap(client, HelloService.Iface.class)).isSameAs(client);

        assertThat(Clients.unwrap(client, RetryingRpcClient.class)).isInstanceOf(RetryingRpcClient.class);
        assertThat(Clients.unwrap(client, LoggingClient.class)).isInstanceOf(LoggingClient.class);

        // The outermost decorator of the client must be returned,
        // because the search begins from outside to inside.
        // In the current setup, the outermost `Unwrappable` and `Client` are
        // `THttpClientInvocationHandler` and `RetryingRpcClient` respectively.
        assertThat(Clients.unwrap(client, Unwrappable.class)).isInstanceOf(THttpClientInvocationHandler.class);
        assertThat(Clients.unwrap(client, Client.class)).isInstanceOf(RetryingRpcClient.class);

        assertThat(Clients.unwrap(client, CircuitBreakerRpcClient.class)).isNull();
    }
}
