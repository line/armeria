/*
 * Copyright 2022 LINE Corporation
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

import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.net.http.HttpResponse.BodyHandlers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class InvalidPathWithDataTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.requestTimeoutMillis(0);
            sb.decorator(LoggingService.newDecorator());
            sb.service("/foo", (ctx, req) -> {
                return HttpResponse.of(req.aggregate().thenApply(agg -> HttpResponse.of(agg.contentUtf8())));
            });
        }
    };

    @Test
    void invalidPath() throws Exception {
        final String invalidPath = "/foo?download=../../secret.txt";
        final RequestTarget reqTarget = RequestTarget.forServer(invalidPath);
        assertThat(reqTarget).isNull();

        final HttpClient client = HttpClient.newHttpClient();

        // Send a normal request to complete an upgrade request successfully.
        final java.net.http.HttpRequest normalRequest =
                java.net.http.HttpRequest.newBuilder()
                                         .version(Version.HTTP_2)
                                         .uri(server.httpUri().resolve("/foo"))
                                         .GET()
                                         .build();

        final ListAppender<ILoggingEvent> logWatcher = new ListAppender<>();
        logWatcher.start();
        final Logger logger = (Logger) LoggerFactory.getLogger(Http2RequestDecoder.class);
        logger.setLevel(Level.DEBUG);
        logger.addAppender(logWatcher);

        final String bodyNormal = client.send(normalRequest, BodyHandlers.ofString()).body();
        assertThat(bodyNormal).isEmpty();

        final java.net.http.HttpRequest invalidRequest =
                java.net.http.HttpRequest.newBuilder()
                                         .version(Version.HTTP_2)
                                         .uri(server.httpUri().resolve(invalidPath))
                                         .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                                                 "Hello Armeria!"))
                                         .build();

        final java.net.http.HttpResponse<String> response =
                client.send(invalidRequest, BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("Invalid request path");

        await().untilAsserted(() -> {
            assertThat(logWatcher.list)
                    .anyMatch(event -> {
                        final String logMessage = event.getFormattedMessage();
                        return event.getLevel().equals(Level.DEBUG) &&
                               logMessage.contains("Received a DATA frame for a finished stream");
                    });
        });
    }
}
