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

import static com.linecorp.armeria.common.zookeeper.ZooKeeperTestUtil.startServerWithRetries;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableMap;
import com.twitter.finagle.common.zookeeper.ServerSet.EndpointStatus;
import com.twitter.finagle.common.zookeeper.ServerSetImpl;
import com.twitter.finagle.common.zookeeper.ZooKeeperClient;
import com.twitter.util.Duration;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.zookeeper.ServerSetsInstance;
import com.linecorp.armeria.common.zookeeper.ZooKeeperExtension;
import com.linecorp.armeria.common.zookeeper.ZooKeeperTestUtil;
import com.linecorp.armeria.internal.common.zookeeper.ServerSetsNodeValueCodec;
import com.linecorp.armeria.internal.testing.FlakyTest;
import com.linecorp.armeria.server.Server;

import zookeeperjunit.CloseableZooKeeper;

@FlakyTest
class ServerSetRegistrationTest {

    private static final String Z_NODE = "/testEndPoints";

    @RegisterExtension
    static ZooKeeperExtension zkInstance = new ZooKeeperExtension();

    @Test
    void serverSetImplCompatible() throws Throwable {
        final List<Endpoint> endpoints = ZooKeeperTestUtil.sampleEndpoints(2);
        final ZooKeeperClient zooKeeperClient =
                new ZooKeeperClient(Duration.fromSeconds(20),
                                    InetSocketAddress.createUnresolved("127.0.0.1", zkInstance.port()));
        final ServerSetImpl serverSet = new ServerSetImpl(zooKeeperClient, Z_NODE);
        final Map<String, InetSocketAddress> additionals = ImmutableMap.of(
                "foo", InetSocketAddress.createUnresolved("127.0.0.1", endpoints.get(1).port()));
        final EndpointStatus endpointStatus =
                serverSet.join(InetSocketAddress.createUnresolved("127.0.0.1", 1),
                               additionals, -100, ImmutableMap.of("bar", "baz"));

        final byte[] serverSetImplBytes;
        try (CloseableZooKeeper zk = zkInstance.connection()) {
            serverSetImplBytes = zk.getData(Z_NODE + "/member_0000000000").get();
        }

        endpointStatus.leave();
        await().untilAsserted(() -> zkInstance.assertNotExists(Z_NODE + "/member_0000000000"));

        final ServerSetsRegistrationSpecBuilder specBuilder =
                ZooKeeperRegistrationSpec.builderForServerSets();
        final ZooKeeperRegistrationSpec spec =
                specBuilder.serviceEndpoint(Endpoint.of("127.0.0.1", 1))
                           .additionalEndpoint("foo", Endpoint.of("127.0.0.1", endpoints.get(1).port()))
                           .shardId(-100)
                           .metadata(ImmutableMap.of("bar", "baz"))
                           .build();

        final ZooKeeperUpdatingListener listener =
                ZooKeeperUpdatingListener.builder(zkInstance.connectString(), Z_NODE, spec).build();
        final Server server = Server.builder()
                                    .serverListener(listener)
                                    .tlsSelfSigned()
                                    .http(endpoints.get(0).port())
                                    .https(endpoints.get(1).port())
                                    .service("/", (ctx, req) -> HttpResponse.of(200))
                                    .build();
        startServerWithRetries(server);

        final byte[] updatingListenerBytes;
        try (CloseableZooKeeper zk = zkInstance.connection()) {
            updatingListenerBytes = zk.getData(Z_NODE + "/member_0000000001").get();
        }
        assertThat(updatingListenerBytes).isEqualTo(serverSetImplBytes);
        final ServerSetsInstance decoded = ServerSetsNodeValueCodec.INSTANCE.decode(
                updatingListenerBytes);
        final ServerSetsInstance expected = new ServerSetsInstance(
                // The specified port number is used although the port is not actually used.
                Endpoint.of("127.0.0.1", 1),
                ImmutableMap.of("foo", Endpoint.of("127.0.0.1", endpoints.get(1).port())),
                -100,
                ImmutableMap.of("bar", "baz"));
        assertThat(decoded).isEqualTo(expected);

        server.stop().join();
        await().untilAsserted(() -> zkInstance.assertNotExists(Z_NODE + "/member_0000000001"));
    }

    @Test
    void noSequential() throws Throwable {
        final List<Endpoint> endpoints = ZooKeeperTestUtil.sampleEndpoints(1);
        final ServerSetsRegistrationSpecBuilder specBuilder =
                ZooKeeperRegistrationSpec.builderForServerSets();
        final ZooKeeperRegistrationSpec spec =
                specBuilder.serviceEndpoint(Endpoint.of("127.0.0.1", endpoints.get(0).port()))
                           .nodeName("foo")
                           .sequential(false)
                           .build();
        final ZooKeeperUpdatingListener listener =
                ZooKeeperUpdatingListener.builder(zkInstance.connectString(), Z_NODE, spec).build();
        final Server server = Server.builder()
                                    .serverListener(listener)
                                    .http(endpoints.get(0).port())
                                    .service("/", (ctx, req) -> HttpResponse.of(200))
                                    .build();
        server.start().join();

        try (CloseableZooKeeper zk = zkInstance.connection()) {
            // nodeName is not sequential.
            await().untilAsserted(() -> zkInstance.assertExists(Z_NODE + "/foo"));
        }
        server.stop().join();
        await().untilAsserted(() -> zkInstance.assertNotExists(Z_NODE + "/foo"));
    }
}
