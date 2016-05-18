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

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.service.test.thrift.main.HelloService;

import io.netty.util.internal.PlatformDependent;

public class StaticEndpointGroupTest {
    private static final HelloService.Iface HELLO_SERVICE_HANDLER_ONE = dump -> "host:127.0.0.1:1234";
    private static final HelloService.Iface HELLO_SERVICE_HANDLER_TWO = dump -> "host:127.0.0.1:2345";
    private static final HelloService.Iface HELLO_SERVICE_HANDLER_THREE = dump -> "host:127.0.0.1:3456";

    @Rule
    public TestName name = new TestName();

    private static class ServiceServer {
        private Server server;
        private final HelloService.Iface handler;
        private final int port;

        ServiceServer(HelloService.Iface handler, int port) {
            this.handler = handler;
            this.port = port;
        }

        private void configureServer() throws Exception {
            final ServerBuilder sb = new ServerBuilder();

            THttpService ipService = THttpService.of(handler);

            sb.serviceAt("/serverIp", ipService);
            sb.port(port, SessionProtocol.HTTP);

            server = sb.build();

            try {
                server.start().get();
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

    @Test
    public void testRoundRobinSelect() {
        EndpointGroup endpointGroup = new StaticEndpointGroup(
                Endpoint.of("127.0.0.1", 1234),
                Endpoint.of("127.0.0.1", 2345),
                Endpoint.of("127.0.0.1", 3456));
        String groupName = name.getMethodName();

        EndpointGroupRegistry.register(groupName, endpointGroup, EndpointSelectionStrategy.WEIGHTED_ROUND_ROBIN);


        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:1234"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:2345"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:3456"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:1234"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:2345"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:3456"));
    }

    @Test
    public void testWeightedRoundRobinSelect() {
        //weight 1,2,3
        EndpointGroup endpointGroup = new StaticEndpointGroup(
                Endpoint.of("127.0.0.1", 1234, 1),
                Endpoint.of("127.0.0.1", 2345, 2),
                Endpoint.of("127.0.0.1", 3456, 3));
        String groupName = name.getMethodName();

        EndpointGroupRegistry.register(groupName, endpointGroup, EndpointSelectionStrategy.WEIGHTED_ROUND_ROBIN);

        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:1234"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:2345"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:3456"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:2345"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:3456"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:3456"));

        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:1234"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:2345"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:3456"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:2345"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:3456"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:3456"));

        //weight 3,2,2
        EndpointGroup endpointGroup2 = new StaticEndpointGroup(
                Endpoint.of("127.0.0.1", 1234, 3),
                Endpoint.of("127.0.0.1", 2345, 2),
                Endpoint.of("127.0.0.1", 3456, 2));
        EndpointGroupRegistry.replace(groupName, endpointGroup2, EndpointSelectionStrategy.WEIGHTED_ROUND_ROBIN);

        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:1234"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:2345"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:3456"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:1234"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:2345"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:3456"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:1234"));

        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:1234"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:2345"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:3456"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:1234"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:2345"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:3456"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:1234"));

        //weight 4,4,4
        EndpointGroup endpointGroup3 = new StaticEndpointGroup(
                Endpoint.of("127.0.0.1", 1234, 4),
                Endpoint.of("127.0.0.1", 2345, 4),
                Endpoint.of("127.0.0.1", 3456, 4));
        EndpointGroupRegistry.replace(groupName, endpointGroup3, EndpointSelectionStrategy.WEIGHTED_ROUND_ROBIN);

        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:1234"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:2345"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:3456"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:1234"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:2345"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:3456"));

        //weight 2,4,6
        EndpointGroup endpointGroup4 = new StaticEndpointGroup(
                Endpoint.of("127.0.0.1", 1234, 2),
                Endpoint.of("127.0.0.1", 2345, 4),
                Endpoint.of("127.0.0.1", 3456, 6));
        EndpointGroupRegistry.replace(groupName, endpointGroup4, EndpointSelectionStrategy.WEIGHTED_ROUND_ROBIN);

        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:1234"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:2345"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:3456"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:1234"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:2345"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:3456"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:2345"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:3456"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:2345"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:3456"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:3456"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:3456"));
        //new round
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:1234"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:2345"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:3456"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:1234"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:2345"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:3456"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:2345"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:3456"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:2345"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:3456"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:3456"));
        assertThat(EndpointGroupRegistry.selectNode(groupName).authority(), is("127.0.0.1:3456"));

    }

    @Test
    public void testRoundRobinServerGroup() throws Exception {
        ServiceServer serverOne = new ServiceServer(HELLO_SERVICE_HANDLER_ONE, 1234);
        serverOne.start();

        ServiceServer serverTwo = new ServiceServer(HELLO_SERVICE_HANDLER_TWO, 2345);
        serverTwo.start();

        ServiceServer serverThree = new ServiceServer(HELLO_SERVICE_HANDLER_THREE, 3456);
        serverThree.start();

        EndpointGroup endpointGroup = new StaticEndpointGroup(
                Endpoint.of("127.0.0.1", 1234),
                Endpoint.of("127.0.0.1", 2345),
                Endpoint.of("127.0.0.1", 3456));
        String groupName = name.getMethodName();
        String endpointGroupMark = "group:";

        EndpointGroupRegistry.register(groupName, endpointGroup, EndpointSelectionStrategy.WEIGHTED_ROUND_ROBIN);

        HelloService.Iface ipService = Clients.newClient("ttext+http://" + endpointGroupMark + groupName + "/serverIp",
                HelloService.Iface.class);
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:1234"));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:2345"));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:3456"));

        StaticEndpointGroup serverGroup2 = new StaticEndpointGroup(
                Endpoint.of("127.0.0.1", 1234, 2),
                Endpoint.of("127.0.0.1", 2345, 4),
                Endpoint.of("127.0.0.1", 3456, 2));

        EndpointGroupRegistry.replace(groupName, serverGroup2, EndpointSelectionStrategy.WEIGHTED_ROUND_ROBIN);

        ipService = Clients.newClient("tbinary+http://" + endpointGroupMark + groupName + "/serverIp",
                HelloService.Iface.class);
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:1234"));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:2345"));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:3456"));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:1234"));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:2345"));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:3456"));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:2345"));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:2345"));


        //new round
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:1234"));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:2345"));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:3456"));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:1234"));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:2345"));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:3456"));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:2345"));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:2345"));

        //direct connect to ip host
        ipService = Clients.newClient("tbinary+http://127.0.0.1:1234/serverIp",
                HelloService.Iface.class);
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:1234"));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:1234"));

        serverOne.stop();
        serverTwo.stop();
        serverThree.stop();
    }
}
