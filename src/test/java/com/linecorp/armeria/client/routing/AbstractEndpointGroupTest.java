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

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.thrift.ThriftService;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import io.netty.util.internal.PlatformDependent;

abstract public class AbstractEndpointGroupTest {
    public static class ServiceServer {
        private Server server;
        private HelloService.Iface handler;
        private int port;

        public ServiceServer(HelloService.Iface handler, int port) {
            this.handler = handler;
            this.port = port;
        }

        private void configureServer() throws InterruptedException {
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

        public void start() throws InterruptedException {
            configureServer();
        }

        public void stop() {
            server.stop();
        }
    }
}
