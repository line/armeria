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

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.logging.AccessLogWriter;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;

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
                Clients.builder(server.httpUri(ThriftSerializationFormats.BINARY).resolve("/thrift"))
                       .build(HelloService.Iface.class);
        client.hello("hello");

        final RequestLog log = capturedCtx.log().partial();
        assertThat(log.name()).isEqualTo("hello");
        assertThat(log.serviceName()).isEqualTo(HelloService.AsyncIface.class.getName());
        assertThat(log.fullName()).isEqualTo(HelloService.AsyncIface.class.getName() + "/hello");
    }

    @Test
    void logNameInAccessLog() throws TException {
        final HelloService.Iface client =
                Clients.builder(server.httpUri(ThriftSerializationFormats.BINARY).resolve("/thrift"))
                       .build(HelloService.Iface.class);
        client.hello("hello");

        verify(appender, atLeast(1)).doAppend(eventCaptor.capture());
        await().untilAsserted(() -> {
            assertThat(eventCaptor.getAllValues()).anyMatch(evt -> {
                return evt.getMessage().contains("POST /thrift#HelloService$AsyncIface/hello h2c");
            });
        });
    }
}
