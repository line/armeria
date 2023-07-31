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

package com.linecorp.armeria.it.logback;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Queue;
import java.util.concurrent.LinkedTransferQueue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.logging.RequestScopedMdc;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

class Logback14CompatibilityTest {

    private static final Logger logger = (Logger) LoggerFactory.getLogger(Logback14CompatibilityTest.class);

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.decorator((delegate, ctx, req) -> {
                RequestScopedMdc.put(ctx, "request", "scoped");
                return delegate.serve(ctx, req);
            });
            sb.service("/", (ctx, req) -> {
                logger.debug("Request scoped message");
                return HttpResponse.of("OK");
            });
        }
    };

    private TestAppender testAppender;

    @BeforeEach
    void beforeAll() {
        testAppender = new TestAppender();
        testAppender.start();
        logger.addAppender(testAppender);
    }

    @AfterEach
    void afterAll() {
        logger.detachAppender(testAppender);
    }

    @Test
    void shouldSetMdcWithRequestScopedMdc() {
        final BlockingWebClient client = server.blockingWebClient();
        final AggregatedHttpResponse response = client.get("/");
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        final ILoggingEvent loggingEvent =
                testAppender.events
                        .stream()
                        .filter(event -> "Request scoped message".equals(event.getFormattedMessage()))
                        .findFirst()
                        .get();

        assertThat(loggingEvent.getMDCPropertyMap()).containsEntry("request", "scoped");
    }

    private static final class TestAppender extends AppenderBase<ILoggingEvent> {
        private final Queue<ILoggingEvent> events = new LinkedTransferQueue<>();

        @Override
        protected void append(ILoggingEvent eventObject) {
            events.add(eventObject);
        }
    }
}
