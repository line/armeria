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

import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.thrift.TApplicationException;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.service.test.thrift.main.HelloService.Iface;
import com.linecorp.armeria.testing.server.ServerRule;

/**
 * Ensures TMultiplexedProtocol works.
 */
public class TMultiplexedProtocolIntegrationTest {

    private static final Queue<String> methodNames = new LinkedBlockingQueue<>();

    @ClassRule
    public static final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(
                    "/",
                    THttpService.of(ImmutableMap.of("", (Iface) name -> "none:" + name,
                                                    "foo", name -> "foo:" + name,
                                                    "bar", name -> "bar:" + name))
                                .decorate((delegate, ctx, req) -> {
                                    ctx.log().addListener(log -> {
                                        final RpcRequest call = (RpcRequest) log.requestContent();
                                        if (call != null) {
                                            methodNames.add(call.method());
                                        }
                                    }, RequestLogAvailability.REQUEST_CONTENT);
                                    return delegate.serve(ctx, req);
                                }));
        }
    };

    @Before
    public void clearMethodNames() {
        methodNames.clear();
    }

    @Test
    public void test() throws Exception {
        assertThat(client("").hello("a")).isEqualTo("none:a");
        assertThat(client("foo").hello("b")).isEqualTo("foo:b");
        assertThat(client("bar").hello("c")).isEqualTo("bar:c");
        assertThatThrownBy(() -> client("baz").hello("d"))
                .isInstanceOf(TApplicationException.class)
                .hasFieldOrPropertyWithValue("type", TApplicationException.UNKNOWN_METHOD);

        assertThat(methodNames).containsExactly("hello", "foo:hello", "bar:hello");
    }

    private static Iface client(String serviceName) {
        String uri = server.uri(BINARY, "/");
        if (!serviceName.isEmpty()) {
            uri += '#' + serviceName;
        }
        return Clients.newClient(uri, Iface.class);
    }
}
