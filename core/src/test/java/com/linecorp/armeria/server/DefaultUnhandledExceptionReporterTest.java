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

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class DefaultUnhandledExceptionReporterTest {

    @Spy
    final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
    final Logger errorHandlerLogger =
            (Logger) LoggerFactory.getLogger(DefaultUnloggedExceptionsReporter.class);
    private static final long reportIntervalMillis = 1000;
    private static final long awaitIntervalMillis = 2000;
    private static volatile boolean throwNonIgnorableException;

    @BeforeEach
    public void attachAppender() {
        logAppender.start();
        errorHandlerLogger.addAppender(logAppender);
        throwNonIgnorableException = false;
    }

    @AfterEach
    public void detachAppender() {
        errorHandlerLogger.detachAppender(logAppender);
        logAppender.list.clear();
    }

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.route()
              .get("/logging-service-subsequently-decorated")
              .decorator(service -> (ctx, req) -> {
                  if (throwNonIgnorableException) {
                      throw new IllegalArgumentException("Non-ignorable exception");
                  }
                  return service.serve(ctx, req);
              })
              .decorator(LoggingService.newDecorator())
              .build((ctx, req) -> {
                  throw HttpStatusException.of(HttpStatus.BAD_REQUEST);
              });
            sb.route()
              .get("/logging-service-previously-decorated")
              .decorator(LoggingService.newDecorator())
              .decorator(service -> (ctx, req) -> {
                  if (throwNonIgnorableException) {
                      throw new IllegalArgumentException("Non-ignorable exception");
                  }
                  return service.serve(ctx, req);
              })
              .build((ctx, req) -> {
                  throw HttpStatusException.of(HttpStatus.BAD_REQUEST);
              });
            sb.unloggedExceptionsReportInterval(Duration.ofMillis(reportIntervalMillis));
        }
    };

    @Test
    void allExceptionShouldNotBeReportedWhenLoggingServiceIsSubsequentlyDecorated() throws Exception {
        assertThat(server.blockingWebClient().get("/logging-service-subsequently-decorated").status().code())
                .isEqualTo(400);
        throwNonIgnorableException = true;
        assertThat(server.blockingWebClient().get("/logging-service-subsequently-decorated").status().code())
                .isEqualTo(500);
        Thread.sleep(reportIntervalMillis + awaitIntervalMillis);
        assertThat(logAppender.list).isEmpty();
    }

    @Test
    void nonIgnorableExceptionShouldBeReportedWhenLoggingServiceIsPreviouslyDecorated() {
        throwNonIgnorableException = true;
        assertThat(server.blockingWebClient().get("/logging-service-previously-decorated").status().code())
                .isEqualTo(500);
        await().atMost(Duration.ofMillis(reportIntervalMillis + awaitIntervalMillis))
               .untilAsserted(() -> assertThat(logAppender.list).isNotEmpty());

        assertThat(logAppender.list
                           .stream()
                           .filter(event -> event.getFormattedMessage().contains(
                                   "Observed 1 exception(s) that didn't reach a LoggingService"))
                           .findAny()).isNotEmpty();
    }

    @Test
    void ignorableExceptionShouldNotBeReportedEvenThoughLoggingServiceIsPreviouslyDecorated() throws Exception {
        assertThat(server.blockingWebClient().get("/logging-service-previously-decorated").status().code())
                .isEqualTo(400);
        Thread.sleep(reportIntervalMillis + awaitIntervalMillis);
        assertThat(logAppender.list).isEmpty();
    }
}
