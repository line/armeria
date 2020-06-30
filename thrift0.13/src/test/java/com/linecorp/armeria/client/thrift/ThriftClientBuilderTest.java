/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.client.thrift;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;
import com.linecorp.armeria.service.test.thrift.main.HelloService;

class ThriftClientBuilderTest {

    @Test
    void uri() throws Exception {
        final HelloService.Iface client = Clients.builder("tbinary+https://google.com/")
                                                 .build(HelloService.Iface.class);
        final ClientBuilderParams params = Clients.unwrap(client, ClientBuilderParams.class);
        assertThat(params).isNotNull();
        assertThat(params.uri().toString()).isEqualTo("tbinary+https://google.com/");
    }

    @Test
    void endpointWithoutPath() {
        final HelloService.Iface client = Clients.builder("tbinary+http", Endpoint.of("127.0.0.1"))
                                                 .build(HelloService.Iface.class);
        final ClientBuilderParams params = Clients.unwrap(client, ClientBuilderParams.class);
        assertThat(params).isNotNull();
        assertThat(params.uri().toString()).isEqualTo("tbinary+http://127.0.0.1/");
    }

    @Test
    void endpointWithPath() {
        final HelloService.Iface client = Clients.builder("tbinary+http", Endpoint.of("127.0.0.1"), "/foo")
                                                 .build(HelloService.Iface.class);
        final ClientBuilderParams params = Clients.unwrap(client, ClientBuilderParams.class);
        assertThat(params).isNotNull();
        assertThat(params.uri().toString()).isEqualTo("tbinary+http://127.0.0.1/foo");
        assertThat(params.scheme().serializationFormat()).isSameAs(ThriftSerializationFormats.BINARY);
    }
}
