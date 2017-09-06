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
import com.linecorp.armeria.testing.server.ServerRule;

public class StaticEndpointGroupIntegrationTest {
    @Rule
    public final TestName name = new TestName();
    @Rule
    public final ServerRule serverOne = new IpServerRule();
    @Rule
    public final ServerRule serverTwo = new IpServerRule();
    @Rule
    public final ServerRule serverThree = new IpServerRule();

    @Test
    public void testRoundRobinServerGroup() throws Exception {
        serverOne.start();
        serverTwo.start();
        serverThree.start();

        EndpointGroup endpointGroup = new StaticEndpointGroup(
                Endpoint.of("127.0.0.1", serverOne.httpPort()),
                Endpoint.of("127.0.0.1", serverTwo.httpPort()),
                Endpoint.of("127.0.0.1", serverThree.httpPort()));
        String groupName = name.getMethodName();
        String endpointGroupMark = "group:";

        EndpointGroupRegistry.register(groupName, endpointGroup, WEIGHTED_ROUND_ROBIN);

        HelloService.Iface ipService = Clients.newClient(
                "ttext+http://" + endpointGroupMark + groupName + "/serverIp",
                HelloService.Iface.class);
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverOne.httpPort()));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverTwo.httpPort()));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverThree.httpPort()));

        StaticEndpointGroup serverGroup2 = new StaticEndpointGroup(
                Endpoint.of("127.0.0.1", serverOne.httpPort(), 2),
                Endpoint.of("127.0.0.1", serverTwo.httpPort(), 4),
                Endpoint.of("127.0.0.1", serverThree.httpPort(), 2));

        EndpointGroupRegistry.register(groupName, serverGroup2, WEIGHTED_ROUND_ROBIN);

        ipService = Clients.newClient("tbinary+http://" + endpointGroupMark + groupName + "/serverIp",
                                      HelloService.Iface.class);

        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverOne.httpPort()));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverTwo.httpPort()));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverThree.httpPort()));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverOne.httpPort()));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverTwo.httpPort()));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverThree.httpPort()));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverTwo.httpPort()));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverTwo.httpPort()));

        //new round
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverOne.httpPort()));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverTwo.httpPort()));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverThree.httpPort()));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverOne.httpPort()));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverTwo.httpPort()));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverThree.httpPort()));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverTwo.httpPort()));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverTwo.httpPort()));

        //direct connect to ip host
        ipService = Clients.newClient("tbinary+http://127.0.0.1:" + serverOne.httpPort() + "/serverIp",
                                      HelloService.Iface.class);
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverOne.httpPort()));
        assertThat(ipService.hello("ip"), is("host:127.0.0.1:" + serverOne.httpPort()));
    }

    private static class IpServerRule extends ServerRule {
        private final HelloService.Iface handler = dump -> "host:127.0.0.1:" + httpPort();

        protected IpServerRule() {
            super(false); // Disable auto-start.
        }

        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/serverIp", THttpService.of(handler));
        }
    }
}
