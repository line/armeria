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
package com.linecorp.armeria.client.routing;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.thrift.ThriftService;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import io.netty.util.internal.PlatformDependent;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

public class StaticEndpointGroupTest {
    private static final HelloService.Iface HELLO_SERVICE_HANDLER_ONE = (dump) -> "host:127.0.0.1:1234";
    private static final HelloService.Iface HELLO_SERVICE_HANDLER_TWO = (dump) -> "host:127.0.0.1:2345";
    private static final HelloService.Iface HELLO_SERVICE_HANDLER_THREE = (dump) -> "host:127.0.0.1:3456";

    @Rule
    public TestName name = new TestName();

    private class ServiceServer {
        private Server server;
        private HelloService.Iface handler;
        private int port;

        public ServiceServer(HelloService.Iface handler, int port) {
            this.handler = handler;
            this.port = port;
        }

        private void configureServer() throws Exception {
            final ServerBuilder sb = new ServerBuilder();

            ThriftService ipService = ThriftService.of(handler);

            sb.serviceAt("/serverIp", ipService);
            sb.port(this.port, SessionProtocol.HTTP);

            server = sb.build();

            try {
                server.start().sync();
            } catch (InterruptedException e) {
                PlatformDependent.throwException(e);
            }

        }

        public void start() throws Exception {
            configureServer();
        }

        public void stop() {
            server.stop();
        }
    }

    private String nodeAddress(Endpoint endpoint) {
        return endpoint.hostname() + ":" + endpoint.port();
    }


