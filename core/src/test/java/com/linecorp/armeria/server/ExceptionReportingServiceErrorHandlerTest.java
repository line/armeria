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

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.logging.LoggingService;
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

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.unhandledExceptionsReportIntervalMillis(reportIntervalMillis);
            sb.service("/hello", (ctx, req) -> {
                throw HttpStatusException.of(HttpStatus.BAD_REQUEST,
                                             new IllegalArgumentException("test"));
            });
            sb.service("/httpexception", (ctx, req) -> {
                throw HttpStatusException.of(HttpStatus.BAD_REQUEST);
            });
            sb.route()
              .path("/iae")
              .decorator(LoggingService.newDecorator())
              .build((ctx, req) -> {
                  throw new IllegalArgumentException("test");
              });
            sb.route()
              .path("/ok")
              .decorator(LoggingService.newDecorator())
              .build((ctx, req) -> HttpResponse.of(HttpStatus.OK));
        }
    };

    @BeforeEach
    public void beforeEach() {
        logAppender.start();
        errorHandlerLogger.addAppender(logAppender);

        final DefaultUnhandledExceptionsReporter reporter =
                (DefaultUnhandledExceptionsReporter) server.server().config().unhandledExceptionsReporter();
        if (reporter != null) {
            reporter.reset();
        }
    }

    @AfterEach
    public void afterEach() {
        errorHandlerLogger.detachAppender(logAppender);
        logAppender.list.clear();
    }

    @Test
    void httpStatusExceptionWithCauseLogged() throws Exception {
        final AggregatedHttpResponse res = server.blockingWebClient().get("/hello");
        assertThat(res.status().code()).isEqualTo(400);

        await().atMost(Duration.ofMillis(reportIntervalMillis + awaitIntervalMillis))
               .untilAsserted(() -> assertThat(logAppender.list).isNotEmpty());

        assertThat(logAppender.list
                           .stream()
                           .filter(event -> event.getFormattedMessage().contains(
                                   "Observed 1 unhandled exceptions"))
                           .findAny()).isNotEmpty();
    }

    @Test
    void httpStatusExceptionWithoutCauseIsIgnored() throws Exception {
        final AggregatedHttpResponse res = server.blockingWebClient().get("/httpexception");
        assertThat(res.status().code()).isEqualTo(400);

        Thread.sleep(reportIntervalMillis + awaitIntervalMillis);
        await().until(() -> logAppender.list.isEmpty());
    }

    @Test
    void exceptionShouldNotBeLoggedWhenDecoratedWithLoggingService() throws Exception {
        final AggregatedHttpResponse res = server.blockingWebClient().get("/iae");
        assertThat(res.status().code()).isEqualTo(500);

        Thread.sleep(reportIntervalMillis + awaitIntervalMillis);
        await().until(() -> logAppender.list.isEmpty());
    }

    @Test
    void exceptionShouldNotBeLoggedWhenNoExceptionIsThrown() throws Exception {
        final AggregatedHttpResponse res = server.blockingWebClient().get("/ok");
        assertThat(res.status().code()).isEqualTo(200);

        Thread.sleep(reportIntervalMillis + awaitIntervalMillis);
        await().until(() -> logAppender.list.isEmpty());
    }
}
