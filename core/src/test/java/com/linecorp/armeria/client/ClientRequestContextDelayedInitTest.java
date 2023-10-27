/*
 * Copyright 2020 LINE Corporation
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.common.base.Stopwatch;

import com.linecorp.armeria.client.endpoint.AbstractEndpointSelector;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.endpoint.EndpointSelectionTimeoutException;
import com.linecorp.armeria.client.endpoint.EndpointSelector;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ClientRequestContextDelayedInitTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
        }
    };

    @Test
    void simple() {
        final Group group = new Group();
        final WebClient client = WebClient.of(SessionProtocol.H2C, group);
        final CompletableFuture<AggregatedHttpResponse> future = client.get("/").aggregate();
        group.add(server.httpEndpoint());
        assertThat(future.join().status()).isSameAs(HttpStatus.OK);
    }

    @Test
    void timeout() {
        final Group group = new Group();
        final WebClient client = WebClient.of(SessionProtocol.H2C, group);
        final Stopwatch stopwatch = Stopwatch.createStarted();
        assertThatThrownBy(() -> client.get("/").aggregate().join())
                .cause().isInstanceOf(UnprocessedRequestException.class)
                .cause().isInstanceOf(EndpointSelectionTimeoutException.class);
        assertThat(stopwatch.elapsed(TimeUnit.MILLISECONDS))
                .isGreaterThanOrEqualTo(Flags.defaultConnectTimeoutMillis());
    }

    /**
     * Makes sure an exception thrown by {@link EndpointSelector#selectNow(ClientRequestContext)} are handled
     * properly at different points of execution.
     */
    @ParameterizedTest
    @CsvSource({ "0", "1", "2" })
    void failure(int failAfter) {
        final AtomicInteger counter = new AtomicInteger();
        final RuntimeException cause = new RuntimeException();
        final Group group = new Group(endpointGroup -> {
            class TestEndpointSelector extends AbstractEndpointSelector {

                protected TestEndpointSelector(EndpointGroup endpointGroup) {
                    super(endpointGroup);
                    initialize();
                }

                @Nullable
                @Override
                public Endpoint selectNow(ClientRequestContext ctx) {
                    if (counter.getAndIncrement() >= failAfter) {
                        throw cause;
                    }
                    return null;
                }
            }

            return new TestEndpointSelector(endpointGroup);
        });
        final WebClient client = WebClient.of(SessionProtocol.H2C, group);
        final CompletableFuture<AggregatedHttpResponse> future = client.get("/").aggregate();
        group.add(server.httpEndpoint());
        assertThatThrownBy(future::join)
                .cause().isInstanceOf(UnprocessedRequestException.class)
                .cause().isSameAs(cause);
    }

    private static final class Group extends DynamicEndpointGroup {

        Group() {}

        Group(EndpointSelectionStrategy strategy) {
            super(strategy);
        }

        void add(Endpoint e) {
            addEndpoint(e);
        }
    }
}
