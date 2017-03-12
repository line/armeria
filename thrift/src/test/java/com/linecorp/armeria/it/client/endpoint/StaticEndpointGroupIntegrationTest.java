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

package com.linecorp.armeria.it.client.endpoint;

import static com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy.WEIGHTED_ROUND_ROBIN;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroupRegistry;
import com.linecorp.armeria.client.endpoint.StaticEndpointGroup;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.test.AbstractServiceServer;

public class StaticEndpointGroupIntegrationTest {
    @Rule
    public TestName name = new TestName();

    private static class ServiceServer extends AbstractServiceServer {
        private final HelloService.Iface handler = dump -> "host:127.0.0.1:" + port();

        @Override
        protected void configureServer(ServerBuilder sb) throws Exception {
            sb.serviceAt("/serverIp", THttpService.of(handler));
        }
    }

    @Test
    public void testRoundRobinServerGroup() throws Exception {
        ServiceServer serverOne = new ServiceServer().start();
        ServiceServer serverTwo = new ServiceServer().start();
        ServiceServer serverThree = new ServiceServer().start();

        EndpointGroup endpointGroup = new StaticEndpointGroup(
                Endpoint.of("127.0.0.1", serverOne.port()),
                Endpoint.of("127.0.0.1", serverTwo.port()),
                Endpoint.of("127.0.0.1", serverThree.port()));
        String groupName = name.getMethodName();
        String endpointGroupMark = "group:";

        EndpointGroupRegistry.register(groupName, endpointGroup, WEIGHTED_ROUND_ROBIN);

        HelloService.Iface ipService = Clients.newClient(
                "ttext+http://" + endpointGroupMark + groupName + "/serverIp",
                HelloService.Iface.class);
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverOne.port()));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverTwo.port()));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverThree.port()));

        StaticEndpointGroup serverGroup2 = new StaticEndpointGroup(
                Endpoint.of("127.0.0.1", serverOne.port(), 2),
                Endpoint.of("127.0.0.1", serverTwo.port(), 4),
                Endpoint.of("127.0.0.1", serverThree.port(), 2));

        EndpointGroupRegistry.register(groupName, serverGroup2, WEIGHTED_ROUND_ROBIN);

        ipService = Clients.newClient("tbinary+http://" + endpointGroupMark + groupName + "/serverIp",
                                      HelloService.Iface.class);

        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverOne.port()));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverTwo.port()));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverThree.port()));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverOne.port()));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverTwo.port()));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverThree.port()));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverTwo.port()));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverTwo.port()));

        //new round
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverOne.port()));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverTwo.port()));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverThree.port()));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverOne.port()));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverTwo.port()));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverThree.port()));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverTwo.port()));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverTwo.port()));

        //direct connect to ip host
        ipService = Clients.newClient("tbinary+http://127.0.0.1:" + serverOne.port() + "/serverIp",
                                      HelloService.Iface.class);
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverOne.port()));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverOne.port()));

        serverOne.close();
        serverTwo.close();
        serverThree.close();
    }
}
