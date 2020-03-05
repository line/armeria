/*
 * Copyright 2016 LINE Corporation
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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.common.zookeeper.NodeValueCodec;
import com.linecorp.armeria.common.zookeeper.ZooKeeperInstanceExtension;
import com.linecorp.armeria.common.zookeeper.ZooKeeperTestUtil;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerListener;
import com.linecorp.armeria.server.ServiceRequestContext;

import zookeeperjunit.CloseableZooKeeper;

class ZooKeeperRegistrationTest {

    private static final String Z_NODE = "/testEndPoints";
    private static final int SESSION_TIMEOUT_MILLIS = 20000;
    private static final Set<Endpoint> sampleEndpoints = ZooKeeperTestUtil.sampleEndpoints();

    @RegisterExtension
    static ZooKeeperInstanceExtension zkInstance = new ZooKeeperInstanceExtension();
    @Nullable
    private List<Server> servers;

    @BeforeEach
    void startServers() {
        servers = new ArrayList<>();

        for (Endpoint endpoint : sampleEndpoints) {
            final Server server = Server.builder()
                                        .http(endpoint.port())
                                        .service("/", new EchoService())
                                        .build();
            final ServerListener listener =
                    ZooKeeperUpdatingListener.builder(zkInstance.connectString(), Z_NODE)
                                             .sessionTimeoutMillis(SESSION_TIMEOUT_MILLIS)
                                             .endpoint(endpoint)
                                             .build();
            server.addListener(listener);
            server.start().join();
            servers.add(server);
        }
    }

    @AfterEach
    void stopServers() {
        if (servers != null) {
            servers.forEach(s -> s.stop().join());
        }
    }

    @Test
    void testServerNodeCreateAndDelete() throws Throwable {
        //all servers start and with zNode created
        await().untilAsserted(() -> sampleEndpoints.forEach(
                endpoint -> zkInstance.assertExists(Z_NODE + '/' + endpoint.host() + '_' + endpoint.port())));

        try (CloseableZooKeeper zk = zkInstance.connection()) {
            for (Endpoint sampleEndpoint : sampleEndpoints) {
                assertThat(NodeValueCodec.ofDefault().decode(zk.getData(
                        Z_NODE + '/' + sampleEndpoint.host() + '_' + sampleEndpoint.port()).get()))
                        .isEqualTo(sampleEndpoint);
            }
            //stop one server and check its ZooKeeper node
            if (servers.size() > 1) {
                servers.get(0).stop().get();
                servers.remove(0);

                int removed = 0;
                int remaining = 0;

                for (Endpoint endpoint : sampleEndpoints) {
                    final String key = Z_NODE + '/' + endpoint.host() + '_' + endpoint.port();
                    if (zk.exists(key).get()) {
                        remaining++;
                    } else {
                        removed++;
                    }
                }

                assertThat(removed).isOne();
                assertThat(remaining).isEqualTo(sampleEndpoints.size() - 1);
            }
        }
    }

    private static class EchoService extends AbstractHttpService {
        @Override
        protected final HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) {
            return HttpResponse.from(req.aggregate()
                                        .thenApply(this::echo)
                                        .exceptionally(CompletionActions::log));
        }

        protected HttpResponse echo(AggregatedHttpRequest aReq) {
            return HttpResponse.of(ResponseHeaders.of(HttpStatus.OK), aReq.content());
        }
    }
}
