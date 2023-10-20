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

package com.linecorp.armeria.server.thrift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;

import org.apache.thrift.TException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.thrift.ThriftClients;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.logging.AccessLogWriter;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import testing.thrift.main.HelloService;

class ThriftServiceLogNameTest {

    private static final ch.qos.logback.classic.Logger rootLogger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

    private static final HelloService.AsyncIface HELLO_SERVICE_HANDLER =
            (name, resultHandler) -> {
                capturedCtx = ServiceRequestContext.current();
                resultHandler.onComplete("Hello " + name);
            };

    private static ServiceRequestContext capturedCtx;

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.accessLogWriter(AccessLogWriter.combined(), true);
            sb.service("/thrift", THttpService.builder()
                                              .addService(HELLO_SERVICE_HANDLER)
                                              .build());
            sb.route()
              .path("/default-names")
              .defaultServiceName("HelloService")
              .defaultLogName("defaultName")
              .build(THttpService.builder()
                                 .addService(HELLO_SERVICE_HANDLER)
                                 .build());
        }
    };

    @Mock
    private Appender<ILoggingEvent> appender;
    @Captor
    private ArgumentCaptor<ILoggingEvent> eventCaptor;

    @BeforeEach
    void setupLogger() {
        rootLogger.addAppender(appender);
    }

    @AfterEach
    void cleanupLogger() {
        rootLogger.detachAppender(appender);
    }

    @Test
    void logName() throws TException {
        final HelloService.Iface client =
                ThriftClients.builder(server.httpUri())
                             .path("/thrift")
                             .build(HelloService.Iface.class);
        client.hello("hello");

        final RequestLog log = capturedCtx.log().whenComplete().join();
        assertThat(log.name()).isEqualTo("hello");
        assertThat(log.serviceName()).isEqualTo(HelloService.AsyncIface.class.getName());
        assertThat(log.fullName()).isEqualTo(HelloService.AsyncIface.class.getName() + "/hello");
    }

    @Test
    void defaultNames() throws TException {
        final HelloService.Iface client =
                ThriftClients.builder(server.httpUri())
                             .path("/default-names")
                             .build(HelloService.Iface.class);
        client.hello("hello");

        final RequestLog log = capturedCtx.log().whenComplete().join();
        assertThat(log.serviceName()).isEqualTo("HelloService");
        assertThat(log.name()).isEqualTo("defaultName");
        assertThat(log.fullName()).isEqualTo("HelloService/defaultName");
    }

    @Test
    void logNameInAccessLog() throws TException {
        final HelloService.Iface client =
                ThriftClients.builder(server.httpUri())
                             .path("/thrift")
                             .build(HelloService.Iface.class);
        client.hello("hello");

        await().untilAsserted(() -> {
            verify(appender, atLeast(0)).doAppend(eventCaptor.capture());
            assertThat(eventCaptor.getAllValues()).anyMatch(evt -> {
                return evt.getMessage().contains("POST /thrift#HelloService$AsyncIface/hello h2c");
            });
        });
    }

    @Test
    void defaultNamesInAccessLog() throws TException {
        final HelloService.Iface client =
                ThriftClients.builder(server.httpUri())
                             .path("/default-names")
                             .build(HelloService.Iface.class);
        client.hello("hello");

        await().untilAsserted(() -> {
            verify(appender, atLeast(0)).doAppend(eventCaptor.capture());
            assertThat(eventCaptor.getAllValues()).anyMatch(evt -> {
                return evt.getMessage().contains("POST /default-names#HelloService/defaultName h2c");
            });
        });
    }

    @Test
    void logNameOfClientSide() throws TException {
        final HelloService.Iface client =
                ThriftClients.builder(server.httpUri())
                             .path("/thrift")
                             .build(HelloService.Iface.class);
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            client.hello("hello");
            final ClientRequestContext ctx = captor.get();
            final RequestLog requestLog = ctx.log().whenComplete().join();
            assertThat(requestLog.serviceName()).isEqualTo(HelloService.Iface.class.getName());
            assertThat(requestLog.name()).isEqualTo("hello");
        }
    }
}
