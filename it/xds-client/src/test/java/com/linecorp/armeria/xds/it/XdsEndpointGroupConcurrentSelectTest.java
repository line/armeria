/*
 * Copyright 2026 LY Corporation
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

package com.linecorp.armeria.xds.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsEndpointGroup;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;

/**
 * Tests that concurrent {@link XdsEndpointGroup#select} calls all resolve successfully
 * within the selection timeout. This exercises the CAS race in
 * {@code AbstractEndpointSelector.tryInitialize()} on the inner {@code StaticEndpointGroup}
 * created by the xDS load balancing chain.
 */
class XdsEndpointGroupConcurrentSelectTest {

    @Test
    void concurrentSelectShouldAllResolve() throws Exception {
        // Use a fully static bootstrap: inline listener -> route_config -> cluster -> load_assignment.
        // No control plane server needed.
        final Bootstrap bootstrap = XdsResourceReader.fromYaml("""
                static_resources:
                  listeners:
                  - name: test-listener
                    api_listener:
                      api_listener:
                        "@type": type.googleapis.com/envoy.extensions.filters.network\
                .http_connection_manager.v3.HttpConnectionManager
                        stat_prefix: http
                        route_config:
                          name: local-route
                          virtual_hosts:
                          - name: local
                            domains: ["*"]
                            routes:
                            - match:
                                prefix: /
                              route:
                                cluster: test-cluster
                        http_filters:
                        - name: envoy.filters.http.router
                          typed_config:
                            "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                  clusters:
                  - name: test-cluster
                    type: STATIC
                    load_assignment:
                      cluster_name: test-cluster
                      endpoints:
                      - lb_endpoints:
                        - endpoint:
                            address:
                              socket_address:
                                address: 127.0.0.1
                                port_value: 8080
                        - endpoint:
                            address:
                              socket_address:
                                address: 127.0.0.1
                                port_value: 8081
                """, Bootstrap.class);

        final int numTasks = 1000;
        final ExecutorService executor = Executors.newFixedThreadPool(numTasks);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsEndpointGroup endpointGroup = XdsEndpointGroup.of("test-listener", xdsBootstrap)) {

            // Wait for the initial endpoints to be ready before firing concurrent selects.
            endpointGroup.whenReady().get(10, TimeUnit.SECONDS);

            final CountDownLatch readyLatch = new CountDownLatch(numTasks);
            final CountDownLatch startLatch = new CountDownLatch(1);
            final List<CompletableFuture<Endpoint>> futures = new ArrayList<>(numTasks);

            for (int i = 0; i < numTasks; i++) {
                final CompletableFuture<Endpoint> future = new CompletableFuture<>();
                futures.add(future);
                executor.submit(() -> {
                    readyLatch.countDown();
                    try {
                        startLatch.await();
                    } catch (InterruptedException e) {
                        future.completeExceptionally(e);
                        return;
                    }
                    final ClientRequestContext ctx =
                            ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
                    final CompletableFuture<Endpoint> selectFuture =
                            endpointGroup.select(ctx, ctx.eventLoop());
                    selectFuture.whenComplete((endpoint, cause) -> {
                        if (cause != null) {
                            future.completeExceptionally(cause);
                        } else {
                            future.complete(endpoint);
                        }
                    });
                });
            }

            // Wait for all tasks to be ready, then release them simultaneously.
            assertThat(readyLatch.await(10, TimeUnit.SECONDS)).isTrue();
            startLatch.countDown();

            // All selects must resolve within 3.2 seconds (the default selection timeout).
            // If any hit the CAS race and time out, this will fail.
            final CompletableFuture<Void> allFuture =
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
            allFuture.get(3200, TimeUnit.MILLISECONDS);

            for (CompletableFuture<Endpoint> future : futures) {
                assertThat(future.join()).isNotNull();
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