    @Test
    public void testRoundRobinSelect() {
        EndpointGroup<WeightedEndpoint> endpointGroup = new StaticEndpointGroup<>(
                new DefaultWeightedEndpoint("127.0.0.1", 1234),
                new DefaultWeightedEndpoint("127.0.0.1", 2345),
                new DefaultWeightedEndpoint("127.0.0.1", 3456));
        String groupName = name.getMethodName();

        EndpointGroupRegistry.register(groupName, endpointGroup, EndpointSelectionStrategy.WEIGHTED_ROUND_ROBIN);

        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:1234"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:2345"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:3456"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:1234"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:2345"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:3456"), is(true));

    }

    @Test
    public void testWeightedRoundRobinSelect() {
        //weight 1,2,3
        EndpointGroup<WeightedEndpoint> endpointGroup = new StaticEndpointGroup<>(
                new DefaultWeightedEndpoint("127.0.0.1", 1234, 1),
                new DefaultWeightedEndpoint("127.0.0.1", 2345, 2),
                new DefaultWeightedEndpoint("127.0.0.1", 3456, 3));
        String groupName = name.getMethodName();

        EndpointGroupRegistry.register(groupName, endpointGroup, EndpointSelectionStrategy.WEIGHTED_ROUND_ROBIN);

        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:1234"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:2345"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:3456"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:2345"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:3456"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:3456"), is(true));

        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:1234"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:2345"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:3456"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:2345"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:3456"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:3456"), is(true));

        //weight 3,2,2
        EndpointGroup<WeightedEndpoint> endpointGroup2 = new StaticEndpointGroup<>(
                new DefaultWeightedEndpoint("127.0.0.1", 1234, 3),
                new DefaultWeightedEndpoint("127.0.0.1", 2345, 2),
                new DefaultWeightedEndpoint("127.0.0.1", 3456, 2));
        EndpointGroupRegistry.replace(groupName, endpointGroup2, EndpointSelectionStrategy.WEIGHTED_ROUND_ROBIN);

        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:1234"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:2345"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:3456"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:1234"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:2345"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:3456"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:1234"), is(true));

        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:1234"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:2345"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:3456"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:1234"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:2345"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:3456"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:1234"), is(true));

        //weight 4,4,4
        EndpointGroup<WeightedEndpoint> endpointGroup3 = new StaticEndpointGroup<>(
                new DefaultWeightedEndpoint("127.0.0.1", 1234, 4),
                new DefaultWeightedEndpoint("127.0.0.1", 2345, 4),
                new DefaultWeightedEndpoint("127.0.0.1", 3456, 4));
        EndpointGroupRegistry.replace(groupName, endpointGroup3, EndpointSelectionStrategy.WEIGHTED_ROUND_ROBIN);


        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:1234"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:2345"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:3456"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:1234"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:2345"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:3456"), is(true));

        //weight 2,4,6
        EndpointGroup<WeightedEndpoint> endpointGroup4 = new StaticEndpointGroup<>(
                new DefaultWeightedEndpoint("127.0.0.1", 1234, 2),
                new DefaultWeightedEndpoint("127.0.0.1", 2345, 4),
                new DefaultWeightedEndpoint("127.0.0.1", 3456, 6));
        EndpointGroupRegistry.replace(groupName, endpointGroup4, EndpointSelectionStrategy.WEIGHTED_ROUND_ROBIN);

        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:1234"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:2345"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:3456"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:1234"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:2345"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:3456"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:2345"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:3456"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:2345"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:3456"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:3456"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:3456"), is(true));
        //new round
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:1234"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:2345"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:3456"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:1234"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:2345"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:3456"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:2345"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:3456"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:2345"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:3456"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:3456"), is(true));
        assertThat(nodeAddress(EndpointGroupRegistry.selectNode(groupName)).equals("127.0.0.1:3456"), is(true));

    }

    @Test
    public void testRoundRobinServerGroup() throws Exception {
        ServiceServer serverOne = new ServiceServer(HELLO_SERVICE_HANDLER_ONE, 1234);
        serverOne.start();

        ServiceServer serverTwo = new ServiceServer(HELLO_SERVICE_HANDLER_TWO, 2345);
        serverTwo.start();

        ServiceServer serverThree = new ServiceServer(HELLO_SERVICE_HANDLER_THREE, 3456);
        serverThree.start();

        EndpointGroup<WeightedEndpoint> endpointGroup = new StaticEndpointGroup<>(
                new DefaultWeightedEndpoint("127.0.0.1", 1234),
                new DefaultWeightedEndpoint("127.0.0.1", 2345),
                new DefaultWeightedEndpoint("127.0.0.1", 3456));
        String groupName = name.getMethodName();
        String endpointGroupMark = "group:";

        EndpointGroupRegistry.register(groupName, endpointGroup, EndpointSelectionStrategy.WEIGHTED_ROUND_ROBIN);

        HelloService.Iface ipService = Clients.newClient("ttext+http://" + endpointGroupMark + groupName + "/serverIp",
                HelloService.Iface.class);
        assertThat(ipService.hello("ip").equals("host:127.0.0.1:1234"), is(true));
        assertThat(ipService.hello("ip").equals("host:127.0.0.1:2345"), is(true));
        assertThat(ipService.hello("ip").equals("host:127.0.0.1:3456"), is(true));


        StaticEndpointGroup<WeightedEndpoint> serverGroup2 = new StaticEndpointGroup<>(
                new DefaultWeightedEndpoint("127.0.0.1", 1234, 2),
                new DefaultWeightedEndpoint("127.0.0.1", 2345, 4),
                new DefaultWeightedEndpoint("127.0.0.1", 3456, 2));

        EndpointGroupRegistry.replace(groupName, serverGroup2, EndpointSelectionStrategy.WEIGHTED_ROUND_ROBIN);

        ipService = Clients.newClient("tbinary+http://" + endpointGroupMark + groupName + "/serverIp",
                HelloService.Iface.class);
        assertThat(ipService.hello("ip").equals("host:127.0.0.1:1234"), is(true));
        assertThat(ipService.hello("ip").equals("host:127.0.0.1:2345"), is(true));
        assertThat(ipService.hello("ip").equals("host:127.0.0.1:3456"), is(true));
        assertThat(ipService.hello("ip").equals("host:127.0.0.1:1234"), is(true));
        assertThat(ipService.hello("ip").equals("host:127.0.0.1:2345"), is(true));
        assertThat(ipService.hello("ip").equals("host:127.0.0.1:3456"), is(true));
        assertThat(ipService.hello("ip").equals("host:127.0.0.1:2345"), is(true));
        assertThat(ipService.hello("ip").equals("host:127.0.0.1:2345"), is(true));


        //new round
        assertThat(ipService.hello("ip").equals("host:127.0.0.1:1234"), is(true));
        assertThat(ipService.hello("ip").equals("host:127.0.0.1:2345"), is(true));
        assertThat(ipService.hello("ip").equals("host:127.0.0.1:3456"), is(true));
        assertThat(ipService.hello("ip").equals("host:127.0.0.1:1234"), is(true));
        assertThat(ipService.hello("ip").equals("host:127.0.0.1:2345"), is(true));
        assertThat(ipService.hello("ip").equals("host:127.0.0.1:3456"), is(true));
        assertThat(ipService.hello("ip").equals("host:127.0.0.1:2345"), is(true));
        assertThat(ipService.hello("ip").equals("host:127.0.0.1:2345"), is(true));

        //direct connect to ip host
        ipService = Clients.newClient("tbinary+http://127.0.0.1:1234/serverIp",
                HelloService.Iface.class);
        assertThat(ipService.hello("ip").equals("host:127.0.0.1:1234"), is(true));
        assertThat(ipService.hello("ip").equals("host:127.0.0.1:1234"), is(true));

        serverOne.stop();
        serverTwo.stop();
        serverThree.stop();

    }

}
