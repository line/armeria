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
package com.linecorp.armeria.client.retry;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.HttpClientBuilder;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroupRegistry;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.endpoint.StaticEndpointGroup;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

class RetryingClientLoadBalancingTest {

    private static final int NUM_PORTS = 5;

    private enum TestMode {
        SUCCESS("/success"),
        FAILURE("/failure");

        final String path;

        TestMode(String path) {
            this.path = path;
        }
    }

    private final List<Integer> accessedPorts = new CopyOnWriteArrayList<>();

    @RegisterExtension
    final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            accessedPorts.clear();

            for (int i = 0; i < NUM_PORTS; i++) {
                sb.http(0);
            }

            sb.service(TestMode.SUCCESS.path, (ctx, req) -> {
                accessedPorts.add(((InetSocketAddress) ctx.localAddress()).getPort());
                return HttpResponse.of(HttpStatus.OK);
            });

            sb.service(TestMode.FAILURE.path, (ctx, req) -> {
                accessedPorts.add(((InetSocketAddress) ctx.localAddress()).getPort());
                return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE);
            });
        }
    };

    /**
     * Makes sure that {@link RetryingClient} respects the {@link Endpoint} selection order.
     */
    @ParameterizedTest
    @EnumSource(TestMode.class)
    void test(TestMode mode) {
        server.start();
        final List<Integer> expectedPorts = server.server().activePorts().keySet().stream()
                                                  .map(InetSocketAddress::getPort)
                                                  .collect(toImmutableList());

        final EndpointGroup group =
                new StaticEndpointGroup(expectedPorts.stream()
                                                     .map(port -> Endpoint.of("127.0.0.1", port))
                                                     .collect(toImmutableList()));

        final String groupName = "loadBalancedRetry";
        EndpointGroupRegistry.register(groupName, group, EndpointSelectionStrategy.ROUND_ROBIN);
        try {
            final RetryStrategy retryStrategy = (ctx, cause) -> {
                // Get the response status.
                final HttpStatus status;
                if (ctx.log().isAvailable(RequestLogAvailability.RESPONSE_HEADERS)) {
                    status = ctx.log().responseHeaders().status();
                } else {
                    status = null;
                }

                // Retry only once on failure.
                if (!HttpStatus.OK.equals(status) && RetryingClient.getTotalAttempts(ctx) <= 1) {
                    return CompletableFuture.completedFuture(Backoff.withoutDelay());
                } else {
                    return CompletableFuture.completedFuture(null);
                }
            };
            final HttpClient c = new HttpClientBuilder("h2c://group:" + groupName)
                    .decorator(RetryingHttpClient.builder(retryStrategy)
                                                 .newDecorator())
                    .build();

            for (int i = 0; i < NUM_PORTS; i++) {
                c.get(mode.path).aggregate().join();
            }

            switch (mode) {
                case SUCCESS:
                    assertThat(accessedPorts).isEqualTo(expectedPorts);
                    break;
                case FAILURE:
                    final List<Integer> expectedPortsWhenRetried =
                            ImmutableList.<Integer>builder()
                                         .addAll(expectedPorts)
                                         .addAll(expectedPorts)
                                         .build();
                    assertThat(accessedPorts).isEqualTo(expectedPortsWhenRetried);
                    break;
            }
        } finally {
            EndpointGroupRegistry.unregister(groupName);
        }
    }
}
