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
import static org.awaitility.Awaitility.await;

import java.util.concurrent.atomic.AtomicReference;

import org.apache.thrift.protocol.TMessageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.thrift.ThriftCall;
import com.linecorp.armeria.common.thrift.ThriftReply;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.server.thrift.ThriftStructuredLog;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.service.test.thrift.main.HelloService.hello_args;
import com.linecorp.armeria.service.test.thrift.main.HelloService.hello_result;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

class ThriftStructuredLoggingTest {

    private static final AtomicReference<ThriftStructuredLog> logHolder = new AtomicReference<>();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {

        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final THttpService tHttpService = THttpService.of((HelloService.Iface) name -> "Hello " + name);
            sb.service("/hello", tHttpService).accessLogWriter(log -> {
                logHolder.set(new ThriftStructuredLog(log));
            }, true);
        }
    };

    private static HelloService.Iface newClient() throws Exception {
        final String uri = server.uri(BINARY, "/hello");
        return Clients.newClient(uri, HelloService.Iface.class);
    }

    @Test
    public void testStructuredLogging() throws Exception {
        final HelloService.Iface client = newClient();
        client.hello("kawamuray");

        await().until(() -> logHolder.get() != null);
        final ThriftStructuredLog log = logHolder.get();

        assertThat(log.timestampMillis()).isGreaterThan(0);
        assertThat(log.responseTimeNanos()).isGreaterThanOrEqualTo(0);

        assertThat(log.thriftServiceName()).isEqualTo(HelloService.class.getCanonicalName());
        assertThat(log.thriftMethodName()).isEqualTo("hello");

        final ThriftCall call = log.thriftCall();
        assertThat(call).isNotNull();
        assertThat(call.header().name).isEqualTo("hello");
        assertThat(call.header().type).isEqualTo(TMessageType.CALL);
        assertThat(call.args()).isEqualTo(new hello_args().setName("kawamuray"));

        final ThriftReply reply = log.thriftReply();
        assertThat(reply).isNotNull();
        assertThat(reply.header().name).isEqualTo("hello");
        assertThat(reply.header().type).isEqualTo(TMessageType.REPLY);
        assertThat(reply.header().seqid).isEqualTo(call.header().seqid);
        assertThat(reply.result()).isEqualTo(new hello_result().setSuccess("Hello kawamuray"));
    }
}
