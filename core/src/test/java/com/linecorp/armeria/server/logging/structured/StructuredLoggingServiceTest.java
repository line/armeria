/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.logging.structured;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;

import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.logging.ResponseLog;
import com.linecorp.armeria.common.thrift.ApacheThriftCall;
import com.linecorp.armeria.common.thrift.ApacheThriftReply;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.service.test.thrift.main.HelloService.hello_args;
import com.linecorp.armeria.service.test.thrift.main.HelloService.hello_result;
import com.linecorp.armeria.test.AbstractServerTest;

public class StructuredLoggingServiceTest extends AbstractServerTest {
    @Rule
    public final MockitoRule mockitoRule = MockitoJUnit.rule();

    private static final List<ApacheThriftStructuredLog> writtenLogs = new ArrayList<>();

    private static class MockedStructuredLoggingService<I extends Request, O extends Response>
            extends StructuredLoggingService<I, O, ApacheThriftStructuredLog> {
        MockedStructuredLoggingService(Service<I, O> delegate) {
            super(delegate, ApacheThriftStructuredLog::new);
        }

        @Override
        protected void writeLog(RequestContext reqCtx, ResponseLog resLog,
                                ApacheThriftStructuredLog structuredLog) {
            writtenLogs.add(structuredLog);
        }
    }

    private StructuredLoggingService<?, ?, ApacheThriftStructuredLog> loggingService;

    private static HelloService.Iface newClient() throws Exception {
        String uri = "tbinary+" + uri("/hello");
        return Clients.newClient(uri, HelloService.Iface.class);
    }

    @SuppressWarnings("unchecked")
    @Override
    protected void configureServer(ServerBuilder sb) {
        MockedStructuredLoggingService<?, ?> service =
                THttpService.of((HelloService.Iface) name -> "Hello " + name)
                            .decorate(s -> new MockedStructuredLoggingService<>(s));
        loggingService = spy(service);
        sb.serviceAt("/hello", loggingService);
    }

    @Test
    public void testStructuredLogging() throws Exception {
        HelloService.Iface client = newClient();
        client.hello("kawamuray");

        assertThat(writtenLogs.size()).isEqualTo(1);

        ApacheThriftStructuredLog log = writtenLogs.remove(0);

        assertThat(log.timestampMillis()).isGreaterThan(0);
        assertThat(log.responseTimeNanos()).isGreaterThanOrEqualTo(0);

        assertThat(log.thriftServiceName()).isEqualTo(HelloService.class.getCanonicalName());
        assertThat(log.thriftMethodName()).isEqualTo("hello");

        ApacheThriftCall call = log.thriftCall();
        assertThat(call.header()).isEqualTo(new TMessage("hello", TMessageType.CALL, 0));
        assertThat(call.args()).isEqualTo(new hello_args().setName("kawamuray"));

        ApacheThriftReply reply = log.thriftReply();
        assertThat(reply.header()).isEqualTo(new TMessage("hello", TMessageType.REPLY, 0));
        assertThat(reply.result()).isEqualTo(new hello_result().setSuccess("Hello kawamuray"));
    }

    @Test(timeout = 10000)
    public void testWriterClosed() throws Exception {
        stopServer();
        verify(loggingService, times(1)).close();
    }
}
