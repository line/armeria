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

import static com.linecorp.armeria.common.SessionProtocol.H2C;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.function.Function;

import org.junit.Test;

import com.google.common.testing.FakeTicker;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.DefaultClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.metric.NoopMeterRegistry;

import io.netty.channel.DefaultEventLoop;

public class CircuitBreakerHttpClientTest {

    private static final String remoteServiceName = "testService";

    private static final ClientRequestContext ctx = new DefaultClientRequestContext(
            new DefaultEventLoop(), NoopMeterRegistry.get(), H2C,
            Endpoint.of("dummyhost", 8080),
            HttpMethod.GET, "/", null, null, ClientOptions.DEFAULT, mock(HttpRequest.class));

    @Test
    public void testPerMethodDecorator() {
        final CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);
        when(circuitBreaker.canRequest()).thenReturn(false);

        @SuppressWarnings("unchecked")
        final Function<String, CircuitBreaker> factory = mock(Function.class);
        when(factory.apply(any())).thenReturn(circuitBreaker);

        final int COUNT = 2;
        failFastInvocation(CircuitBreakerHttpClient.newPerMethodDecorator(factory, strategy()),
                           ctx.method(), COUNT);

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
        failFastInvocation(CircuitBreakerHttpClient.newPerHostDecorator(factory, strategy()),
                           ctx.method(), COUNT);

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
        failFastInvocation(CircuitBreakerHttpClient.newPerHostAndMethodDecorator(factory, strategy()),
                           ctx.method(), COUNT);

        verify(circuitBreaker, times(COUNT)).canRequest();
        verify(factory, times(1)).apply("dummyhost:8080#GET");
    }

    @Test
    public void circuitBreakerIsOpenOnServerError() throws Exception {
        final FakeTicker ticker = new FakeTicker();
        final int minimumRequestThreshold = 2;
        final Duration circuitOpenWindow = Duration.ofSeconds(60);
        final Duration counterSlidingWindow = Duration.ofSeconds(180);
        final Duration counterUpdateInterval = Duration.ofMillis(1);

        final CircuitBreaker circuitBreaker = new CircuitBreakerBuilder(remoteServiceName)
                .minimumRequestThreshold(minimumRequestThreshold)
                .circuitOpenWindow(circuitOpenWindow)
                .counterSlidingWindow(counterSlidingWindow)
                .counterUpdateInterval(counterUpdateInterval)
                .ticker(ticker)
                .build();

        @SuppressWarnings("unchecked")
        final Client<HttpRequest, HttpResponse> delegate = mock(Client.class);
        when(delegate.execute(any(), any())).thenReturn(HttpResponse.of(503))
                                            .thenReturn(HttpResponse.of(503))
                                            .thenReturn(HttpResponse.of(503))
                                            .thenReturn(HttpResponse.of(503));

        final CircuitBreakerMapping mapping = (ctx, req) -> circuitBreaker;
        final CircuitBreakerHttpClient stub = new CircuitBreakerHttpClient(
                delegate, mapping, CircuitBreakerStrategy.onServerErrorStatus());

        // CLOSED
        for (int i = 0; i < minimumRequestThreshold + 1; i++) {
            // Need to call execute() one more to change the state of the circuit breaker.

            assertThat(stub.execute(ctx, mock(HttpRequest.class)).aggregate().join().headers().status())
                    .isSameAs(HttpStatus.SERVICE_UNAVAILABLE);
            ticker.advance(Duration.ofMillis(1).toNanos());
        }

        // OPEN
        assertThatThrownBy(() -> stub.execute(ctx, mock(HttpRequest.class)))
                .isInstanceOf(FailFastException.class);
    }

    private static void failFastInvocation(
            Function<Client<HttpRequest, HttpResponse>, CircuitBreakerHttpClient> decorator,
            HttpMethod method, int count) {

        for (int i = 0; i < count; i++) {
            assertThatThrownBy(() -> invoke(decorator, HttpRequest.of(method, "/")))
                    .isInstanceOf(FailFastException.class);
        }
    }

    private static void invoke(
            Function<Client<HttpRequest, HttpResponse>, CircuitBreakerHttpClient> decorator,
            HttpRequest req) throws Exception {

        @SuppressWarnings("unchecked")
        final Client<HttpRequest, HttpResponse> client = mock(Client.class);
        final Client<HttpRequest, HttpResponse> decorated = decorator.apply(client);

        decorated.execute(ctx, req);
    }

    /**
     * Returns a {@link CircuitBreakerStrategy} which returns {@code true} when there's
     * no {@link Exception} raised.
     */
    private static CircuitBreakerStrategy<HttpResponse> strategy() {
        return response -> response.aggregate().handle((res, cause) -> cause == null);
    }
}
