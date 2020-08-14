/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.SimpleDecoratingHttpClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailabilityException;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class RetryingClientWithLoggingTest {

    @RegisterExtension
    final ServerExtension server = new ServerExtension() {
        @Override
        protected boolean runForEachTest() {
            return true;
        }

        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/hello", new AbstractHttpService() {
                final AtomicInteger reqCount = new AtomicInteger();

                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    ctx.mutateAdditionalResponseTrailers(
                            mutator -> mutator.add(HttpHeaderNames.of("foo"), "bar"));
                    if (reqCount.getAndIncrement() < 1) {
                        return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
                    } else {
                        return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "hello");
                    }
                }
            });
        }
    };

    private final Consumer<RequestLog> listener = new Consumer<RequestLog>() {
        @Override
        public void accept(RequestLog log) {
            logResult.add(log);
            final int index = logIndex.getAndIncrement();
            if (index % 2 == 0) {
                assertRequestSideLog(log);
            } else {
                assertResponseSideLog(log, index == successLogIndex);
            }
        }
    };

    private final AtomicInteger logIndex = new AtomicInteger();
    private int successLogIndex;
    private final List<RequestLog> logResult = new ArrayList<>();

    @BeforeEach
    void init() {
        logIndex.set(0);
        logResult.clear();
    }

    // WebClient -> RetryingClient -> LoggingClient -> HttpClientDelegate
    // In this case, all of the requests and responses are logged.
    @Test
    void retryingThenLogging() throws InterruptedException {
        successLogIndex = 3;
        final RetryRuleWithContent<HttpResponse> retryRule =
                RetryRuleWithContent.onResponse((unused, response) -> {
                    return response.aggregate().thenApply(content -> !"hello".equals(content.contentUtf8()));
                });

        final WebClient client = WebClient.builder(server.httpUri())
                                          .decorator(loggingDecorator())
                                          .decorator(RetryingClient.builder(retryRule).newDecorator())
                                          .decorator((delegate, ctx, req) -> {
                                              final RequestLogBuilder logBuilder = ctx.logBuilder();
                                              logBuilder.name("FooService", "foo");
                                              logBuilder.requestContent("bar", null);
                                              logBuilder.defer(RequestLogProperty.REQUEST_CONTENT_PREVIEW);
                                              logBuilder.defer(RequestLogProperty.RESPONSE_CONTENT);
                                              return delegate.execute(ctx, req);
                                          })
                                          .build();
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThat(client.get("/hello").aggregate().join().contentUtf8()).isEqualTo("hello");
            final RequestLogBuilder logBuilder = captor.get().logBuilder();
            logBuilder.requestContentPreview("baz");

            await().untilAsserted(() -> assertThat(logResult).hasSize(successLogIndex));
            TimeUnit.SECONDS.sleep(1);
            // The last log is not complete because the parent log doesn't set the response content yet.
            assertThat(logResult).hasSize(successLogIndex);
            logBuilder.responseContent("qux", null);
        }

        // wait until 4 logs(2 requests and 2 responses) are called back
        await().untilAsserted(() -> assertThat(logResult).hasSize(successLogIndex + 1));
        // Let's just check the first request log.
        final RequestLog requestLog = logResult.get(0);
        assertThat(requestLog.serviceName()).isEqualTo("FooService");
        assertThat(requestLog.name()).isEqualTo("foo");
        assertThat(requestLog.fullName()).isEqualTo("FooService/foo");
        assertThat(requestLog.requestContent()).isEqualTo("bar");
        assertThat(requestLog.requestContentPreview()).isEqualTo("baz");

        assertThat(logResult.get(3).responseContent()).isEqualTo("qux");
        // The response content of the first log is different.
        assertThat(logResult.get(1).responseContent()).isNull();
    }

    // WebClient -> LoggingClient -> RetryingClient -> HttpClientDelegate
    // In this case, only the first request and the last response are logged.
    @Test
    void loggingThenRetrying() throws Exception {
        successLogIndex = 1;
        final WebClient client =
                WebClient.builder(server.httpUri())
                         .decorator(RetryingClient.newDecorator(RetryRule.failsafe()))
                         .decorator(loggingDecorator())
                         .build();
        assertThat(client.get("/hello").aggregate().join().contentUtf8()).isEqualTo("hello");

        // wait until 2 logs are called back
        await().untilAsserted(() -> assertThat(logResult).hasSize(successLogIndex + 1));

        // toStringRequestOnly() is same in the request log and the response log
        assertThat(logResult.get(0).toStringRequestOnly()).isEqualTo(logResult.get(1).toStringRequestOnly());
    }

    private Function<? super HttpClient, ? extends HttpClient> loggingDecorator() {
        return delegate -> new SimpleDecoratingHttpClient(delegate) {
            @Override
            public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
                ctx.log().whenRequestComplete().thenAccept(log -> listener.accept(log.partial()));
                ctx.log().whenComplete().thenAccept(listener);
                return unwrap().execute(ctx, req);
            }
        };
    }

    private static void assertRequestSideLog(RequestLog log) {
        assertThat(log.requestHeaders()).isNotNull();
        assertThat(log.requestHeaders().path()).isEqualTo("/hello");

        assertThat(log.toStringResponseOnly()).isEqualTo("{}"); // empty response
        assertThatThrownBy(log::responseStartTimeMillis).isInstanceOf(RequestLogAvailabilityException.class);
    }

    private static void assertResponseSideLog(RequestLog log, boolean success) {
        assertThat(log.requestHeaders()).isNotNull();
        assertThat(log.responseHeaders()).isNotNull();
        assertThat(log.responseTrailers().get(HttpHeaderNames.of("foo"))).isEqualTo("bar");

        assertThat(log.responseHeaders().status()).isEqualTo(success ? HttpStatus.OK
                                                                     : HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
