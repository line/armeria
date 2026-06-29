/*
 * Copyright 2026 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.circuitbreaker.CircuitBreaker;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRpcClient;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRuleWithContent;
import com.linecorp.armeria.client.circuitbreaker.FailFastException;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import testing.thrift.main.HelloService;
import testing.thrift.main.HelloService.Iface;

class CircuitBreakerRpcClientRuleTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/hello", THttpService.of((Iface) name -> {
                throw new TApplicationException(TApplicationException.INTERNAL_ERROR, "boom");
            }));
        }
    };

    @Test
    void doNotOpenCircuit_whenSuccessFunctionAgrees() {
        final CircuitBreakerRuleWithContent<RpcResponse> rule =
                CircuitBreakerRuleWithContent.<RpcResponse>builder()
                                             .onSuccessFunctionResult(true)
                                             .thenSuccess()
                                             .orElse(CircuitBreakerRuleWithContent
                                                             .<RpcResponse>builder()
                                                             .onException()
                                                             .thenFailure());

        final HelloService.Iface client =
                ThriftClients.builder(server.httpUri())
                             .path("/hello")
                             .successFunction((ctx, log) -> true)
                             .rpcDecorator(CircuitBreakerRpcClient.newDecorator(
                                     superSensitiveCircuitBreaker(), rule))
                             .build(HelloService.Iface.class);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> client.hello("foo")).isInstanceOf(TException.class);
        }
    }

    @Test
    void openCircuit_whenSuccessFunctionDisagrees() {
        final CircuitBreakerRuleWithContent<RpcResponse> rule =
                CircuitBreakerRuleWithContent.<RpcResponse>builder()
                                             .onSuccessFunctionResult(true)
                                             .thenSuccess()
                                             .orElse(CircuitBreakerRuleWithContent
                                                             .<RpcResponse>builder()
                                                             .onException()
                                                             .thenFailure());

        final HelloService.Iface client =
                ThriftClients.builder(server.httpUri())
                             .path("/hello")
                             .successFunction((ctx, log) -> false)
                             .rpcDecorator(CircuitBreakerRpcClient.newDecorator(
                                     superSensitiveCircuitBreaker(), rule))
                             .build(HelloService.Iface.class);

        assertThatThrownBy(() -> client.hello("foo")).isInstanceOf(TException.class);
        await().untilAsserted(() -> {
            assertThatThrownBy(() -> client.hello("foo"))
                    .isInstanceOf(FailFastException.class);
        });
    }

    private static CircuitBreaker superSensitiveCircuitBreaker() {
        return CircuitBreaker.builder().minimumRequestThreshold(0).build();
    }
}
