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
    private static final long unLoggedExceptionReportInterval = 1;
    private static final long awaitIntervalForTest = 2;

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
    void testServerBuilderConfiguration() {
        final Server server1 = Server.builder()
                                     .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                                     .build();
        assertThat(server1.config().unLoggedExceptionReportInterval().isZero()).isFalse();
        assertThat(server1.config().unLoggedExceptionReportInterval().getSeconds()).isEqualTo(60);

        final Server server2 = Server.builder()
                                     .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                                     .unLoggedExceptionReportInterval(Duration.ofSeconds(120))
                                     .build();
        assertThat(server2.config().unLoggedExceptionReportInterval().isZero()).isFalse();
        assertThat(server2.config().unLoggedExceptionReportInterval().getSeconds()).isEqualTo(120);

        final Server server3 = Server.builder()
                                     .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                                     .unLoggedExceptionReportInterval(Duration.ZERO)
                                     .build();
        assertThat(server3.config().unLoggedExceptionReportInterval().isZero()).isTrue();
    }

    @Test
    void testExceptionIsLogged() throws Exception {
        final Server server = Server.builder()
                                    .service("/hello", (ctx, req) -> {
                                        throw new IllegalArgumentException("test");
                                    })
                                    .unLoggedExceptionReportInterval(
                                            Duration.ofSeconds(unLoggedExceptionReportInterval))
                                    .build();
        server.start().join();

        WebClient.of("http://127.0.0.1:" + server.activePort().localAddress().getPort()).get("/hello")
                 .aggregate().get();
        await().atMost(Duration.ofSeconds(unLoggedExceptionReportInterval + awaitIntervalForTest))
               .untilAsserted(() -> assertThat(logAppender.list).isNotEmpty());

        assertThat(logAppender.list
                           .stream()
                           .filter(event -> event.getFormattedMessage().contains(
                                   "Observed 1 uncaught exceptions in last"))
                           .findAny()
        ).isNotEmpty();
    }

    @Test
    void testExceptionIsNotLoggedWhenDecoratedWithLoggingService() throws Exception {
        final Server server = Server.builder()
                                    .service("/hello", (ctx, req) -> {
                                        throw new IllegalArgumentException("test");
                                    })
                                    .unLoggedExceptionReportInterval(
                                            Duration.ofSeconds(unLoggedExceptionReportInterval))
                                    .decorator(LoggingService.newDecorator())
                                    .build();
        server.start().join();

        WebClient.of("http://127.0.0.1:" + server.activePort().localAddress().getPort()).get("/hello")
                 .aggregate().get();
        Thread.sleep(1000L * (unLoggedExceptionReportInterval + awaitIntervalForTest));

        assertThat(logAppender.list).isEmpty();
    }

    @Test
    void testExceptionIsNotLoggedWhenNoExceptionsIsThrown() throws Exception {
        final Server server = Server.builder()
                                    .service("/hello", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                                    .decorator(LoggingService.newDecorator())
                                    .build();
        server.start().join();

        WebClient.of("http://127.0.0.1:" + server.activePort().localAddress().getPort()).get("/hello")
                 .aggregate().get();
        Thread.sleep(1000L * (unLoggedExceptionReportInterval + awaitIntervalForTest));

        assertThat(logAppender.list).isEmpty();
    }
}
