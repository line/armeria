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

package com.linecorp.armeria.it.client.retry;

import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.BINARY;
import static com.linecorp.armeria.common.util.UnmodifiableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionTimeoutException;
import com.linecorp.armeria.client.retry.RetryConfig;
import com.linecorp.armeria.client.retry.RetryRuleWithContent;
import com.linecorp.armeria.client.retry.RetryingRpcClient;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.internal.testing.CountDownEmptyEndpointStrategy;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import testing.thrift.main.HelloService.Iface;

class RetryingRpcClientWithEmptyEndpointGroupTest {

    private static final String CUSTOM_HEADER = "custom-header";

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/thrift", THttpService.of((Iface) name -> name));
        }
    };

    @Test
    void testSelectionTimeout() {
        final int maxTotalAttempts = 3;

        final DynamicEndpointGroup endpointGroup = new DynamicEndpointGroup();
        final RetryConfig<RpcResponse> retryConfig =
                RetryConfig.builderForRpc(RetryRuleWithContent.onUnprocessed())
                           .maxTotalAttempts(maxTotalAttempts)
                           .build();
        final Iface iface = Clients.builder(Scheme.of(BINARY, SessionProtocol.HTTP), endpointGroup)
                                   .responseTimeout(Duration.ZERO)
                                   .rpcDecorator(RetryingRpcClient.builder(retryConfig).newDecorator())
                                   .build(Iface.class);

        try (ClientRequestContextCaptor ctxCaptor = Clients.newContextCaptor()) {
            assertThatThrownBy(() -> iface.hello("world"))
                    .isInstanceOf(UnprocessedRequestException.class)
                    .hasCauseInstanceOf(EndpointSelectionTimeoutException.class);

            assertThat(ctxCaptor.size()).isEqualTo(1);
            assertThat(ctxCaptor.get().log().children()).hasSize(maxTotalAttempts);
            ctxCaptor.get().log().children().forEach(log -> {
                final Throwable responseCause = log.whenComplete().join().responseCause();
                assertThat(responseCause)
                        .isInstanceOf(UnprocessedRequestException.class)
                        .hasCauseInstanceOf(EndpointSelectionTimeoutException.class);
            });
        }
    }

    @Test
    void testSelectionTimeoutEventuallySucceeds() throws Exception {
        final int maxTotalAttempts = 10;
        final int selectAttempts = 3;

        final DynamicEndpointGroup endpointGroup = new DynamicEndpointGroup(
                new CountDownEmptyEndpointStrategy(selectAttempts,
                                                 unused -> completedFuture(server.httpEndpoint())));

        final RetryConfig<RpcResponse> retryConfig =
                RetryConfig.builderForRpc(RetryRuleWithContent.onUnprocessed())
                           .maxTotalAttempts(maxTotalAttempts)
                           .build();
        final Iface iface = Clients.builder(Scheme.of(BINARY, SessionProtocol.HTTP), endpointGroup, "/thrift")
                                   .responseTimeout(Duration.ZERO)
                                   .contextCustomizer(ctx -> ctx
                                           .addAdditionalRequestHeader(CUSTOM_HEADER, "asdf"))
                                   .rpcDecorator(RetryingRpcClient.builder(retryConfig).newDecorator())
                                   .build(Iface.class);

        try (ClientRequestContextCaptor ctxCaptor = Clients.newContextCaptor()) {
            final String world = iface.hello("world");
            assertThat(world).isEqualTo("world");

            assertThat(ctxCaptor.size()).isEqualTo(1);
            assertThat(ctxCaptor.get().log().children()).hasSize(selectAttempts);

            // ensure that selection timeout occurred (selectAttempts - 1) times
            for (int i = 0; i < selectAttempts - 1; i++) {
                final RequestLogAccess log = ctxCaptor.get().log().children().get(i);
                final Throwable responseCause = log.whenComplete().join().responseCause();
                assertThat(responseCause)
                        .isInstanceOf(UnprocessedRequestException.class)
                        .hasCauseInstanceOf(EndpointSelectionTimeoutException.class);
            }

            // ensure that the last selection succeeded
            final RequestLogAccess log = ctxCaptor.get().log().children().get(selectAttempts - 1);
            assertThat(log.whenComplete().join().responseStatus().code())
                    .isEqualTo(200);

            // context customizer should be run only once
            assertThat(log.whenRequestComplete().join().requestHeaders().getAll(CUSTOM_HEADER)).hasSize(1);
        }
    }
}
