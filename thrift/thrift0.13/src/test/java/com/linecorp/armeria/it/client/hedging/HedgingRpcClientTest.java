/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.it.client.hedging;

import static com.linecorp.armeria.client.retry.AbstractRetryingClient.ARMERIA_RETRY_COUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.apache.thrift.TException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.hedging.HedgingConfig;
import com.linecorp.armeria.client.hedging.HedgingRpcClient;
import com.linecorp.armeria.client.hedging.HedgingRule;
import com.linecorp.armeria.client.thrift.ThriftClients;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import testing.thrift.main.HelloService;

public class HedgingRpcClientTest {
    private final HelloService.Iface serviceHandler = mock(HelloService.Iface.class);

    class HedgingTestServerExtension extends ServerExtension {
        private final AtomicInteger serviceCallCount = new AtomicInteger();

        @Override
        protected boolean runForEachTest() {
            return true;
        }

        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            resetCounters();

            sb.service("/thrift", THttpService.of(serviceHandler).decorate(
                    (delegate, ctx, req) -> {
                        final int count = serviceCallCount.getAndIncrement();
                        if (count != 0) {
                            assertThat(count).isEqualTo(req.headers().getInt(ARMERIA_RETRY_COUNT));
                        }
                        return delegate.serve(ctx, req);
                    }));
        }

        private void resetCounters() {
            serviceCallCount.set(0);
        }
    }

    @RegisterExtension
    final ServerExtension server1 = new HedgingTestServerExtension();
    @RegisterExtension
    final ServerExtension server2 = new HedgingTestServerExtension();
    @RegisterExtension
    final ServerExtension server3 = new HedgingTestServerExtension();

    @Test
    void ofWithConfig() throws TException {
        final HelloService.Iface client = helloClient(
                HedgingRpcClient.newDecorator(
                        HedgingConfig.builderForRpc(
                                                      HedgingRule
                                                              .builder()
                                                              .onUnprocessed()
                                                              .thenHedge(100),
                                                      50
                                              ).build()
                )
        );


        client.hello("Hello Armeria!");
    }

    private HelloService.Iface helloClient(
            Function<? super RpcClient, HedgingRpcClient> hedgingClientDecorator) {
        return ThriftClients.builder(
                                    SessionProtocol.H2,
                                    EndpointGroup.of(
                                            server1.httpEndpoint(),
                                            server2.httpEndpoint(),
                                            server3.httpEndpoint()
                                    )
                            )
                            .path("/thrift")
                            .rpcDecorator(hedgingClientDecorator)
                            .build(HelloService.Iface.class);
    }
}
