/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Spy;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class ExceptionReportingServiceErrorHandlerTest {

    @Spy
    final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
    final Logger errorHandlerLogger =
            (Logger) LoggerFactory.getLogger(DefaultUnhandledExceptionsReporter.class);
    private static final long reportIntervalMillis = 1000;
    private static final long awaitIntervalMillis = 2000;

    @BeforeEach
    public void attachAppender() {
        logAppender.start();
        errorHandlerLogger.addAppender(logAppender);
    }

    @AfterEach
    public void detachAppender() {
        errorHandlerLogger.detachAppender(logAppender);
        logAppender.list.clear();
    }

    @RegisterExtension
    static ServerExtension serverWithIgnorableException = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/hello", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
            sb.decorator(service -> (ctx, req) -> {
                throw new ClosedSessionException("Ignorable exception");
            });
            sb.unhandledExceptionsReportInterval(Duration.ofMillis(reportIntervalMillis));
        }
    };

    @RegisterExtension
    static ServerExtension serverWithNonIgnorableException = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/hello", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
            sb.decorator(service -> (ctx, req) -> {
                throw new IllegalArgumentException("Non-ignorable exception");
            });
            sb.unhandledExceptionsReportInterval(Duration.ofMillis(reportIntervalMillis));
        }
    };

    @Test
    void ignorableExceptionShouldNotBeLogged() throws Exception {
        try (ClientFactory cf = ClientFactory.builder().build()) {
            serverWithIgnorableException.blockingWebClient(cb -> cb.factory(cf)).get("/hello");
            Thread.sleep(reportIntervalMillis + awaitIntervalMillis);
            assertThat(logAppender.list).isEmpty();
        }
    }

    @Test
    void nonIgnorableExceptionShouldBeLogged() {
        try (ClientFactory cf = ClientFactory.builder().build()) {
            serverWithNonIgnorableException.blockingWebClient(cb -> cb.factory(cf)).get("/hello");
            await().atMost(Duration.ofMillis(reportIntervalMillis + awaitIntervalMillis))
                   .untilAsserted(() -> assertThat(logAppender.list).isNotEmpty());

            assertThat(logAppender.list
                               .stream()
                               .filter(event -> event.getFormattedMessage().contains(
                                       "Observed 1 exceptions that didn't reach a LoggingService"))
                               .findAny()
            ).isNotEmpty();
        }
    }
}
