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

package com.linecorp.armeria.it.thrift;

import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.BINARY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.thrift.TApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.thrift.ThriftClients;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.internal.testing.GenerateNativeImageTrace;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import testing.thrift.main.HelloService.Iface;

/**
 * Ensures TMultiplexedProtocol works.
 */
@GenerateNativeImageTrace
class TMultiplexedProtocolIntegrationTest {

    private static final Queue<String> methodNames = new LinkedBlockingQueue<>();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(
                    "/",
                    THttpService.builder()
                                .addService((Iface) name -> "none:" + name)
                                .addService("foo", (Iface) name -> "foo:" + name)
                                .addService("bar", (Iface) name -> "bar:" + name)
                                .build()
                                .decorate((delegate, ctx, req) -> {
                                    ctx.log().whenAvailable(RequestLogProperty.REQUEST_CONTENT)
                                       .thenAccept(log -> {
                                           final RpcRequest call = (RpcRequest) log.requestContent();
                                           if (call != null) {
                                               methodNames.add(call.method());
                                           }
                                       });
                                    return delegate.serve(ctx, req);
                                }));
        }
    };

    @BeforeEach
    void clearMethodNames() {
        methodNames.clear();
    }

    @Test
    void test() throws Exception {
        assertThat(client("").hello("a")).isEqualTo("none:a");
        assertThat(client("foo").hello("b")).isEqualTo("foo:b");
        assertThat(client("bar").hello("c")).isEqualTo("bar:c");
        assertThatThrownBy(() -> client("baz").hello("d"))
                .isInstanceOf(TApplicationException.class)
                .hasFieldOrPropertyWithValue("type", TApplicationException.UNKNOWN_METHOD);

        assertThat(methodNames).containsExactly("hello", "foo:hello", "bar:hello");
    }

    private static Iface client(String serviceName) {
        final URI uri;
        if (serviceName.isEmpty()) {
            uri = server.httpUri(BINARY);
        } else {
            uri = server.httpUri(BINARY).resolve('#' + serviceName);
        }
        return ThriftClients.newClient(uri, Iface.class);
    }
}
