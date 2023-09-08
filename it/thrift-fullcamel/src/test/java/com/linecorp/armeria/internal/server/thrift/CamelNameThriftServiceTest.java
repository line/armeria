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

package com.linecorp.armeria.internal.server.thrift;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.thrift.TException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.thrift.ThriftClients;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import testing.thrift.full.camel.TestService.Iface;

/**
 * Special test for `fullcamel` option in thrift compiler.
 */
class CamelNameThriftServiceTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/hello", THttpService.of(new Iface() {
                @Override
                public String sayHello(String name) throws TException {
                    return "Hello, " + name + '!';
                }

                @Override
                public String sayHelloNow(String name) throws TException {
                    return "Hello, " + name + '!';
                }

                @Override
                public String sayHelloWorld(String name) throws TException {
                    return "Hello, " + name + '!';
                }
            }));
        }
    };

    @Test
    void testSync_SayHelloService_sayHello()
            throws Exception {
        final Iface client = ThriftClients.newClient(server.httpUri() + "/hello", Iface.class);
        assertThat(client.sayHello("Armeria")).isEqualTo("Hello, Armeria!");
        assertThat(client.sayHello(null)).isEqualTo("Hello, null!");

        assertThat(client.sayHelloNow("Armeria")).isEqualTo("Hello, Armeria!");
        assertThat(client.sayHelloNow(null)).isEqualTo("Hello, null!");

        assertThat(client.sayHelloWorld("Armeria")).isEqualTo("Hello, Armeria!");
        assertThat(client.sayHelloWorld(null)).isEqualTo("Hello, null!");
    }
}
