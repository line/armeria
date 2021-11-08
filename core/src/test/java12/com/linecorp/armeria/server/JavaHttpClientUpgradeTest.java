/*
 * Copyright 2021 LINE Corporation
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

import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class JavaHttpClientUpgradeTest {

    static int maxRequestLength = 10;

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.requestTimeoutMillis(0);
            sb.idleTimeoutMillis(0);
            sb.maxRequestLength(maxRequestLength);
            sb.decorator(LoggingService.newDecorator());
            sb.service("/echo", (ctx, req) -> {
                return HttpResponse.from(req.aggregate().thenApply(agg -> {
                    return HttpResponse.of(ResponseHeaders.of(200), agg.content());
                }));
            });
        }
    };

    @EnumSource(Version.class)
    @ParameterizedTest
    void shouldHandleLargeData(Version version) throws Exception {
        for (int i = -1; i <= 1; i++) {
            final byte[] bytes = new byte[maxRequestLength + i];
            ThreadLocalRandom.current().nextBytes(bytes);

            final HttpClient client = HttpClient.newHttpClient();
            final HttpRequest request =
                    HttpRequest.newBuilder()
                            .version(version)
                               .uri(server.httpUri().resolve("/echo"))
                               .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                               .build();

            if (i <= 0) {
                final byte[] body = client.send(request, BodyHandlers.ofByteArray()).body();
                assertThat(body).isEqualTo(bytes);
            } else {
                final int statusCode = client.send(request, BodyHandlers.discarding()).statusCode();
                assertThat(statusCode).isEqualTo(HttpStatus.REQUEST_ENTITY_TOO_LARGE.code());
            }
        }
    }
}
