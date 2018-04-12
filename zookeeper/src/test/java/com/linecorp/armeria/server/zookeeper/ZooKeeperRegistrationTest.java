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
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.zookeeper.ZooKeeperTestBase;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.common.zookeeper.NodeValueCodec;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerListener;
import com.linecorp.armeria.server.ServiceRequestContext;

import zookeeperjunit.CloseableZooKeeper;

public class ZooKeeperRegistrationTest extends ZooKeeperTestBase {

    @Nullable
    private List<Server> servers;

    @Before
    public void startServers() {
        servers = new ArrayList<>();

        for (Endpoint endpoint : sampleEndpoints) {
            final Server server = new ServerBuilder().http(endpoint.port())
                                                     .service("/", new EchoService())
                                                     .build();
            final ServerListener listener = new ZooKeeperUpdatingListenerBuilder(
                    instance().connectString().get(), zNode)
                    .sessionTimeoutMillis(sessionTimeoutMillis)
                    .endpoint(endpoint)
                    .build();
            server.addListener(listener);
            server.start().join();
            servers.add(server);
        }
    }

    @After
    public void stopServers() {
        if (servers != null) {
            servers.forEach(s -> s.stop().join());
        }
    }

    @Test(timeout = 30000)
    public void testServerNodeCreateAndDelete() {
        //all servers start and with zNode created
        await().untilAsserted(() -> sampleEndpoints.forEach(
                endpoint -> assertExists(zNode + '/' + endpoint.host() + '_' + endpoint.port())));

        try (CloseableZooKeeper zk = connection()) {
            try {
                sampleEndpoints.forEach(endpoint -> {
                    try {
                        assertThat(NodeValueCodec.DEFAULT.decode(zk.getData(
                                zNode + '/' + endpoint.host() + '_' + endpoint.port()).get()))
                                .isEqualTo(endpoint);
                    } catch (Throwable throwable) {
                        fail(throwable.getMessage());
                    }
                });
                //stop one server and check its ZooKeeper node
                if (servers.size() > 1) {
                    servers.get(0).stop().get();
                    servers.remove(0);

                    int removed = 0;
                    int remaining = 0;

                    for (Endpoint endpoint : sampleEndpoints) {
                        try {
                            final String key = zNode + '/' + endpoint.host() + '_' + endpoint.port();
                            if (zk.exists(key).get()) {
                                remaining++;
                            } else {
                                removed++;
                            }
                        } catch (Throwable throwable) {
                            fail(throwable.getMessage());
                        }
                    }

                    assertThat(removed).isOne();
                    assertThat(remaining).isEqualTo(sampleEndpoints.size() - 1);
                }
            } catch (Throwable throwable) {
                fail(throwable.getMessage());
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

        protected HttpResponse echo(AggregatedHttpMessage aReq) {
            return HttpResponse.of(
                    HttpHeaders.of(HttpStatus.OK),
                    aReq.content());
        }
    }
}
