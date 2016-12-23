/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.common.zookeeper;

import static org.assertj.core.api.Assertions.fail;
import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooKeeper;
import org.assertj.core.api.Assertions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.http.AggregatedHttpMessage;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponseWriter;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.http.AbstractHttpService;
import com.linecorp.armeria.server.zookeeper.listener.ZooKeeperListener;

import junitextensions.OptionAssert;
import zookeeperjunit.ZooKeeperAssert;

public class ServerZKConnectorTest extends TestBase implements ZooKeeperAssert, OptionAssert {

    protected static final KeeperState[] expectedStates = {
            KeeperState.Disconnected, KeeperState.Expired,
            KeeperState.SyncConnected, KeeperState.SyncConnected, KeeperState.Disconnected
    };
    List<Server> servers;
    List<ZKConnector> zkConnectors;
    List<ZooKeeperListener> listeners;

    @Before
    public void startServer() {
        servers = new ArrayList<>();
        zkConnectors = new ArrayList<>();
        listeners = new ArrayList<>();
        try {
            for (Endpoint endpoint : sampleEndpoints) {
                ServerBuilder sb = new ServerBuilder();
                Server server = sb.serviceAt("/", new EchoService()).port(endpoint.port(), SessionProtocol.HTTP)
                                  .build();
                ZooKeeperListener listener;
                listener = new ZooKeeperListener(instance().connectString().get(), zNode,
                                                 sessionTimeout,
                                                 endpoint);
                server.addListener(listener);
                server.start().get();
                listeners.add(listener);
                zkConnectors.add(listener.getConnector());
                servers.add(server);
            }
        } catch (Exception e) {
            fail(e.getMessage());
        }
    }

    @Test
    public void testServerNodeCreateAndDelete() {
        //all servers start and with zNode created
        sampleEndpoints.forEach(
                endpoint -> assertExists(zNode + '/' + endpoint.host() + '_' + endpoint.port()));
        instance().connect().forEach(zkClient -> {
            try {
                sampleEndpoints.forEach(endpoint -> {
                    try {
                        Assertions.assertThat(NODE_VALUE_CODEC.decode(
                                zkClient.getData(zNode + '/' + endpoint.host() + '_' + endpoint.port()).get()))
                                  .isEqualTo(
                                          endpoint);
                    } catch (Throwable throwable) {
                        fail(throwable.getMessage());
                    }
                });
                //stop one server and check its ZooKeeper node
                if (servers.size() > 1) {
                    servers.get(0).stop().get();
                    servers.remove(0);
                    zkConnectors.remove(0);
                    ZooKeeperListener stoppedServerListener = listeners.remove(0);
                    assertNotExists(zNode + '/' + stoppedServerListener.getEndpoint().host() + '_' +
                                    stoppedServerListener.getEndpoint().port());
                    //the other server will not influenced
                    assertExists(zNode + '/' + listeners.get(0).getEndpoint().host() + '_' +
                                 listeners.get(0).getEndpoint().port());
                }
            } catch (Throwable throwable) {
                fail(throwable.getMessage());
            }
        });
    }

    /**
     * suppose we delete a normal server's ZooKeeper node, it will recover automatically.
     */
    @Test
    public void testNodeRecover() {
        Endpoint sampleEndpoint = listeners.get(0).getEndpoint();
        instance().connect().forEach(
                zkClient -> zkClient.delete(zNode + '/' + sampleEndpoint.host() + '_' + sampleEndpoint.port()));
        try {
            //wait few seconds to let the server recover automatically
            Thread.sleep(2 * 1000);
        } catch (InterruptedException e) {
            fail(e.getMessage());
        }
        assertExists(zNode + '/' + sampleEndpoint.host() + '_' + sampleEndpoint.port());
    }

    @Test
    public void testConnectionRecovery() throws Exception {
        ZKConnector zkConnector = zkConnectors.get(0);
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
        for (KeeperState state : expectedStates) {
            assertEquals(state, zkConnector.stateQueue().take());
        }
        //connection will recover and our ZooKeeper node also exists
        testServerNodeCreateAndDelete();
    }

    @After
    public void stopServer() {
        try {
            for (Server server : servers) {
                server.stop().get();
            }
        } catch (Exception ex) {
            fail(ex.getMessage());
        }
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
