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
 *
 */

package com.linecorp.armeria.server.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletionException;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.common.prometheus.PrometheusMeterRegistries;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.util.Functions;
import com.linecorp.armeria.internal.logging.ContentPreviewingUtil;
import com.linecorp.armeria.internal.testing.ImmediateEventLoop;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.cors.CorsService;
import com.linecorp.armeria.server.metric.MetricCollectingService;
import com.linecorp.armeria.server.prometheus.PrometheusExpositionService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

class ContentPreviewerCancellationTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final PrometheusMeterRegistry registry = PrometheusMeterRegistries.newRegistry();
            sb.meterRegistry(registry)
              .decorator(
                      MetricCollectingService.newDecorator(
                              MeterIdPrefixFunction.ofDefault("armeria.server")))
              .decorator(ContentPreviewingService.newDecorator(Integer.MAX_VALUE))
              .decorator(
                      CorsService
                              .builderForAnyOrigin()
                              .allowCredentials()
                              .allowRequestMethods(HttpMethod.POST, HttpMethod.GET)
                              .maxAge(3600)
                              .newDecorator()
              )
              .annotatedService(new TestService())
              .service("/metrics",
                       PrometheusExpositionService.of(registry.getPrometheusRegistry()));
        }
    };

    @Test
    void shouldCompleteLogWithNoContentResponse() throws InterruptedException {
        final BlockingWebClient client = server.blockingWebClient();
        final AggregatedHttpResponse response = client.get("/test");
        assertThat(response.status()).isEqualTo(HttpStatus.NO_CONTENT);
        final ServiceRequestContext ctx = server.requestContextCaptor().take();
        // Make sure the log is complete.
        ctx.log().whenComplete().join();
    }

    @Test
    void shouldCompleteContentPreviewerResponseWhenCancelled() {
        final ContentPreviewerFactory previewerFactory = ContentPreviewerFactory.text(100);
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        HttpResponse response = HttpResponse.of(HttpStatus.NO_CONTENT);
        response = response.recover(cause -> null);
        response = response.mapHeaders(Function.identity());
        final HttpResponse contentPreviewingResponse =
                ContentPreviewingUtil.setUpResponseContentPreviewer(previewerFactory, ctx, response,
                                                                    Functions.second());
        contentPreviewingResponse.subscribe(new Subscriber<HttpObject>() {

            @Nullable
            private Subscription subscription;

            @Override
            public void onSubscribe(Subscription s) {
                subscription = s;
                s.request(1);
            }

            @Override
            public void onNext(HttpObject httpObject) {
                assertThat(httpObject).isInstanceOf(ResponseHeaders.class);
                subscription.cancel();
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onComplete() {}
        }, ImmediateEventLoop.INSTANCE);

        assertThatThrownBy(() -> {
            contentPreviewingResponse.whenComplete().join();
        }).isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(CancelledSubscriptionException.class);
    }

    @ExceptionHandler(SomeExceptionHandler.class)
    private static class TestService {
        @Get("/test")
        public void noContent() {}
    }

    private static final class SomeExceptionHandler implements ExceptionHandlerFunction {

        @Override
        public HttpResponse handleException(ServiceRequestContext ctx, HttpRequest req, Throwable cause) {
            return ExceptionHandlerFunction.fallthrough();
        }
    }
}
