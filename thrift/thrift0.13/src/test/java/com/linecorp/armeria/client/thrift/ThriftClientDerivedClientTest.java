/*
 * Copyright 2023 LINE Corporation
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

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.ClientDecoration;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreaker;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRpcClient;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRuleWithContent;
import com.linecorp.armeria.client.limit.ConcurrencyLimitingClient;
import com.linecorp.armeria.client.logging.LoggingRpcClient;
import com.linecorp.armeria.common.RpcResponse;

import testing.thrift.main.HelloService;

class ThriftClientDerivedClientTest {

    @Test
    void shouldPreserveOriginalDecorators() {
        final HelloService.Iface client =
                ThriftClients.builder("http://127.0.0.1:8080/")
                             .rpcDecorator(LoggingRpcClient.newDecorator())
                             .rpcDecorator(
                                     CircuitBreakerRpcClient.newDecorator(
                                             CircuitBreaker.ofDefaultName(),
                                             CircuitBreakerRuleWithContent.<RpcResponse>builder()
                                                                          .onServerErrorStatus()
                                                                          .thenFailure()))
                             .build(HelloService.Iface.class);
        final HelloService.Iface derivedClient0 = Clients.newDerivedClient(client, options -> {
            return options.toBuilder()
                          .decorator(ConcurrencyLimitingClient.newDecorator(10))
                          .build();
        });

        final HelloService.Iface derivedClient1 =
                Clients.newDerivedClient(client,
                                         ClientOptions.DECORATION.newValue(ClientDecoration.of(
                                                 ConcurrencyLimitingClient.newDecorator(10))));

        final ClientBuilderParams originalParams = Clients.unwrap(client, ClientBuilderParams.class);
        final ClientBuilderParams derivedParams0 = Clients.unwrap(derivedClient0, ClientBuilderParams.class);
        assertThat(derivedParams0.options().decoration().rpcDecorators())
                .isEqualTo(originalParams.options().decoration().rpcDecorators());
        assertThat(derivedParams0.options().decoration().decorators()).hasSize(1);

        final ClientBuilderParams derivedParams1 = Clients.unwrap(derivedClient1, ClientBuilderParams.class);
        assertThat(derivedParams1.options().decoration().rpcDecorators())
                .isEqualTo(originalParams.options().decoration().rpcDecorators());
        assertThat(derivedParams1.options().decoration().decorators()).hasSize(1);
    }
}
