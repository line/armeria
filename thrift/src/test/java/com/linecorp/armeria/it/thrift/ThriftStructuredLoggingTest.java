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

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedTransferQueue;

import org.apache.thrift.protocol.TMessageType;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.thrift.ThriftCall;
import com.linecorp.armeria.common.thrift.ThriftReply;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.logging.structured.StructuredLoggingService;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.server.thrift.ThriftStructuredLog;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.service.test.thrift.main.HelloService.hello_args;
import com.linecorp.armeria.service.test.thrift.main.HelloService.hello_result;
import com.linecorp.armeria.testing.server.ServerRule;

public class ThriftStructuredLoggingTest {

    private static final BlockingQueue<ThriftStructuredLog> writtenLogs = new LinkedTransferQueue<>();

    private static class MockedStructuredLoggingService<I extends Request, O extends Response>
            extends StructuredLoggingService<I, O, ThriftStructuredLog> {

        int closed;

        MockedStructuredLoggingService(Service<I, O> delegate) {
            super(delegate, ThriftStructuredLog::new);
        }

        @Override
        protected void writeLog(RequestLog log, ThriftStructuredLog structuredLog) {
            writtenLogs.add(structuredLog);
        }

        @Override
        protected void close() {
            super.close();
            closed++;
        }
    }

    private MockedStructuredLoggingService<HttpRequest, HttpResponse> loggingService;

    @Rule
    public final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            loggingService = new MockedStructuredLoggingService<>(
                    THttpService.of((HelloService.Iface) name -> "Hello " + name));
            sb.service("/hello", loggingService);
        }
    };

    private HelloService.Iface newClient() throws Exception {
        String uri = server.uri(BINARY, "/hello");
        return Clients.newClient(uri, HelloService.Iface.class);
    }

    @Rule
    public final MockitoRule mockitoRule = MockitoJUnit.rule();

    @Test(timeout = 10000)
    public void testStructuredLogging() throws Exception {
        HelloService.Iface client = newClient();
        client.hello("kawamuray");

        ThriftStructuredLog log = writtenLogs.take();
        //assertThat(writtenLogs.size()).isEqualTo(1);

        assertThat(log.timestampMillis()).isGreaterThan(0);
        assertThat(log.responseTimeNanos()).isGreaterThanOrEqualTo(0);

        assertThat(log.thriftServiceName()).isEqualTo(HelloService.class.getCanonicalName());
        assertThat(log.thriftMethodName()).isEqualTo("hello");

        ThriftCall call = log.thriftCall();
        assertThat(call.header().name).isEqualTo("hello");
        assertThat(call.header().type).isEqualTo(TMessageType.CALL);
        assertThat(call.args()).isEqualTo(new hello_args().setName("kawamuray"));

        ThriftReply reply = log.thriftReply();
        assertThat(reply.header().name).isEqualTo("hello");
        assertThat(reply.header().type).isEqualTo(TMessageType.REPLY);
        assertThat(reply.header().seqid).isEqualTo(call.header().seqid);
        assertThat(reply.result()).isEqualTo(new hello_result().setSuccess("Hello kawamuray"));
    }

    @Test(timeout = 10000)
    public void testWriterClosed() throws Exception {
        server.stop().join();
        assertThat(loggingService.closed).isOne();
    }
}
