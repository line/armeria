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
 */

package com.linecorp.armeria.xds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Duration;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;

class SotwXdsStreamTest {

    private static final Node SERVER_INFO = Node.getDefaultInstance();

    private static final String GROUP = "key";
    private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);
    private static final String clusterName = "cluster1";

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(cache);
            sb.service(GrpcService.builder()
                                  .addService(v3DiscoveryServer.getAggregatedDiscoveryServiceImpl())
                                  .build());
        }
    };

    @BeforeEach
    void beforeEach() {
        cache.setSnapshot(
                GROUP,
                Snapshot.create(
                        ImmutableList.of(createCluster(clusterName, 0)),
                        ImmutableList.of(),
                        ImmutableList.of(),
                        ImmutableList.of(),
                        ImmutableList.of(),
                        "1"));
    }

    @RegisterExtension
    static EventLoopExtension eventLoop = new EventLoopExtension();

    static class TestResponseHandler implements XdsResponseHandler {

        private final List<DiscoveryResponse> responses = new ArrayList<>();
        private final List<String> resets = new ArrayList<>();
        private final SubscriberStorage subscriberStorage;

        TestResponseHandler(SubscriberStorage subscriberStorage) {
            this.subscriberStorage = subscriberStorage;
        }

        public List<DiscoveryResponse> getResponses() {
            return responses;
        }

        public void clear() {
            responses.clear();
            resets.clear();
        }

        @Override
        public void handleResponse(ResourceParser resourceParser, DiscoveryResponse value,
                                   SotwXdsStream sender) {
            responses.add(value);
            sender.ackResponse(resourceParser.type(), value.getVersionInfo(), value.getNonce());
        }

        @Override
        public void handleReset(XdsStream sender) {
            resets.add("handleReset");
            for (XdsType type: XdsType.values()) {
                if (!subscriberStorage.subscribers(type).isEmpty()) {
                    sender.resourcesUpdated(type);
                }
            }
        }
    }

    @Test
    void basicCase() throws Exception {
        final SotwDiscoveryStub stub = SotwDiscoveryStub.ads(GrpcClients.builder(server.httpUri()));
        final Bootstrap bootstrap = XdsTestResources.bootstrap(URI.create("https://a.com"), "cluster");
        final XdsBootstrapImpl xdsBootstrap = new XdsBootstrapImpl(bootstrap);
        final WatchersStorage watchersStorage = new WatchersStorage(xdsBootstrap);
        final TestResourceWatcher watcher = new TestResourceWatcher();
        final SubscriberStorage subscriberStorage =
                new SubscriberStorage(eventLoop.get(), watchersStorage, 15_000);
        final TestResponseHandler responseHandler = new TestResponseHandler(subscriberStorage);
        try (SotwXdsStream stream = new SotwXdsStream(stub, SERVER_INFO, Backoff.ofDefault(), eventLoop.get(),
                                                      responseHandler, subscriberStorage)) {

            await().pollDelay(100, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertThat(responseHandler.getResponses()).isEmpty());

            stream.start();
            await().pollDelay(100, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertThat(responseHandler.getResponses()).isEmpty());

            subscriberStorage.register(XdsType.CLUSTER, clusterName, xdsBootstrap, watcher);
            responseHandler.handleReset(stream);

            // check if the initial cache update is done
            await().until(() -> !responseHandler.getResponses().isEmpty());
            assertThat(responseHandler.getResponses()).allSatisfy(res -> {
                final Cluster expected = cache.getSnapshot(GROUP).clusters().resources().get(clusterName);
                assertThat(res.getResources(0).unpack(Cluster.class)).isEqualTo(expected);
            });
            responseHandler.clear();

            // check if a cache update is propagated to the handler
            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(
                            ImmutableList.of(createCluster(clusterName, 1)),
                            ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                            ImmutableList.of(), "2"));

            await().until(() -> !responseHandler.getResponses().isEmpty());
            assertThat(responseHandler.getResponses()).allSatisfy(res -> {
                final Cluster expected = cache.getSnapshot(GROUP).clusters().resources().get(clusterName);
                assertThat(res.getResources(0).unpack(Cluster.class)).isEqualTo(expected);
            });
            responseHandler.clear();

            // now the stream is stopped, so no more updates
            stream.stop();
            await().until(() -> stream.requestObserver == null);

            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(
                            ImmutableList.of(createCluster(clusterName, 2)),
                            ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                            ImmutableList.of(), "3"));

            await().pollDelay(100, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertThat(responseHandler.getResponses()).isEmpty());
        }
    }

    @Test
    void restart() throws Exception {
        final SotwDiscoveryStub stub = SotwDiscoveryStub.ads(GrpcClients.builder(server.httpUri()));
        final Bootstrap bootstrap = XdsTestResources.bootstrap(URI.create("https://a.com"), "cluster");
        final XdsBootstrapImpl xdsBootstrap = new XdsBootstrapImpl(bootstrap);
        final WatchersStorage watchersStorage = new WatchersStorage(xdsBootstrap);
        final TestResourceWatcher watcher = new TestResourceWatcher();
        final SubscriberStorage subscriberStorage =
                new SubscriberStorage(eventLoop.get(), watchersStorage, 15_000);
        final TestResponseHandler responseHandler = new TestResponseHandler(subscriberStorage);

        try (SotwXdsStream stream = new SotwXdsStream(stub, SERVER_INFO, Backoff.ofDefault(), eventLoop.get(),
                                                      responseHandler, subscriberStorage)) {

            await().pollDelay(100, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertThat(responseHandler.getResponses()).isEmpty());

            subscriberStorage.register(XdsType.CLUSTER, clusterName, xdsBootstrap, watcher);
            stream.start();

            // check if the initial cache update is done
            await().until(() -> !responseHandler.getResponses().isEmpty());
            assertThat(responseHandler.getResponses()).allSatisfy(res -> {
                final Cluster expected = cache.getSnapshot(GROUP).clusters().resources().get(clusterName);
                assertThat(res.getResources(0).unpack(Cluster.class)).isEqualTo(expected);
            });
            responseHandler.clear();

            // stop the stream and verify there are no updates
            stream.stop();
            await().until(() -> stream.requestObserver == null);

            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(
                            ImmutableList.of(createCluster(clusterName, 1)),
                            ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                            ImmutableList.of(), "2"));
            await().pollDelay(100, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertThat(responseHandler.getResponses()).isEmpty());

            // restart the thread and verify that the handle receives the update
            stream.start();
            await().until(() -> !responseHandler.getResponses().isEmpty());
            assertThat(responseHandler.getResponses()).allSatisfy(res -> {
                final Cluster expected = cache.getSnapshot(GROUP).clusters().resources().get(clusterName);
                assertThat(res.getResources(0).unpack(Cluster.class)).isEqualTo(expected);
            });
        }
    }

    @Test
    void errorHandling() throws Exception {
        final SotwDiscoveryStub stub = SotwDiscoveryStub.ads(GrpcClients.builder(server.httpUri()));
        final Bootstrap bootstrap = XdsTestResources.bootstrap(URI.create("https://a.com"), "cluster");
        final XdsBootstrapImpl xdsBootstrap = new XdsBootstrapImpl(bootstrap);
        final WatchersStorage watchersStorage = new WatchersStorage(xdsBootstrap);
        final TestResourceWatcher watcher = new TestResourceWatcher();
        final SubscriberStorage subscriberStorage =
                new SubscriberStorage(eventLoop.get(), watchersStorage, 15_000);
        final AtomicInteger cntRef = new AtomicInteger();
        final CountDownLatch latch = new CountDownLatch(1);
        final TestResponseHandler responseHandler = new TestResponseHandler(subscriberStorage) {
            @Override
            public void handleResponse(ResourceParser type, DiscoveryResponse value, SotwXdsStream sender) {
                if (cntRef.getAndIncrement() < 3) {
                    throw new RuntimeException("test");
                }
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                super.handleResponse(type, value, sender);
            }
        };

        try (SotwXdsStream stream = new SotwXdsStream(stub, SERVER_INFO, Backoff.ofDefault(), eventLoop.get(),
                                                      responseHandler, subscriberStorage)) {

            await().pollDelay(100, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertThat(responseHandler.getResponses()).isEmpty());

            subscriberStorage.register(XdsType.CLUSTER, clusterName, xdsBootstrap, watcher);
            stream.start();

            await().untilAtomic(cntRef, Matchers.greaterThanOrEqualTo(3));
            assertThat(responseHandler.getResponses()).isEmpty();

            latch.countDown();
            // Once an update is done, the handler will eventually receive the new update
            await().until(() -> !responseHandler.getResponses().isEmpty());
            assertThat(responseHandler.getResponses()).allSatisfy(res -> {
                final Cluster expected = cache.getSnapshot(GROUP).clusters().resources().get(clusterName);
                assertThat(res.getResources(0).unpack(Cluster.class)).isEqualTo(expected);
            });
        }
    }

    @Test
    void nackResponse() throws Exception {
        final SotwDiscoveryStub stub = SotwDiscoveryStub.ads(GrpcClients.builder(server.httpUri()));
        final Bootstrap bootstrap = XdsTestResources.bootstrap(URI.create("https://a.com"), "cluster");
        final XdsBootstrapImpl xdsBootstrap = new XdsBootstrapImpl(bootstrap);
        final WatchersStorage watchersStorage = new WatchersStorage(xdsBootstrap);
        final TestResourceWatcher watcher = new TestResourceWatcher();
        final SubscriberStorage subscriberStorage =
                new SubscriberStorage(eventLoop.get(), watchersStorage, 15_000);
        final AtomicBoolean ackRef = new AtomicBoolean();
        final AtomicInteger nackResponses = new AtomicInteger();
        final TestResponseHandler responseHandler = new TestResponseHandler(subscriberStorage) {
            @Override
            public void handleResponse(ResourceParser resourceParser,
                                       DiscoveryResponse value, SotwXdsStream sender) {
                if (ackRef.get()) {
                    super.handleResponse(resourceParser, value, sender);
                } else {
                    nackResponses.incrementAndGet();
                    sender.nackResponse(XdsType.CLUSTER, value.getNonce(), "temporarily unavailable");
                }
            }
        };

        try (SotwXdsStream stream = new SotwXdsStream(
                     stub, SERVER_INFO, Backoff.ofDefault(), eventLoop.get(), responseHandler,
                     subscriberStorage)) {

            await().pollDelay(100, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertThat(responseHandler.getResponses()).isEmpty());

            subscriberStorage.register(XdsType.CLUSTER, clusterName, xdsBootstrap, watcher);
            stream.start();

            await().untilAtomic(nackResponses, Matchers.greaterThan(2));
            assertThat(responseHandler.getResponses()).isEmpty();
            ackRef.set(true);

            // Once an update is done, the handler will eventually receive the new update
            await().until(() -> !responseHandler.getResponses().isEmpty());
            assertThat(responseHandler.getResponses()).allSatisfy(res -> {
                final Cluster expected = cache.getSnapshot(GROUP).clusters().resources().get(clusterName);
                assertThat(res.getResources(0).unpack(Cluster.class)).isEqualTo(expected);
            });
        }
    }

    static Cluster createCluster(String clusterName, long connectTimeout) {
        return Cluster.newBuilder()
                      .setName(clusterName)
                      .setConnectTimeout(Duration.newBuilder().setSeconds(connectTimeout))
                      .build();
    }
}
