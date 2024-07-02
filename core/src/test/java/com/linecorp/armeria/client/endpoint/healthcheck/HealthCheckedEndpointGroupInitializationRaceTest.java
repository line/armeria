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
 *
 */

package com.linecorp.armeria.client.endpoint.healthcheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.internal.client.endpoint.healthcheck.HealthCheckContextGroup;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import wiremock.com.google.common.collect.ImmutableList;

class HealthCheckedEndpointGroupInitializationRaceTest {

    private static final CompletableFuture<Void> healthy = new CompletableFuture<>();

    @RegisterExtension
    static final ServerExtension server0 = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/health", (ctx, req) -> {
                return HttpResponse.of(healthy.thenApply(unused -> HttpResponse.of(HttpStatus.OK)));
            });
        }
    };

    @RegisterExtension
    static final ServerExtension server1 = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/health", (ctx, req) -> {
                return HttpResponse.of(healthy.thenApply(unused -> HttpResponse.of(HttpStatus.OK)));
            });
        }
    };

    @Test
    void shouldPreserveNewestContextGroup() throws InterruptedException {
        final SettableEndpointGroup group = new SettableEndpointGroup();
        group.updateEndpoints(ImmutableList.of(server0.httpEndpoint(), server0.httpEndpoint(),
                                               server1.httpEndpoint(), server1.httpEndpoint()));
        final HealthCheckedEndpointGroup healthGroup = HealthCheckedEndpointGroup.of(group,
                                                                                     "/health");
        Queue<HealthCheckContextGroup> contextGroupChain = healthGroup.endpointPool().contextGroupChain();
        assertThat(contextGroupChain).hasSize(1);
        assertThat(contextGroupChain.peek().contexts().values())
                .allSatisfy(ctx -> assertThat(ctx.refCnt()).isOne());

        group.updateEndpoints(ImmutableList.of(server0.httpEndpoint(), server0.httpEndpoint(),
                                               server1.httpEndpoint()));
        contextGroupChain = healthGroup.endpointPool().contextGroupChain();
        assertThat(contextGroupChain).hasSize(2);
        for (HealthCheckContextGroup checkContextGroup : contextGroupChain) {
            assertThat(checkContextGroup.contexts().values())
                    .allSatisfy(ctx -> assertThat(ctx.refCnt()).isEqualTo(2));
        }

        group.updateEndpoints(ImmutableList.of(server0.httpEndpoint(), server0.httpEndpoint(),
                                               server1.httpEndpoint(), server1.httpEndpoint()));
        contextGroupChain = healthGroup.endpointPool().contextGroupChain();
        assertThat(contextGroupChain).hasSize(3);
        for (HealthCheckContextGroup checkContextGroup : contextGroupChain) {
            assertThat(checkContextGroup.contexts().values())
                    .allSatisfy(ctx -> assertThat(ctx.refCnt()).isEqualTo(3));
        }

        healthy.complete(null);
        final List<Endpoint> endpoints = healthGroup.whenReady().join();
        assertThat(endpoints).hasSize(4);
        assertThat(endpoints).containsExactlyInAnyOrder(server0.httpEndpoint(), server0.httpEndpoint(),
                                                        server1.httpEndpoint(), server1.httpEndpoint());

        contextGroupChain = healthGroup.endpointPool().contextGroupChain();
        contextGroupChain.peek().whenInitialized().join();
        // Should clean up the old context groups and decrease the `refCnt`s.
        assertThat(contextGroupChain).hasSize(1);
        assertThat(contextGroupChain.peek().contexts().values())
                .allSatisfy(ctx -> assertThat(ctx.refCnt()).isOne());
    }

    private static final class SettableEndpointGroup extends DynamicEndpointGroup {
        void updateEndpoints(List<Endpoint> endpoints) {
            setEndpoints(endpoints);
        }
    }
}
