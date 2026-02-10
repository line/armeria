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

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceErrorHandler;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.ProducesText;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ContentPreviewingErrorResponseTest {

    @RegisterExtension
    static final ServerExtension serverWithErrorHandler = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            configureServer(sb, true);
        }
    };

    @RegisterExtension
    static final ServerExtension serverWithoutServerErrorHandler = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            configureServer(sb, false);
        }
    };

    private static void configureServer(ServerBuilder sb, boolean errorHandler) {
        if (errorHandler) {
            sb.errorHandler((ctx, cause) -> {
                return HttpResponse.of("errorHandler: " + cause.getMessage());
            });
        }

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
        if (errorHandler) {
            sb.annotatedService("/annotatedExceptionHandler",
                                new ContentPreviewingAnnotatedService(),
                                (ExceptionHandlerFunction) (ctx, req, cause) -> {
                                    return HttpResponse.of("exceptionHandler: " + cause.getMessage());
                                });
        }

        final ServiceErrorHandler serviceErrorHandler = (ctx, cause) -> {
            return HttpResponse.of("serviceErrorHandler: " + cause.getMessage());
        };
        sb.withRoute(bindingBuilder -> {
            if (errorHandler) {
                bindingBuilder.errorHandler(serviceErrorHandler);
            }
            bindingBuilder.path("/binding/aborted/http-status-exception")
                          .build((ctx, req) -> {
                              return HttpResponse.ofFailure(
                                      HttpStatusException.of(HttpStatus.INTERNAL_SERVER_ERROR));
                          });
        });
        sb.withRoute(bindingBuilder -> {
            if (errorHandler) {
                bindingBuilder.errorHandler(serviceErrorHandler);
            }
            bindingBuilder.path("/binding/aborted/unexpected-exception")
                          .build((ctx, req) -> {
                              return HttpResponse.ofFailure(new IllegalStateException("Oops!"));
                          });
        });
        sb.withRoute(bindingBuilder -> {
            if (errorHandler) {
                bindingBuilder.errorHandler(serviceErrorHandler);
            }
            bindingBuilder.path("/binding/throw/http-status-exception")
                          .build((ctx, req) -> {
                              throw HttpStatusException.of(HttpStatus.INTERNAL_SERVER_ERROR);
                          });
        });
        sb.withRoute(bindingBuilder -> {
            if (errorHandler) {
                bindingBuilder.errorHandler(serviceErrorHandler);
            }
            bindingBuilder.path("/binding/throw/unexpected-exception")
                          .build((ctx, req) -> {
                              throw new IllegalStateException("Oops!");
                          });
        });

        sb.decorator(LoggingService.newDecorator());
        sb.decorator(ContentPreviewingService.newDecorator(Integer.MAX_VALUE));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/aborted/http-status-exception",
            "/aborted/unexpected-exception",
            "/throw/http-status-exception",
            "/throw/unexpected-exception",
            "/annotated/aborted/http-status-exception",
            "/annotated/aborted/unexpected-exception",
            "/annotated/throw/http-status-exception",
            "/annotated/throw/unexpected-exception",
            "/binding/aborted/http-status-exception",
            "/binding/aborted/unexpected-exception",
            "/binding/throw/http-status-exception",
            "/binding/throw/unexpected-exception",
    })
    void shouldSetNullContentPreviewWhenAnExceptionIsRaisedAndErrorHandlerSet(String path) throws Exception {
        serverWithErrorHandler.blockingWebClient().get(path);

        final RequestContext ctx = serverWithErrorHandler.requestContextCaptor().poll();
        final RequestLog log = ctx.log().whenComplete().join();

        assertThat(log.responseContentPreview()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/aborted/http-status-exception",
            "/aborted/unexpected-exception",
            "/throw/http-status-exception",
            "/throw/unexpected-exception",
            "/annotated/aborted/http-status-exception",
            "/annotated/aborted/unexpected-exception",
            "/annotated/throw/http-status-exception",
            "/annotated/throw/unexpected-exception",
            "/binding/aborted/http-status-exception",
            "/binding/aborted/unexpected-exception",
            "/binding/throw/http-status-exception",
            "/binding/throw/unexpected-exception",
    })
    void shouldSetNullContentPreviewWhenAnExceptionIsRaisedAndNoErrorHandlerSet(String path) throws Exception {
        serverWithoutServerErrorHandler.blockingWebClient().get(path);

        final RequestContext ctx = serverWithoutServerErrorHandler.requestContextCaptor().poll();
        final RequestLog log = ctx.log().whenComplete().join();

        assertThat(log.responseContentPreview()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/annotatedExceptionHandler/aborted/http-status-exception",
            "/annotatedExceptionHandler/aborted/unexpected-exception",
            "/annotatedExceptionHandler/throw/http-status-exception",
            "/annotatedExceptionHandler/throw/unexpected-exception",
    })
    void annotatedServiceExceptionHandlerAlwaysRecordContentPreview(String path) throws Exception {
        final AggregatedHttpResponse res = serverWithErrorHandler.blockingWebClient().get(path);
        final String content = res.contentUtf8();

        final RequestContext ctx = serverWithErrorHandler.requestContextCaptor().poll();
        final RequestLog log = ctx.log().whenComplete().join();

        assertThat(log.responseContentPreview()).isEqualTo(content);
    }

    @ProducesText
    static final class ContentPreviewingAnnotatedService {
        @Get("/aborted/http-status-exception")
        public HttpResponse abortedHttpStatusException() {
            return HttpResponse.ofFailure(HttpStatusException.of(HttpStatus.INTERNAL_SERVER_ERROR));
        }

        @Get("/aborted/unexpected-exception")
        public HttpResponse abortedUnexpectedException() {
            return HttpResponse.ofFailure(new IllegalArgumentException("Oops!"));
        }

        @Get("/throw/http-status-exception")
        public HttpResponse throwHttpStatusException() {
            throw HttpStatusException.of(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Get("/throw/unexpected-exception")
        public HttpResponse throwUnexpectedException() {
            throw new IllegalArgumentException("Oops!");
        }
    }
}
