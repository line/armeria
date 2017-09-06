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
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooKeeper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.zookeeper.TestBase;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.common.zookeeper.NodeValueCodec;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;

import junitextensions.OptionAssert;
import zookeeperjunit.CloseableZooKeeper;
import zookeeperjunit.ZooKeeperAssert;

public class ZooKeeperRegistrationTest extends TestBase implements ZooKeeperAssert, OptionAssert {

    private List<Server> servers;
    private List<ZooKeeperRegistration> zkConnectors;
    private List<ZooKeeperUpdatingListener> listeners;

    @Before
    public void startServers() throws Exception {
        servers = new ArrayList<>();
        zkConnectors = new ArrayList<>();
        listeners = new ArrayList<>();

        for (Endpoint endpoint : sampleEndpoints) {
            Server server = new ServerBuilder().port(endpoint.port(), SessionProtocol.HTTP)
                                               .service("/", new EchoService())
                                               .build();
            ZooKeeperUpdatingListener listener;
            listener = new ZooKeeperUpdatingListener(instance().connectString().get(), zNode,
                                                     sessionTimeout, endpoint);
            server.addListener(listener);
            server.start().join();
            listeners.add(listener);
            zkConnectors.add(listener.getConnector());
            servers.add(server);
        }
    }

    @After
    public void stopServers() throws Exception {
        if (servers != null) {
            servers.forEach(s -> s.stop().join());
        }
    }

    @Test(timeout = 10000)
    public void testServerNodeCreateAndDelete() {
        //all servers start and with zNode created
        sampleEndpoints.forEach(
                endpoint -> assertExists(zNode + '/' + endpoint.host() + '_' + endpoint.port()));
        try (CloseableZooKeeper zkClient = connection()) {
            try {
                sampleEndpoints.forEach(endpoint -> {
                    try {
                        assertThat(NodeValueCodec.DEFAULT.decode(zkClient.getData(
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
                    zkConnectors.remove(0);
                    ZooKeeperUpdatingListener stoppedServerListener = listeners.remove(0);
                    assertNotExists(zNode + '/' + stoppedServerListener.getEndpoint().host() + '_' +
                                    stoppedServerListener.getEndpoint().port());
                    //the other server will not influenced
                    assertExists(zNode + '/' + listeners.get(0).getEndpoint().host() + '_' +
                                 listeners.get(0).getEndpoint().port());
                }
            } catch (Throwable throwable) {
                fail(throwable.getMessage());
            }
        }
    }

    @Test(timeout = 10000)
    public void testConnectionRecovery() throws Exception {
        ZooKeeperRegistration zkConnector = zkConnectors.get(0);
        zkConnector.enableStateRecording();
        ZooKeeper zkHandler1 = zkConnector.underlyingClient();
        CountDownLatch latch = new CountDownLatch(1);
        ZooKeeper zkHandler2;

        //create a new handler with the same sessionId and password
        zkHandler2 = new ZooKeeper(instance().connectString().get(), sessionTimeout, event -> {
            if (event.getState() == KeeperState.SyncConnected) {
                latch.countDown();
            }
        }, zkHandler1.getSessionId(), zkHandler1.getSessionPasswd());
        latch.await();

        //once connected, close the new handler to cause the original handler session expire
        zkHandler2.close();

        // Ensure the state transition went as expected.
        final List<KeeperState> actualStates = takeAllStates(zkConnector.stateQueue());
        int i = 0;

        // Expect the initial disconnection events.
        int numDisconnected = 0;
        for (; i < actualStates.size(); i++) {
            if (actualStates.get(i) != KeeperState.Disconnected) {
                break;
            }
            numDisconnected++;
        }
        assertThat(numDisconnected).isGreaterThan(0);

        assertThat(actualStates.get(i++)).isEqualTo(KeeperState.Expired);
        assertThat(actualStates.get(i++)).isEqualTo(KeeperState.SyncConnected);

        // Expect the optional disconnection events.
        for (; i < actualStates.size(); i++) {
            if (actualStates.get(i) != KeeperState.Disconnected) {
                break;
            }
        }

        assertThat(actualStates.get(i++)).isEqualTo(KeeperState.SyncConnected);

        // Expect the last disconnection events.
        numDisconnected = 0;
        for (; i < actualStates.size(); i++) {
            if (actualStates.get(i) != KeeperState.Disconnected) {
                break;
            }
            numDisconnected++;
        }
        assertThat(numDisconnected).isGreaterThan(0);

        //connection will recover and our ZooKeeper node also exists
        testServerNodeCreateAndDelete();
    }

    private static class EchoService extends AbstractHttpService {
        @Override
        protected final void doPost(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
            req.aggregate()
               .thenAccept(aReq -> echo(aReq, res))
               .exceptionally(CompletionActions::log);
        }

        protected void echo(AggregatedHttpMessage aReq, HttpResponseWriter res) {
            res.write(HttpHeaders.of(HttpStatus.OK));
            res.write(aReq.content());
            res.close();
        }
    }
}
