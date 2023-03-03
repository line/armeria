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
import org.mockito.Spy;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.logging.LoggingService;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class ExceptionReportingServerErrorHandlerTest {

    @Spy
    final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
    final Logger errorHandlerLogger =
            (Logger) LoggerFactory.getLogger(ExceptionReportingServerErrorHandler.class);
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

    @Test
    void exceptionShouldBeLoggedWhenNoLoggingServiceHandlesException() throws Exception {
        final Server server = Server.builder()
                                    .service("/hello", (ctx, req) -> {
                                        throw new IllegalArgumentException("test");
                                    })
                                    .unhandledExceptionsReportInterval(Duration.ofMillis(reportIntervalMillis))
                                    .build();
        try {
            server.start().join();

            WebClient.of("http://127.0.0.1:" + server.activePort().localAddress().getPort()).get("/hello")
                     .aggregate().get();
            await().atMost(Duration.ofMillis(reportIntervalMillis + awaitIntervalMillis))
                   .untilAsserted(() -> assertThat(logAppender.list).isNotEmpty());

            assertThat(logAppender.list
                               .stream()
                               .filter(event -> event.getFormattedMessage().contains(
                                       "Observed 1 unhandled exceptions"))
                               .findAny()
            ).isNotEmpty();
        } finally {
            server.stop();
        }
    }

    @Test
    void exceptionShouldNotBeLoggedWhenExceptionIsHandled() throws Exception {
        final Server server = Server.builder()
                                    .service("/hello", (ctx, req) -> {
                                        throw HttpStatusException.of(HttpStatus.BAD_REQUEST,
                                                                     new IllegalArgumentException("test"));
                                    })
                                    .unhandledExceptionsReportInterval(Duration.ofMillis(reportIntervalMillis))
                                    .build();
        try {
            server.start().join();

            WebClient.of("http://127.0.0.1:" + server.activePort().localAddress().getPort()).get("/hello")
                     .aggregate().get();
            Thread.sleep(reportIntervalMillis + awaitIntervalMillis);
            assertThat(logAppender.list).isEmpty();
        } finally {
            server.stop();
        }
    }

    @Test
    void exceptionShouldNotBeLoggedWhenDecoratedWithLoggingService() throws Exception {
        final Server server = Server.builder()
                                    .service("/hello", (ctx, req) -> {
                                        throw new IllegalArgumentException("test");
                                    })
                                    .unhandledExceptionsReportInterval(Duration.ofMillis(reportIntervalMillis))
                                    .decorator(LoggingService.newDecorator())
                                    .build();

        try {
            server.start().join();

            WebClient.of("http://127.0.0.1:" + server.activePort().localAddress().getPort()).get("/hello")
                     .aggregate().get();
            Thread.sleep(reportIntervalMillis + awaitIntervalMillis);
            assertThat(logAppender.list).isEmpty();
        } finally {
            server.stop();
        }
    }

    @Test
    void exceptionShouldNotBeLoggedWhenNoExceptionIsThrown() throws Exception {
        final Server server = Server.builder()
                                    .service("/hello", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                                    .decorator(LoggingService.newDecorator())
                                    .build();
        try {
            server.start().join();

            WebClient.of("http://127.0.0.1:" + server.activePort().localAddress().getPort()).get("/hello")
                     .aggregate().get();
            Thread.sleep(reportIntervalMillis + awaitIntervalMillis);

            assertThat(logAppender.list).isEmpty();
        } finally {
            server.stop();
        }
    }
}
