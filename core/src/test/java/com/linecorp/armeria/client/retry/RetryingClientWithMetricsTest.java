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
import static org.awaitility.Awaitility.await;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.metric.MetricCollectingClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class RetryingClientWithMetricsTest {

    private static final MeterIdPrefixFunction meterIdPrefixFunction = MeterIdPrefixFunction.ofDefault("foo");

    @RegisterExtension
    final ServerExtension server = new ServerExtension() {
        @Override
        protected boolean runForEachTest() {
            return true;
        }

        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/ok", (ctx, req) -> HttpResponse.of(200));
            sb.service("/hello", new AbstractHttpService() {
                final AtomicInteger reqCount = new AtomicInteger();

                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                        throws Exception {
                    ctx.mutateAdditionalResponseTrailers(
                            mutator -> mutator.add(HttpHeaderNames.of("foo"), "bar"));
                    if (reqCount.getAndIncrement() < 2) {
                        return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
                    } else {
                        return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "hello");
                    }
                }
            });
        }
    };

    private ClientFactory clientFactory;
    private MeterRegistry meterRegistry;

    @BeforeEach
    public void init() {
        meterRegistry = new SimpleMeterRegistry();
        clientFactory = ClientFactory.builder()
                                     .meterRegistry(meterRegistry)
                                     .build();
    }

    @AfterEach
    public void destroy() {
        if (clientFactory != null) {
            clientFactory.closeAsync();
        }
    }

    // WebClient -> RetryingClient -> MetricCollectingClient -> HttpClientDelegate
    // In this case, all of the requests and responses are recorded.
    @Test
    void retryingThenMetricCollecting() throws Exception {
        final RetryRuleWithContent<HttpResponse> rule =
                (ctx, response, cause) -> response.aggregate().handle((msg, unused) -> {
                    if ("hello".equals(msg.contentUtf8())) {
                        return RetryDecision.noRetry();
                    }
                    return RetryDecision.retry(Backoff.ofDefault());
                });
        final WebClient client = WebClient.builder(server.httpUri())
                                          .factory(clientFactory)
                                          .decorator(MetricCollectingClient.newDecorator(meterIdPrefixFunction))
                                          .decorator(RetryingClient.newDecorator(rule))
                                          .build();
        assertThat(client.get("/hello").aggregate().join().contentUtf8()).isEqualTo("hello");

        // wait until 3 calls are recorded.
        await().untilAsserted(() -> {
            assertThat(MoreMeters.measureAll(meterRegistry))
                    .containsEntry("foo.requests#count{http.status=200,method=GET,result=success,service=none}",
                                   1.0)
                    .containsEntry("foo.requests#count{http.status=200,method=GET,result=failure,service=none}",
                                   0.0)
                    .containsEntry("foo.requests#count{http.status=500,method=GET,result=success,service=none}",
                                   0.0)
                    .containsEntry("foo.requests#count{http.status=500,method=GET,result=failure,service=none}",
                                   2.0);
        });
    }

    @Test
    void retryingThenMetricCollectingWithConnectionRefused() throws Exception {
        // The first request will fail with an UnprocessedException and
        // the second request will succeed with 200.
        final EndpointGroup group = EndpointGroup.of(Endpoint.of("127.0.0.1", 1),
                                                     server.httpEndpoint());
        final WebClient client =
                WebClient.builder(SessionProtocol.HTTP, group)
                         .factory(clientFactory)
                         .decorator(MetricCollectingClient.newDecorator(meterIdPrefixFunction))
                         .decorator(RetryingClient.newDecorator(RetryRule.onUnprocessed()))
                         .build();
        assertThat(client.get("/ok").aggregate().join().status()).isEqualTo(HttpStatus.OK);

        // wait until 2 calls are recorded.
        await().untilAsserted(() -> {
            assertThat(MoreMeters.measureAll(meterRegistry))
                    .containsEntry("foo.requests#count{http.status=200,method=GET,result=success,service=none}",
                                   1.0)
                    .containsEntry("foo.requests#count{http.status=200,method=GET,result=failure,service=none}",
                                   0.0)
                    .containsEntry("foo.requests#count{http.status=0,method=GET,result=success,service=none}",
                                   0.0)
                    .containsEntry("foo.requests#count{http.status=0,method=GET,result=failure,service=none}",
                                   1.0);
        });
    }

    // WebClient -> MetricCollectingClient -> RetryingClient -> HttpClientDelegate
    // In this case, only the first request and the last response are recorded.
    @Test
    public void metricCollectingThenRetrying() throws Exception {
        final WebClient client =
                WebClient.builder(server.httpUri())
                         .factory(clientFactory)
                         .decorator(RetryingClient.newDecorator(
                                 RetryRule.builder().onServerErrorStatus().onException().thenBackoff()))
                         .decorator(MetricCollectingClient.newDecorator(meterIdPrefixFunction))
                         .build();
        assertThat(client.get("/hello").aggregate().join().contentUtf8()).isEqualTo("hello");

        // wait until 1 call is recorded.
        await().untilAsserted(() -> {
            assertThat(MoreMeters.measureAll(meterRegistry))
                    .containsEntry("foo.requests#count{http.status=200,method=GET,result=success,service=none}",
                                   1.0)
                    .containsEntry("foo.requests#count{http.status=200,method=GET,result=failure,service=none}",
                                   0.0);
        });
    }

    @Test
    public void metricCollectingThenRetryingWithConnectionRefused() throws Exception {
        // The first request will fail with an UnprocessedException and
        // the second request will succeed with 200.
        final EndpointGroup group = EndpointGroup.of(Endpoint.of("127.0.0.1", 1),
                                                     server.httpEndpoint());
        final WebClient client =
                WebClient.builder(SessionProtocol.HTTP, group)
                         .factory(clientFactory)
                         .decorator(RetryingClient.newDecorator(RetryRule.onUnprocessed()))
                         .decorator(MetricCollectingClient.newDecorator(MeterIdPrefixFunction.ofDefault("foo")))
                         .build();

        assertThat(client.get("/ok").aggregate().join().status()).isEqualTo(HttpStatus.OK);

        // wait until 1 call is recorded.
        await().untilAsserted(() -> {
            assertThat(MoreMeters.measureAll(meterRegistry))
                    .containsEntry("foo.requests#count{http.status=200,method=GET,result=success,service=none}",
                                   1.0)
                    .containsEntry("foo.requests#count{http.status=200,method=GET,result=failure,service=none}",
                                   0.0);
        });
    }
}
