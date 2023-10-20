/*
 * Copyright 2022 LINE Corporation
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

package resilience4j.thrift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.function.Function;

import org.apache.thrift.TException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRpcClient;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRuleWithContent;
import com.linecorp.armeria.client.thrift.ThriftClients;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.resilience4j.circuitbreaker.client.Resilience4JCircuitBreakerClientHandler;
import com.linecorp.armeria.resilience4j.circuitbreaker.client.Resilience4jCircuitBreakerMapping;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.Builder;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import testing.resilience4j.HelloReply;
import testing.resilience4j.HelloRequest;
import testing.resilience4j.NoHelloException;
import testing.resilience4j.TestService;
import testing.resilience4j.TestService.Iface;

class Resilience4jWithThriftTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final THttpService thriftService =
                    THttpService.builder()
                                .addService(new TestServiceImpl())
                                .build();
            sb.service("/thrift", thriftService);
        }
    };

    static class TestServiceImpl implements TestService.Iface {
        @Override
        public HelloReply hello(HelloRequest request) throws TException {
            throw new NoHelloException();
        }
    }

    @Test
    void testBasicClientIntegration() throws Exception {
        final int minimumNumberOfCalls = 3;
        final CircuitBreakerConfig config = new Builder()
                .minimumNumberOfCalls(minimumNumberOfCalls)
                .build();
        final CircuitBreakerRuleWithContent<RpcResponse> rule =
                CircuitBreakerRuleWithContent.<RpcResponse>builder()
                                             .onException(NoHelloException.class)
                                             .thenFailure();
        final CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        final Resilience4jCircuitBreakerMapping mapping = Resilience4jCircuitBreakerMapping.builder()
                                                                                           .registry(registry)
                                                                                           .perHost()
                                                                                           .build();
        final Function<? super RpcClient, CircuitBreakerRpcClient> decorator =
                CircuitBreakerRpcClient.newDecorator(
                        Resilience4JCircuitBreakerClientHandler.of(mapping), rule);
        final Iface TestService = ThriftClients.builder(server.httpUri())
                                                .path("/thrift")
                                                .rpcDecorator(decorator)
                                                .build(Iface.class);

        for (int i = 0; i < minimumNumberOfCalls; i++) {
            assertThatThrownBy(() -> TestService.hello(new HelloRequest("hello")))
                    .isInstanceOf(NoHelloException.class);
        }

        // wait until the circuitbreaker is open
        assertThat(registry.getAllCircuitBreakers()).hasSize(1);
        final CircuitBreaker cb = registry.getAllCircuitBreakers().stream().findFirst().orElseThrow();
        await().untilAsserted(() -> assertThat(cb.getState()).isEqualTo(State.OPEN));

        assertThatThrownBy(() -> TestService.hello(new HelloRequest("hello")))
                .isInstanceOf(UnprocessedRequestException.class)
                .hasCauseInstanceOf(CallNotPermittedException.class);
    }
}
