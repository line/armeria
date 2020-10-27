/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.client.circuitbreaker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class CircuitBreakerClientTest {

    private static final String remoteServiceName = "testService";

    private static final ClientRequestContext ctx =
            ClientRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/dummy-path"))
                                .endpoint(Endpoint.of("dummyhost", 8080))
                                .build();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/unavailable", (ctx, req) -> HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE));
        }
    };

    @Test
    void testPerMethodDecorator() {
        final CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);
        when(circuitBreaker.canRequest()).thenReturn(false);

        @SuppressWarnings("unchecked")
        final Function<String, CircuitBreaker> factory = mock(Function.class);
        when(factory.apply(any())).thenReturn(circuitBreaker);

        final int COUNT = 2;
        failFastInvocation(CircuitBreakerClient.newPerMethodDecorator(factory, rule()),
                           HttpMethod.GET, COUNT);

        verify(circuitBreaker, times(COUNT)).canRequest();
        verify(factory, times(1)).apply("GET");
    }

    @Test
    void testPerHostDecorator() {
        final CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);
        when(circuitBreaker.canRequest()).thenReturn(false);

        @SuppressWarnings("unchecked")
        final Function<String, CircuitBreaker> factory = mock(Function.class);
        when(factory.apply(any())).thenReturn(circuitBreaker);

        final int COUNT = 2;
        failFastInvocation(CircuitBreakerClient.newPerHostDecorator(factory, rule()),
                           HttpMethod.GET, COUNT);

        verify(circuitBreaker, times(COUNT)).canRequest();
        verify(factory, times(1)).apply("dummyhost:8080");
    }

    @Test
    void testPerPathDecorator() {
        final CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);
        when(circuitBreaker.canRequest()).thenReturn(false);

        @SuppressWarnings("unchecked")
        final Function<String, CircuitBreaker> factory = mock(Function.class);
        when(factory.apply(any())).thenReturn(circuitBreaker);

        final int COUNT = 2;
        failFastInvocation(CircuitBreakerClient.newPerPathDecorator(factory, rule()),
                           HttpMethod.GET, COUNT);

        verify(circuitBreaker, times(COUNT)).canRequest();
        verify(factory, times(1)).apply("/dummy-path");
    }

    @Test
    void testPerPathDecoratorWithContent() {
        final CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);
        when(circuitBreaker.canRequest()).thenReturn(false);

        @SuppressWarnings("unchecked")
        final Function<String, CircuitBreaker> factory = mock(Function.class);
        when(factory.apply(any())).thenReturn(circuitBreaker);

        final int COUNT = 2;
        failFastInvocation(CircuitBreakerClient.newPerPathDecorator(factory, ruleWithResponse()),
                           HttpMethod.GET, COUNT);

        verify(circuitBreaker, times(COUNT)).canRequest();
        verify(factory, times(1)).apply("/dummy-path");
    }

    @Test
    void testPerHostAndMethodDecorator() {
        final CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);
        when(circuitBreaker.canRequest()).thenReturn(false);

        @SuppressWarnings("unchecked")
        final BiFunction<String, String, CircuitBreaker> factory = mock(BiFunction.class);
        when(factory.apply(any(), any())).thenReturn(circuitBreaker);

        final int COUNT = 2;
        failFastInvocation(CircuitBreakerClient.newPerHostAndMethodDecorator(factory, rule()),
                           HttpMethod.GET, COUNT);

        verify(circuitBreaker, times(COUNT)).canRequest();
        verify(factory, times(1)).apply("dummyhost:8080", "GET");
    }

    @Test
    void testPerHostAndPathDecorator() {
        final CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);
        when(circuitBreaker.canRequest()).thenReturn(false);

        @SuppressWarnings("unchecked")
        final CircuitBreakerFactory factory = mock(CircuitBreakerFactory.class);
        when(factory.apply(any(), any(), any())).thenReturn(circuitBreaker);

        final int COUNT = 2;
        failFastInvocation(
                CircuitBreakerClient.newDecorator(
                        new CircuitBreakerMapping.Builder().perHost().perPath().build(factory), rule()),
                HttpMethod.GET,
                COUNT);

        verify(circuitBreaker, times(COUNT)).canRequest();
        verify(factory, times(1))
                .apply("dummyhost:8080", null, "/dummy-path");
    }

    @Test
    void testPerHostAndPathDecoratorWithContent() {
        final CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);
        when(circuitBreaker.canRequest()).thenReturn(false);

        @SuppressWarnings("unchecked")
        final CircuitBreakerFactory factory = mock(CircuitBreakerFactory.class);
        when(factory.apply(any(), any(), any())).thenReturn(circuitBreaker);

        final int COUNT = 2;
        failFastInvocation(
                CircuitBreakerClient.newDecorator(
                        new CircuitBreakerMapping.Builder().perHost().perPath().build(factory),
                        ruleWithResponse()),
                HttpMethod.GET,
                COUNT);

        verify(circuitBreaker, times(COUNT)).canRequest();
        verify(factory, times(1))
                .apply("dummyhost:8080", null, "/dummy-path");
    }

    @Test
    void testPerMethodAndPathDecorator() {
        final CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);
        when(circuitBreaker.canRequest()).thenReturn(false);

        @SuppressWarnings("unchecked")
        final CircuitBreakerFactory factory = mock(CircuitBreakerFactory.class);
        when(factory.apply(any(), any(), any())).thenReturn(circuitBreaker);

        final int COUNT = 2;
        failFastInvocation(
                CircuitBreakerClient.newDecorator(
                        new CircuitBreakerMapping.Builder().perMethod().perPath().build(factory), rule()),
                HttpMethod.GET,
                COUNT);

        verify(circuitBreaker, times(COUNT)).canRequest();
        verify(factory, times(1)).apply(null, "GET", "/dummy-path");
    }

    @Test
    void testPerMethodAndPathDecoratorWithContent() {
        final CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);
        when(circuitBreaker.canRequest()).thenReturn(false);

        @SuppressWarnings("unchecked")
        final CircuitBreakerFactory factory = mock(CircuitBreakerFactory.class);
        when(factory.apply(any(), any(), any())).thenReturn(circuitBreaker);

        final int COUNT = 2;
        failFastInvocation(
                CircuitBreakerClient.newDecorator(
                        new CircuitBreakerMapping.Builder().perMethod().perPath().build(factory),
                        ruleWithResponse()),
                HttpMethod.GET,
                COUNT);

        verify(circuitBreaker, times(COUNT)).canRequest();
        verify(factory, times(1))
                .apply(null, "GET", "/dummy-path");
    }

    @Test
    void testPerHostAndMethodAndPathDecorator() {
        final CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);
        when(circuitBreaker.canRequest()).thenReturn(false);

        @SuppressWarnings("unchecked")
        final CircuitBreakerFactory factory = mock(CircuitBreakerFactory.class);
        when(factory.apply(any(), any(), any())).thenReturn(circuitBreaker);

        final int COUNT = 2;
        failFastInvocation(
                CircuitBreakerClient.newDecorator(
                        new CircuitBreakerMapping.Builder().perHost().perMethod().perPath().build(factory),
                        rule()),
                HttpMethod.GET,
                COUNT);

        verify(circuitBreaker, times(COUNT)).canRequest();
        verify(factory, times(1))
                .apply("dummyhost:8080","GET", "/dummy-path");
    }

    @Test
    void testPerHostAndMethodAndPathDecoratorWithContent() {
        final CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);
        when(circuitBreaker.canRequest()).thenReturn(false);

        @SuppressWarnings("unchecked")
        final CircuitBreakerFactory factory = mock(CircuitBreakerFactory.class);
        when(factory.apply(any(), any(), any())).thenReturn(circuitBreaker);

        final int COUNT = 2;
        failFastInvocation(
                CircuitBreakerClient.newDecorator(
                        new CircuitBreakerMapping.Builder().perHost().perMethod().perPath().build(factory),
                        ruleWithResponse()),
                HttpMethod.GET,
                COUNT);

        verify(circuitBreaker, times(COUNT)).canRequest();
        verify(factory, times(1))
                .apply("dummyhost:8080","GET", "/dummy-path");
    }

    @Test
    void ruleWithoutContent() {
        final CircuitBreakerRule rule = CircuitBreakerRule.onServerErrorStatus();
        circuitBreakerIsOpenOnServerError(CircuitBreakerClient.builder(rule));
    }

    @Test
    void ruleWithContent() {
        final CircuitBreakerRuleWithContent<HttpResponse> rule =
                CircuitBreakerRuleWithContent.<HttpResponse>builder().onServerErrorStatus().thenFailure();
        circuitBreakerIsOpenOnServerError(CircuitBreakerClient.builder(rule, 10000));
    }

    private static void circuitBreakerIsOpenOnServerError(CircuitBreakerClientBuilder builder) {
        final AtomicLong ticker = new AtomicLong();
        final int minimumRequestThreshold = 2;
        final Duration circuitOpenWindow = Duration.ofSeconds(60);
        final Duration counterSlidingWindow = Duration.ofSeconds(180);
        final Duration counterUpdateInterval = Duration.ofMillis(1);

        final CircuitBreaker circuitBreaker =
                CircuitBreaker.builder(remoteServiceName)
                              .minimumRequestThreshold(minimumRequestThreshold)
                              .circuitOpenWindow(circuitOpenWindow)
                              .counterSlidingWindow(counterSlidingWindow)
                              .counterUpdateInterval(counterUpdateInterval)
                              .ticker(ticker::get)
                              .listener(new CircuitBreakerListenerAdapter() {
                                  @Override
                                  public void onEventCountUpdated(String circuitBreakerName,
                                                                  EventCount eventCount) throws Exception {
                                      ticker.addAndGet(Duration.ofMillis(1).toNanos());
                                  }
                              })
                              .build();

        final CircuitBreakerMapping mapping = (ctx, req) -> circuitBreaker;
        final WebClient client = WebClient.builder(server.httpUri())
                                          .decorator(builder.mapping(mapping).newDecorator())
                                          .build();

        ticker.addAndGet(Duration.ofMillis(1).toNanos());
        // CLOSED
        for (int i = 0; i < minimumRequestThreshold + 1; i++) {
            // Need to call execute() one more to change the state of the circuit breaker.
            final long currentTime = ticker.get();
            assertThat(client.get("/unavailable").aggregate().join().status())
                    .isSameAs(HttpStatus.SERVICE_UNAVAILABLE);
            await().until(() -> currentTime != ticker.get());
        }

        await().untilAsserted(() -> assertThat(circuitBreaker.canRequest()).isFalse());
        // OPEN
        assertThatThrownBy(() -> client.get("/unavailable").aggregate().join())
                .hasCauseExactlyInstanceOf(FailFastException.class);
    }

    private static void failFastInvocation(
            Function<? super HttpClient, CircuitBreakerClient> decorator, HttpMethod method, int count) {
        for (int i = 0; i < count; i++) {
            final HttpRequest req = HttpRequest.of(method, "/");
            assertThatThrownBy(() -> invoke(decorator, req)).isInstanceOf(FailFastException.class);
        }
    }

    private static void invoke(
            Function<? super HttpClient, CircuitBreakerClient> decorator,
            HttpRequest req) throws Exception {
        final HttpClient client = mock(HttpClient.class);
        final HttpClient decorated = decorator.apply(client);

        decorated.execute(ctx, req).aggregate();
    }

    /**
     * Returns a {@link CircuitBreakerRule} which returns {@link CircuitBreakerDecision#success()}
     * when there's no {@link Exception} raised.
     */
    private static CircuitBreakerRule rule() {
        return CircuitBreakerRule.builder()
                                 .onException().thenFailure()
                                 .orElse(CircuitBreakerRule.builder().thenSuccess());
    }

    /**
     * A rule with content that returns failure when am exception is thrown, and success otherwise.
     */
    private static CircuitBreakerRuleWithContent<HttpResponse> ruleWithResponse() {
        return (ctx, response, cause) -> CompletableFuture.completedFuture(
                cause == null ? CircuitBreakerDecision.failure() : CircuitBreakerDecision.success());
    }
}
