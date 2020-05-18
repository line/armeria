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
package com.linecorp.armeria.server.zookeeper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CompletableFuture;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.x.discovery.ServiceInstance;
import org.apache.curator.x.discovery.ServiceType;
import org.apache.curator.x.discovery.UriSpec;
import org.apache.curator.x.discovery.details.JsonInstanceSerializer;
import org.apache.curator.x.discovery.details.ServiceDiscoveryImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.cloud.zookeeper.discovery.ZookeeperInstance;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.zookeeper.DiscoverySpec;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.zookeeper.ZooKeeperExtension;
import com.linecorp.armeria.common.zookeeper.ZooKeeperTestUtil;
import com.linecorp.armeria.server.Server;

import zookeeperjunit.CloseableZooKeeper;

class CuratorServiceDiscoveryCompatibilityTest {

    private static final String Z_NODE = "/testEndPoints";
    private static final UriSpec CURATOR_X_URI_SPEC = new UriSpec("{scheme}://{address}:{port}");

    @RegisterExtension
    static ZooKeeperExtension zkInstance = new ZooKeeperExtension();

    @Test
    void registeredInstancesAreSameWhenUsingServiceDiscoveryImplAndUpdatingListener() throws Throwable {
        final Endpoint endpoint = ZooKeeperTestUtil.sampleEndpoints(1).get(0);
        final CuratorFramework client =
                CuratorFrameworkFactory.builder()
                                       .connectString(zkInstance.connectString())
                                       .retryPolicy(new ExponentialBackoffRetry(1000, 3))
                                       .build();
        client.start();
        final JsonInstanceSerializer<ZookeeperInstance> serializer =
                new JsonInstanceSerializer<>(ZookeeperInstance.class);
        final ServiceInstance<ZookeeperInstance> registered = serviceInstance(endpoint);
        final ServiceDiscoveryImpl<ZookeeperInstance> serviceDiscovery =
                new ServiceDiscoveryImpl<>(client, Z_NODE, serializer, registered, false);
        serviceDiscovery.start();
        assertInstance(registered);
        serviceDiscovery.close();
        await().untilAsserted(() -> zkInstance.assertNotExists(Z_NODE + "/foo/bar"));

        final InstanceSpec instanceSpec =
                InstanceSpec.curatorXInstanceBuilder("foo")
                            .serviceId("bar")
                            .serviceAddress("foo.com")
                            .port(endpoint.port())
                            .payload(new ZookeeperInstance("a", "b", ImmutableMap.of()))
                            .uriSpec(CURATOR_X_URI_SPEC)
                            .build();

        final ZooKeeperUpdatingListener listener =
                ZooKeeperUpdatingListener.builder(zkInstance.connectString(), Z_NODE, instanceSpec).build();
        final Server server = Server.builder()
                                    .http(endpoint.port())
                                    .service("/", (ctx, req) -> HttpResponse.of(200))
                                    .build();
        server.addListener(listener);
        server.start().join();
        assertInstance(registered);
        server.stop().join();
        client.close();
    }

    private static void assertInstance(ServiceInstance<ZookeeperInstance> registered) throws Throwable {
        await().untilAsserted(() -> zkInstance.assertExists(Z_NODE + "/foo/bar"));

        final CompletableFuture<ServiceInstance<?>> instanceCaptor = new CompletableFuture<>();
        try (CloseableZooKeeper zk = zkInstance.connection()) {
            final DiscoverySpec discoverySpec = DiscoverySpec.curatorXBuilder("foo")
                                                             .converter(serviceInstance -> {
                                                                 instanceCaptor.complete(serviceInstance);
                                                                 return null;
                                                             }).build();
            discoverySpec.decode(zk.getData(Z_NODE + "/foo/bar").get());
            final ServiceInstance<?> actual = instanceCaptor.join();
            assertThat(actual).isEqualToIgnoringGivenFields(registered, "registrationTimeUTC");
        }
    }

    private static ServiceInstance<ZookeeperInstance> serviceInstance(Endpoint endpoint) {
        return new ServiceInstance<>("foo", "bar", "foo.com", endpoint.port(), null,
                                     new ZookeeperInstance("a", "b", ImmutableMap.of()), 0,
                                     ServiceType.DYNAMIC, CURATOR_X_URI_SPEC, true);
    }
}
