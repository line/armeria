/*
 * Copyright 2021 LINE Corporation
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
import static org.awaitility.Awaitility.await;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.x.discovery.UriSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.zookeeper.ZooKeeperExtension;
import com.linecorp.armeria.common.zookeeper.ZooKeeperTestUtil;
import com.linecorp.armeria.server.Server;

public class CuratorServiceExternalClientUsageTest {

    private static final String Z_NODE = "/testEndPoints";
    private static final UriSpec CURATOR_X_URI_SPEC = new UriSpec("{scheme}://{address}:{port}");

    @RegisterExtension
    static ZooKeeperExtension zkInstance = new ZooKeeperExtension();

    @Test
    void updatingListenerWithExternalClient() {
        final Endpoint endpoint = ZooKeeperTestUtil.sampleEndpoints(1).get(0);
        final CuratorFramework client =
                CuratorFrameworkFactory.builder()
                                       .connectString(zkInstance.connectString())
                                       .retryPolicy(new ExponentialBackoffRetry(1000, 3))
                                       .build();
        client.start();

        final ZooKeeperRegistrationSpec registrationSpec =
                ZooKeeperRegistrationSpec.builderForCurator("foo")
                                         .serviceId("bar")
                                         .serviceAddress("foo.com")
                                         .port(endpoint.port())
                                         .uriSpec(CURATOR_X_URI_SPEC)
                                         .build();

        final ZooKeeperUpdatingListener listener =
                ZooKeeperUpdatingListener.builder(client, Z_NODE, registrationSpec).build();
        final Server server = Server.builder()
                                    .http(endpoint.port())
                                    .service("/", (ctx, req) -> HttpResponse.of(200))
                                    .build();
        server.addListener(listener);
        startServerWithRetries(server);
        await().untilAsserted(() -> zkInstance.assertExists(Z_NODE + "/foo/bar"));
        server.stop().join();
        client.close();
    }
}
