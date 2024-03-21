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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.thrift.ThriftClients;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.testing.junit4.server.ServerRule;

import testing.thrift.main.HelloService;

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

        final EndpointGroup endpointGroup = EndpointGroup.of(
                Endpoint.of("127.0.0.1", serverOne.httpPort()).withWeight(1),
                Endpoint.of("127.0.0.1", serverTwo.httpPort()).withWeight(2),
                Endpoint.of("127.0.0.1", serverThree.httpPort()).withWeight(3));

        HelloService.Iface ipService = ThriftClients.newClient("ttext+http", endpointGroup, "/serverIp",
                                                               HelloService.Iface.class);
        assertThat(ipService.hello("ip")).isEqualTo(
                "host:127.0.0.1:" + serverOne.httpPort());
        assertThat(ipService.hello("ip")).isEqualTo(
                "host:127.0.0.1:" + serverTwo.httpPort());
        assertThat(ipService.hello("ip")).isEqualTo("host:127.0.0.1:" + serverThree.httpPort());

        final EndpointGroup serverGroup2 = EndpointGroup.of(
                Endpoint.of("127.0.0.1", serverOne.httpPort()).withWeight(2),
                Endpoint.of("127.0.0.1", serverTwo.httpPort()).withWeight(4),
                Endpoint.of("127.0.0.1", serverThree.httpPort()).withWeight(3));

        ipService = ThriftClients.newClient("http", serverGroup2, "/serverIp",
                                            HelloService.Iface.class);

        assertThat(ipService.hello("ip")).isEqualTo("host:127.0.0.1:" + serverOne.httpPort());
        assertThat(ipService.hello("ip")).isEqualTo("host:127.0.0.1:" + serverThree.httpPort());
        assertThat(ipService.hello("ip")).isEqualTo("host:127.0.0.1:" + serverTwo.httpPort());
        assertThat(ipService.hello("ip")).isEqualTo("host:127.0.0.1:" + serverOne.httpPort());
        assertThat(ipService.hello("ip")).isEqualTo("host:127.0.0.1:" + serverThree.httpPort());
        assertThat(ipService.hello("ip")).isEqualTo("host:127.0.0.1:" + serverTwo.httpPort());
        assertThat(ipService.hello("ip")).isEqualTo("host:127.0.0.1:" + serverThree.httpPort());
        assertThat(ipService.hello("ip")).isEqualTo("host:127.0.0.1:" + serverTwo.httpPort());
        assertThat(ipService.hello("ip")).isEqualTo("host:127.0.0.1:" + serverTwo.httpPort());

        //new round
        assertThat(ipService.hello("ip")).isEqualTo("host:127.0.0.1:" + serverOne.httpPort());
        assertThat(ipService.hello("ip")).isEqualTo("host:127.0.0.1:" + serverThree.httpPort());
        assertThat(ipService.hello("ip")).isEqualTo("host:127.0.0.1:" + serverTwo.httpPort());
        assertThat(ipService.hello("ip")).isEqualTo("host:127.0.0.1:" + serverOne.httpPort());
        assertThat(ipService.hello("ip")).isEqualTo("host:127.0.0.1:" + serverThree.httpPort());
        assertThat(ipService.hello("ip")).isEqualTo("host:127.0.0.1:" + serverTwo.httpPort());
        assertThat(ipService.hello("ip")).isEqualTo("host:127.0.0.1:" + serverThree.httpPort());
        assertThat(ipService.hello("ip")).isEqualTo("host:127.0.0.1:" + serverTwo.httpPort());
        assertThat(ipService.hello("ip")).isEqualTo("host:127.0.0.1:" + serverTwo.httpPort());

        //direct connect to ip host
        ipService = ThriftClients.newClient("tbinary+http://127.0.0.1:" + serverOne.httpPort() + "/serverIp",
                                            HelloService.Iface.class);
        assertThat(ipService.hello("ip")).isEqualTo("host:127.0.0.1:" + serverOne.httpPort());
        assertThat(ipService.hello("ip")).isEqualTo("host:127.0.0.1:" + serverOne.httpPort());
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
