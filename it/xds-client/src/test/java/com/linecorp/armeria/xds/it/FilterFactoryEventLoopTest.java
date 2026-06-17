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
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.SnapshotWatcher;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.filter.FactoryContext;
import com.linecorp.armeria.xds.filter.HttpFilterFactory;
import com.linecorp.armeria.xds.filter.XdsHttpFilter;
import com.linecorp.armeria.xds.stream.SnapshotStream;
import com.linecorp.armeria.xds.stream.Subscription;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.SdsSecretConfig;

class FilterFactoryEventLoopTest {

    private static final String LISTENER_NAME = "listener1";
    private static final String CUSTOM_FILTER_NAME = "test.custom_filter";
    private static final String CUSTOM_FILTER_TYPE_URL =
            "type.googleapis.com/google.protobuf.Empty";

    @RegisterExtension
    static final EventLoopExtension xdsEventLoop = new EventLoopExtension();

    @RegisterExtension
    static final EventLoopExtension otherEventLoop = new EventLoopExtension();

    //language=YAML
    private static final String BOOTSTRAP_YAML = """
            static_resources:
              clusters:
                - name: my-cluster
                  type: STATIC
                  load_assignment:
                    cluster_name: my-cluster
                    endpoints:
                    - lb_endpoints:
                      - endpoint:
                          address:
                            socket_address:
                              address: 127.0.0.1
                              port_value: 8080
              listeners:
                - name: listener1
                  api_listener:
                    api_listener:
                      "@type": type.googleapis.com/envoy.extensions.filters.network\
            .http_connection_manager.v3.HttpConnectionManager
                      stat_prefix: http
                      route_config:
                        name: local_route
                        virtual_hosts:
                        - name: local_service
                          domains: [ "*" ]
                          routes:
                          - match:
                              prefix: /
                            route:
                              cluster: my-cluster
                      http_filters:
                      - name: test.custom_filter
                        typed_config:
                          "@type": type.googleapis.com/google.protobuf.Empty
                      - name: envoy.filters.http.router
                        typed_config:
                          "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
            """;

    /**
     * Verifies that subscribing to {@code genericSecretStream} from a non-event-loop thread
     * triggers an {@link IllegalStateException} from {@code checkSubscribeOn}.
     */
    @Test
    void strictCheckPropagatesError() {
        final CompletableFuture<Void> subscribeOffEventLoop = new CompletableFuture<>();
        final HttpFilterFactory offThreadFactory = new HttpFilterFactory() {
            @Override
            public String name() {
                return CUSTOM_FILTER_NAME;
            }

            @Override
            public List<String> typeUrls() {
                return ImmutableList.of(CUSTOM_FILTER_TYPE_URL);
            }

            @Nullable
            @Override
            public XdsHttpFilter create(HttpFilter httpFilter, Any config, FactoryContext context) {
                throw new UnsupportedOperationException();
            }

            @Override
            public SnapshotStream<XdsHttpFilter> createStream(
                    HttpFilter httpFilter, Any config, FactoryContext context) {
                return watcher -> {
                    otherEventLoop.get().execute(() -> {
                        try {
                            // Subscribe to genericSecretStream from a different event loop.
                            // checkSubscribeOn should throw IllegalStateException.
                            final Subscription unused = context.genericSecretStream(
                                    SdsSecretConfig.newBuilder()
                                                   .setName("nonexistent")
                                                   .build()
                            ).subscribe((value, error) -> {});
                            subscribeOffEventLoop.complete(null);
                        } catch (Throwable t) {
                            subscribeOffEventLoop.completeExceptionally(t);
                        }
                    });
                    return Subscription.noop();
                };
            }
        };

        final Bootstrap bootstrap = XdsResourceReader.fromYaml(BOOTSTRAP_YAML);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .eventExecutor(xdsEventLoop.get())
                                                     .extensionFactory(offThreadFactory)
                                                     .build()) {
            final ListenerRoot root = xdsBootstrap.listenerRoot(LISTENER_NAME);
            try {
                await().untilAsserted(() -> assertThat(subscribeOffEventLoop).isCompletedExceptionally());
                assertThat(subscribeOffEventLoop)
                        .failsWithin(0, TimeUnit.SECONDS)
                        .withThrowableThat()
                        .withCauseInstanceOf(IllegalStateException.class)
                        .withMessageContaining("subscribe must be called from the event loop");
            } finally {
                root.close();
            }
        }
    }

    /**
     * Verifies that a custom filter factory emitting from a non-event-loop thread
     * still delivers events successfully, thanks to {@code rescheduleEventsOn}.
     */
    @Test
    void reschedulingDeliversEventsFromOffThread() {
        final HttpFilterFactory offThreadEmitterFactory = new HttpFilterFactory() {
            @Override
            public String name() {
                return CUSTOM_FILTER_NAME;
            }

            @Override
            public List<String> typeUrls() {
                return ImmutableList.of(CUSTOM_FILTER_TYPE_URL);
            }

            @Nullable
            @Override
            public XdsHttpFilter create(HttpFilter httpFilter, Any config, FactoryContext context) {
                throw new UnsupportedOperationException();
            }

            @Override
            public SnapshotStream<XdsHttpFilter> createStream(
                    HttpFilter httpFilter, Any config, FactoryContext context) {
                return watcher -> {
                    otherEventLoop.get().execute(() -> {
                        // Emit a no-op filter from a different event loop.
                        // Without rescheduleEventsOn this would be a threading violation.
                        watcher.onUpdate(new XdsHttpFilter() {}, null);
                    });
                    return Subscription.noop();
                };
            }
        };

        final List<Object> snapshots = new CopyOnWriteArrayList<>();
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        final SnapshotWatcher<Object> defaultWatcher = (snapshot, error) -> {
            if (snapshot != null) {
                snapshots.add(snapshot);
            }
            if (error != null) {
                errors.add(error);
            }
        };

        final Bootstrap bootstrap = XdsResourceReader.fromYaml(BOOTSTRAP_YAML);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .eventExecutor(xdsEventLoop.get())
                                                     .extensionFactory(offThreadEmitterFactory)
                                                     .defaultSnapshotWatcher(defaultWatcher)
                                                     .build()) {
            final ListenerRoot root = xdsBootstrap.listenerRoot(LISTENER_NAME);
            try {
                await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                    assertThat(snapshots).isNotEmpty();
                });
                assertThat(errors).isEmpty();
            } finally {
                root.close();
            }
        }
    }
}
