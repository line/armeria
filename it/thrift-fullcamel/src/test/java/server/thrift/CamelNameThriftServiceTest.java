/*
 * Copyright 2021 LINE Corporation
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

package server.thrift;

import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.BINARY;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.service.test.thrift.main.SayHelloService;
import com.linecorp.armeria.service.test.thrift.main.SayHelloService.Iface;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

/**
 * Special test for `fullcamel` option in thrift compiler.
 */
class CamelNameThriftServiceTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/hello", THttpService.of((SayHelloService.Iface) name
                    -> "Hello, " + name + '!'));
        }
    };

    @Test
    void testSync_SayHelloService_sayHello()
            throws Exception {
        final Iface client = Clients.newClient(server.httpUri(BINARY) + "/hello", Iface.class);
        assertThat(client.sayHello("Armeria")).isEqualTo("Hello, Armeria!");
        assertThat(client.sayHello(null)).isEqualTo("Hello, null!");
    }
}
