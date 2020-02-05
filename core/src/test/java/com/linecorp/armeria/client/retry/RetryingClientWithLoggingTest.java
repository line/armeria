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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import com.linecorp.armeria.client.ClientRequestContext;
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
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit4.server.ServerRule;

public class RetryingClientWithLoggingTest {

    @Rule
    public final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/hello", new AbstractHttpService() {
                final AtomicInteger reqCount = new AtomicInteger();

                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                        throws Exception {
                    ctx.addAdditionalResponseTrailer(HttpHeaderNames.of("foo"), "bar");
                    if (reqCount.getAndIncrement() < 2) {
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

    @Before
    public void init() {
        logIndex.set(0);
        logResult.clear();
    }

    // WebClient -> RetryingClient -> LoggingClient -> HttpClientDelegate
    // In this case, all of the requests and responses are logged.
    @Test
    public void retryingThenLogging() {
        successLogIndex = 5;
        final RetryStrategyWithContent<HttpResponse> retryStrategy =
                (ctx, response) -> response.aggregate().handle((msg, cause) -> {
                    if ("hello".equals(msg.contentUtf8())) {
                        return null;
                    }
                    return Backoff.ofDefault();
                });
        final WebClient client = WebClient.builder(server.httpUri())
                                          .decorator(loggingDecorator())
                                          .decorator(RetryingClient.builder(retryStrategy)
                                                                   .newDecorator())
                                          .build();
        assertThat(client.get("/hello").aggregate().join().contentUtf8()).isEqualTo("hello");

        // wait until 6 logs(3 requests and 3 responses) are called back
        await().untilAsserted(() -> assertThat(logResult.size()).isEqualTo(successLogIndex + 1));
    }

    // WebClient -> LoggingClient -> RetryingClient -> HttpClientDelegate
    // In this case, only the first request and the last response are logged.
    @Test
    public void loggingThenRetrying() throws Exception {
        successLogIndex = 1;
        final WebClient client = WebClient.builder(server.httpUri())
                                          .decorator(RetryingClient.newDecorator(
                                                  RetryStrategy.onServerErrorStatus()))
                                          .decorator(loggingDecorator())
                                          .build();
        assertThat(client.get("/hello").aggregate().join().contentUtf8()).isEqualTo("hello");

        // wait until 2 logs are called back
        await().untilAsserted(() -> assertThat(logResult.size()).isEqualTo(successLogIndex + 1));

        // toStringRequestOnly() is same in the request log and the response log
        assertThat(logResult.get(0).toStringRequestOnly()).isEqualTo(logResult.get(1).toStringRequestOnly());
    }

    private Function<? super HttpClient, ? extends HttpClient> loggingDecorator() {
        return delegate -> new SimpleDecoratingHttpClient(delegate) {
            @Override
            public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
                ctx.log().whenRequestComplete().thenAccept(log -> listener.accept(log.partial()));
                ctx.log().whenComplete().thenAccept(listener);
                return delegate().execute(ctx, req);
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
