/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.server.docs;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Get;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class DocServiceEmptyServicesTest {

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void emptyServices(boolean excludeAllServices) {
        final Logger logger = (Logger) LoggerFactory.getLogger(DocService.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            final ServerBuilder sb = Server.builder()
                                           .http(0)
                                           .service("/", (ctx, req) -> HttpResponse.of("OK"));
            final DocServiceBuilder docService = DocService.builder();
            if (excludeAllServices) {
                sb.annotatedService(new Object() {
                    @Get("/annotated")
                    public String get() {
                        return "OK";
                    }
                });
                docService.exclude((pluginName, serviceName, methodName) -> true);
            }
            sb.serviceUnder("/docs", docService.build());

            try (Server server = sb.build()) {
                server.start().join();

                final BlockingWebClient client = BlockingWebClient.of("http://127.0.0.1:" +
                                                                      server.activeLocalPort());
                final AggregatedHttpResponse specification =
                        client.get("/docs/specification.json");
                assertThat(specification.status()).isEqualTo(HttpStatus.OK);
                assertThatJson(specification.contentUtf8()).node("services").isArray().ofLength(0);

                final AggregatedHttpResponse schemas = client.get("/docs/schemas.json");
                assertThat(schemas.status()).isEqualTo(HttpStatus.OK);
                assertThatJson(schemas.contentUtf8()).isEqualTo("{}");
                assertThat(appender.list).noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN));
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
