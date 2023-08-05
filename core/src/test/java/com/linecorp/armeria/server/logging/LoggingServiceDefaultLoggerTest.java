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
package com.linecorp.armeria.server.logging;

import static com.linecorp.armeria.server.logging.LoggingServiceTest.delegate;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Spy;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class LoggingServiceDefaultLoggerTest {

    @Spy
    final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
    final Logger defaultLogger = (Logger) LoggerFactory.getLogger(LoggingService.class);

    @BeforeEach
    public void attachAppender() {
        logAppender.start();
        defaultLogger.addAppender(logAppender);
    }

    @AfterEach
    public void detachAppender() {
        defaultLogger.detachAppender(logAppender);
        logAppender.list.clear();
    }

    @Test
    void defaultLoggerUsedIfLogWriterNotSet() throws Exception {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final LoggingService service = LoggingService.newDecorator().apply(delegate);
        service.serve(ctx, ctx.request());
        assertThat(logAppender.list).hasSize(2);
        logAppender.list.forEach(iLoggingEvent -> {
            assertThat(iLoggingEvent.getFormattedMessage()).contains(ctx.toString());
            assertThat(iLoggingEvent.getLoggerName()).isEqualTo(
                    "com.linecorp.armeria.server.logging.LoggingService");
        });
    }
}
