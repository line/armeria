/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.ProducesText;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ContentPreviewingErrorResponseTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.errorHandler((ctx, cause) -> {
                return HttpResponse.of("errorHandler: " + cause.getMessage());
            });

            sb.service("/aborted/http-status-exception", (ctx, req) ->
                    HttpResponse.ofFailure(HttpStatusException.of(HttpStatus.INTERNAL_SERVER_ERROR)));
            sb.service("/aborted/unexpected-exception", (ctx, req) ->
                    HttpResponse.ofFailure(new IllegalStateException("Oops!")));

            sb.service("/throw/http-status-exception", (ctx, req) -> {
                throw HttpStatusException.of(HttpStatus.INTERNAL_SERVER_ERROR);
            });
            sb.service("/throw/unexpected-exception", (ctx, req) -> {
                throw new IllegalStateException("Oops!");
            });

            sb.annotatedService("/annotated", new ContentPreviewingAnnotatedService());
            sb.annotatedService("/annotatedExceptionHandler",
                                new ContentPreviewingAnnotatedService(),
                                (ExceptionHandlerFunction) (ctx, req, cause) -> {
                                    return HttpResponse.of("exceptionHandler: " + cause.getMessage());
                                });

            sb.decorator(LoggingService.newDecorator());
            sb.decorator(ContentPreviewingService.newDecorator(Integer.MAX_VALUE));
        }
    };

    @ParameterizedTest
    @ArgumentsSource(ShouldRecordErrorResponseContentPreviewingArgumentsProvider.class)
    void shouldRecordErrorResponseContentPreviewing(String path, String responseContent) throws Exception {
        final BlockingWebClient client = server.blockingWebClient();
        final AggregatedHttpResponse res = client.get(path);
        assertThat(res.contentUtf8()).isEqualTo(responseContent);

        final RequestContext ctx = server.requestContextCaptor().poll();
        final RequestLog log = ctx.log().whenComplete().join();

        assertThat(log.responseContentPreview()).isEqualTo(responseContent);
    }

    @ProducesText
    static final class ContentPreviewingAnnotatedService {
        @Get("/aborted/http-status-exception")
        public HttpResponse abortedHttpStatusException() {
            return HttpResponse.ofFailure(HttpStatusException.of(HttpStatus.INTERNAL_SERVER_ERROR));
        }

        @Get("/aborted/unexpected-exception")
        public HttpResponse abortedUnexpectedException() {
            return HttpResponse.ofFailure(new IllegalStateException("Oops!"));
        }

        @Get("/throw/http-status-exception")
        public HttpResponse throwHttpStatusException() {
            throw HttpStatusException.of(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Get("/throw/unexpected-exception")
        public HttpResponse throwUnexpectedException() {
            throw new IllegalStateException("Oops!");
        }
    }

    private static final class ShouldRecordErrorResponseContentPreviewingArgumentsProvider
            implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
            return Stream.of(Arguments.of("/aborted/http-status-exception",
                                          "errorHandler: 500 Internal Server Error"),
                             Arguments.of("/aborted/unexpected-exception",
                                          "errorHandler: Oops!"),
                             Arguments.of("/throw/http-status-exception",
                                          "errorHandler: 500 Internal Server Error"),
                             Arguments.of("/throw/unexpected-exception",
                                          "errorHandler: Oops!"),
                             Arguments.of("/annotated/aborted/http-status-exception",
                                          "errorHandler: 500 Internal Server Error"),
                             Arguments.of("/annotated/aborted/unexpected-exception",
                                          "errorHandler: Oops!"),
                             Arguments.of("/annotated/throw/http-status-exception",
                                          "errorHandler: 500 Internal Server Error"),
                             Arguments.of("/annotated/throw/unexpected-exception",
                                          "errorHandler: Oops!"),
                             Arguments.of("/annotatedExceptionHandler/aborted/http-status-exception",
                                          "exceptionHandler: 500 Internal Server Error"),
                             Arguments.of("/annotatedExceptionHandler/aborted/unexpected-exception",
                                          "exceptionHandler: Oops!"),
                             Arguments.of("/annotatedExceptionHandler/throw/http-status-exception",
                                          "exceptionHandler: 500 Internal Server Error"),
                             Arguments.of("/annotatedExceptionHandler/throw/unexpected-exception",
                                          "exceptionHandler: Oops!"));
        }
    }
}
