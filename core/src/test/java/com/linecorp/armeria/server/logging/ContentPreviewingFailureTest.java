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

package com.linecorp.armeria.server.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.atomic.AtomicReference;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ContentPreviewingFailureTest {

    private static final AtomicReference<ServiceRequestContext> ctxRef = new AtomicReference<>();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/http-status-exception", (ctx, req) -> {
                throw HttpStatusException.of(500);
            });
            sb.service("/http-status-exception-with-cause", (ctx, req) -> {
                throw HttpStatusException
                        .of(HttpStatus.SERVICE_UNAVAILABLE, new RuntimeException("with-status"));
            });
            sb.service("/unexpected-exception", (ctx, req) -> {
                throw new IllegalStateException("Oops!");
            });
            sb.decorator((delegate, ctx, req) -> {
                ctxRef.set(ctx);
                return delegate.serve(ctx, req);
            });

            sb.decorator(ContentPreviewingService.newDecorator(128));
            sb.decorator(LoggingService.newDecorator());
        }
    };

    @AfterEach
    void cleanCtx() {
        ctxRef.set(null);
    }

    @CsvSource({ "/http-status-exception, 500",
                 "/http-status-exception-with-cause, 503",
                 "/unexpected-exception, 500" })
    @ParameterizedTest
    void shouldCompleteLogWhenExceptionIsThrown(String path, int status) {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse response = client.post(path, "Hello, Armeria!").aggregate().join();
        await().untilAtomic(ctxRef, Matchers.notNullValue());
        // Make sure to a log is completed
        final RequestLog log = ctxRef.get().log().whenComplete().join();

        assertThat(response.status().code()).isEqualTo(status);

        switch (path) {
            case "/http-status-exception":
                assertThat(log.requestCause()).isNull();
                assertThat(log.responseCause()).isNull();
                break;
            case "/http-status-exception-with-cause":
                assertThat(log.requestCause())
                        .isInstanceOf(RuntimeException.class)
                        .hasMessage("with-status");
                assertThat(log.responseCause())
                        .isInstanceOf(RuntimeException.class)
                        .hasMessage("with-status");
                break;
            case "/unexpected-exception":
                assertThat(log.requestCause())
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("Oops!");
                assertThat(log.responseCause())
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("Oops!");
                break;
            default:
                // Should not reach here.
                throw new Error();
        }
    }
}
