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

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import java.net.ConnectException;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

public class FailOverTest extends AbstractEndpointGroupTest {
    private static final HelloService.Iface HELLO_SERVICE_HANDLER_ONE = (dump) -> "host:127.0.0.1:1234";
    private static final HelloService.Iface HELLO_SERVICE_HANDLER_TWO = (dump) -> "host:127.0.0.1:1234";
    private static final HelloService.Iface HELLO_SERVICE_HANDLER_THREE = (dump) -> "host:127.0.0.1:1234";

    @Rule
    public TestName name = new TestName();

    @Test
    public void requestSuccess() throws Exception {
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

        HelloService.Iface ipService = new ClientBuilder("ttext+http://" + endpointGroupMark + groupName + "/serverIp")
                .decorator(FailOverClient.newDecorator(3)).build(HelloService.Iface.class);
        serverOne.stop();
        serverTwo.stop();
        assertThat(ipService.hello("ip").equals("host:127.0.0.1:1234"), is(true));
        assertThat(ipService.hello("ip").equals("host:127.0.0.1:1234"), is(true));
        serverThree.stop();
    }

    @Test(expected = ConnectException.class)
    public void requestFailedByNoAliveEndpoints() throws Exception {
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

        HelloService.Iface ipService = new ClientBuilder("ttext+http://" + endpointGroupMark + groupName + "/serverIp")
                .decorator(FailOverClient.newDecorator(3)).build(HelloService.Iface.class);
        serverOne.stop();
        serverTwo.stop();
        serverThree.stop();
        try {
            assertThat(ipService.hello("ip").equals("host:127.0.0.1:1234"), is(true));
        } catch (Exception ex) {
            assertEquals(ConnectException.class, ex.getCause().getClass());
            throw new ConnectException(ex.getMessage());
        }
    }

    @Test(expected = ConnectException.class)
    public void requestFailedByTryCount() throws Exception {
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

        HelloService.Iface ipService = new ClientBuilder("ttext+http://" + endpointGroupMark + groupName + "/serverIp")
                .decorator(FailOverClient.newDecorator(1)).build(HelloService.Iface.class);
        serverOne.stop();
        serverTwo.stop();
        try {
            assertThat(ipService.hello("ip").equals("host:127.0.0.1:1234"), is(true));
        } catch (Exception ex) {
            assertEquals(ConnectException.class, ex.getCause().getClass());
            throw new ConnectException(ex.getMessage());
        } finally {
            serverThree.stop();
        }
    }

    @Test(expected = DumpBizException.class)
    public void requestFailedByBizException() throws Exception {
        HelloService.Iface HELLO_SERVICE_HANDLER = (dump) -> {
            throw new DumpBizException("biz exception");
        };

        ServiceServer serverOne = new ServiceServer(HELLO_SERVICE_HANDLER, 1234);
        serverOne.start();

        ServiceServer serverTwo = new ServiceServer(HELLO_SERVICE_HANDLER, 2345);
        serverTwo.start();

        ServiceServer serverThree = new ServiceServer(HELLO_SERVICE_HANDLER, 3456);
        serverThree.start();

        EndpointGroup<WeightedEndpoint> endpointGroup = new StaticEndpointGroup<>(
                new DefaultWeightedEndpoint("127.0.0.1", 1234),
                new DefaultWeightedEndpoint("127.0.0.1", 2345),
                new DefaultWeightedEndpoint("127.0.0.1", 3456));
        String groupName = name.getMethodName();
        String endpointGroupMark = "group:";

        EndpointGroupRegistry.register(groupName, endpointGroup, EndpointSelectionStrategy.WEIGHTED_ROUND_ROBIN);

        HelloService.Iface ipService = new ClientBuilder("ttext+http://" + endpointGroupMark + groupName + "/serverIp")
                .decorator(FailOverClient.newDecorator(3)).build(HelloService.Iface.class);

        try {
            assertThat(ipService.hello("ip").equals("host:127.0.0.1:1234"), is(true));
        } catch (Exception ex) {
            assertEquals("com.linecorp.armeria.client.routing.FailOverTest$DumpBizException: biz exception", ex.getMessage());
            throw new DumpBizException(ex.getMessage());
        } finally {
            serverOne.stop();
            serverTwo.stop();
            serverThree.stop();
        }
    }

    static class DumpBizException extends RuntimeException {
        public DumpBizException(String s) {
            super(s);
        }
    }
}
