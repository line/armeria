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

package com.linecorp.armeria.client.endpoint.healthcheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.healthcheck.HealthCheckedEndpointGroupTest.MockEndpointGroup;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class HealthCheckedEndpointSelectionTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.requestTimeoutMillis(0);
            sb.service("/healthy", (ctx, req) -> HttpResponse.of("OK"));
            sb.service("/slow", (ctx, req) -> {
                return HttpResponse.delayed(HttpResponse.of("SLOW"), Duration.ofSeconds(5));
            });
        }
    };

    @Test
    void healthy() {
        try (MockEndpointGroup delegate = new MockEndpointGroup();
             HealthCheckedEndpointGroup endpointGroup =
                     HealthCheckedEndpointGroup.of(delegate, "/healthy")) {

            final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            final CompletableFuture<Endpoint> result =
                    endpointGroup.select(ctx, CommonPools.blockingTaskExecutor());
            CommonPools.blockingTaskExecutor().schedule(() -> {
                delegate.set(server.httpEndpoint());
            }, 500, TimeUnit.MILLISECONDS);
            assertThat(result.join()).isEqualTo(server.httpEndpoint());
        }
    }

    @Test
    void slow_success() {
        try (MockEndpointGroup delegate = new MockEndpointGroup();
             HealthCheckedEndpointGroup endpointGroup =
                     HealthCheckedEndpointGroup.of(delegate, "/slow")) {

            final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            final CompletableFuture<Endpoint> result =
                    endpointGroup.select(ctx, CommonPools.blockingTaskExecutor());
            CommonPools.blockingTaskExecutor().schedule(() -> {
                delegate.set(server.httpEndpoint());
            }, 1, TimeUnit.SECONDS);
            assertThat(result.join()).isEqualTo(server.httpEndpoint());
        }
    }

    @Test
    void slow_failure() {
        try (MockEndpointGroup delegate = new MockEndpointGroup(1000);
             HealthCheckedEndpointGroup endpointGroup =
                     HealthCheckedEndpointGroup.builder(delegate, "/slow")
                                               .selectionTimeoutMillis(1000)
                                               .build()) {

            final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            final CompletableFuture<Endpoint> result =
                    endpointGroup.select(ctx, CommonPools.blockingTaskExecutor());
            CommonPools.blockingTaskExecutor().schedule(() -> {
                delegate.set(server.httpEndpoint());
            }, 1, TimeUnit.SECONDS);
            assertThat(result.join()).isNull();
        }
    }
}
