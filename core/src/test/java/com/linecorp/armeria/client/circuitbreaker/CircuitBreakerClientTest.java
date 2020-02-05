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
import java.util.function.Function;

import org.junit.ClassRule;
import org.junit.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit4.server.ServerRule;

public class CircuitBreakerClientTest {

    private static final String remoteServiceName = "testService";

    private static final ClientRequestContext ctx =
            ClientRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                .endpoint(Endpoint.of("dummyhost", 8080))
                                .build();

    @ClassRule
    public static final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/unavailable", (ctx, req) -> HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE));
        }
    };

    @Test
    public void testPerMethodDecorator() {
        final CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);
        when(circuitBreaker.canRequest()).thenReturn(false);

        @SuppressWarnings("unchecked")
        final Function<String, CircuitBreaker> factory = mock(Function.class);
        when(factory.apply(any())).thenReturn(circuitBreaker);

        final int COUNT = 2;
        failFastInvocation(CircuitBreakerClient.newPerMethodDecorator(factory, strategy()),
                           HttpMethod.GET, COUNT);

        verify(circuitBreaker, times(COUNT)).canRequest();
        verify(factory, times(1)).apply("GET");
    }

    @Test
    public void testPerHostDecorator() {
        final CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);
        when(circuitBreaker.canRequest()).thenReturn(false);

        @SuppressWarnings("unchecked")
        final Function<String, CircuitBreaker> factory = mock(Function.class);
        when(factory.apply(any())).thenReturn(circuitBreaker);

        final int COUNT = 2;
        failFastInvocation(CircuitBreakerClient.newPerHostDecorator(factory, strategy()),
                           HttpMethod.GET, COUNT);

        verify(circuitBreaker, times(COUNT)).canRequest();
        verify(factory, times(1)).apply("dummyhost:8080");
    }

    @Test
    public void testPerHostAndMethodDecorator() {
        final CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);
        when(circuitBreaker.canRequest()).thenReturn(false);

        @SuppressWarnings("unchecked")
        final Function<String, CircuitBreaker> factory = mock(Function.class);
        when(factory.apply(any())).thenReturn(circuitBreaker);

        final int COUNT = 2;
        failFastInvocation(CircuitBreakerClient.newPerHostAndMethodDecorator(factory, strategy()),
                           HttpMethod.GET, COUNT);

        verify(circuitBreaker, times(COUNT)).canRequest();
        verify(factory, times(1)).apply("dummyhost:8080#GET");
    }

    @Test
    public void strategyWithoutContent() {
        final CircuitBreakerStrategy strategy = CircuitBreakerStrategy.onServerErrorStatus();
        circuitBreakerIsOpenOnServerError(CircuitBreakerClient.builder(strategy));
    }

    @Test
    public void strategyWithContent() {
        final CircuitBreakerStrategyWithContent<HttpResponse> strategy =
                (ctx, response) -> response.aggregate().handle(
                        (msg, unused1) -> msg.status().codeClass() != HttpStatusClass.SERVER_ERROR);
        circuitBreakerIsOpenOnServerError(CircuitBreakerClient.builder(strategy));
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

        decorated.execute(ctx, req);
    }

    /**
     * Returns a {@link CircuitBreakerStrategy} which returns {@code true} when there's
     * no {@link Exception} raised.
     */
    private static CircuitBreakerStrategy strategy() {
        return (ctx, cause) -> CompletableFuture.completedFuture(cause == null);
    }
}
