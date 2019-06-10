/*
 * Copyright 2016 LINE Corporation
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.function.Function;

import org.junit.Test;

import com.google.common.testing.FakeTicker;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.testing.internal.AnticipatedException;

public class CircuitBreakerRpcClientTest {

    private static final String remoteServiceName = "testService";

    // Remote invocation parameters
    private static final RpcRequest reqA = RpcRequest.of(Object.class, "methodA", "a", "b");
    private static final ClientRequestContext ctxA = ClientRequestContext.of(reqA, "h2c://dummyhost:8080/");

    private static final RpcRequest reqB = RpcRequest.of(Object.class, "methodB", "c", "d");
    private static final ClientRequestContext ctxB = ClientRequestContext.of(reqB, "h2c://dummyhost:8080/");

    private static final RpcResponse successRes = RpcResponse.of(null);
    private static final RpcResponse failureRes = RpcResponse.ofFailure(
            Exceptions.clearTrace(new AnticipatedException()));

    private static final Duration circuitOpenWindow = Duration.ofSeconds(60);
    private static final Duration counterSlidingWindow = Duration.ofSeconds(180);
    private static final Duration counterUpdateInterval = Duration.ofMillis(1);
    private static final int minimumRequestThreshold = 2;

    @Test
    public void testSingletonDecorator() {
        final CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);
        when(circuitBreaker.canRequest()).thenReturn(false);

        final int COUNT = 1;
        failFastInvocation(CircuitBreakerRpcClient.newDecorator(circuitBreaker, strategy()), COUNT);
        verify(circuitBreaker, times(COUNT)).canRequest();
    }

    @Test
    public void testPerMethodDecorator() {
        final CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);
        when(circuitBreaker.canRequest()).thenReturn(false);

        @SuppressWarnings("unchecked")
        final Function<String, CircuitBreaker> factory = mock(Function.class);
        when(factory.apply(any())).thenReturn(circuitBreaker);

        final int COUNT = 2;
        failFastInvocation(CircuitBreakerRpcClient.newPerMethodDecorator(factory, strategy()), COUNT);

        verify(circuitBreaker, times(COUNT)).canRequest();
        verify(factory, times(1)).apply("methodA");
    }

    @Test
    public void testPerHostDecorator() {
        final CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);
        when(circuitBreaker.canRequest()).thenReturn(false);

        @SuppressWarnings("unchecked")
        final Function<String, CircuitBreaker> factory = mock(Function.class);
        when(factory.apply(any())).thenReturn(circuitBreaker);

        final int COUNT = 2;
        failFastInvocation(CircuitBreakerRpcClient.newPerHostDecorator(factory, strategy()), COUNT);

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
        failFastInvocation(CircuitBreakerRpcClient.newPerHostAndMethodDecorator(factory, strategy()), COUNT);

        verify(circuitBreaker, times(COUNT)).canRequest();
        verify(factory, times(1)).apply("dummyhost:8080#methodA");
    }

    @Test
    public void testDelegate() throws Exception {
        final FakeTicker ticker = new FakeTicker();
        final CircuitBreaker circuitBreaker = new CircuitBreakerBuilder(remoteServiceName).ticker(ticker)
                                                                                          .build();

        @SuppressWarnings("unchecked")
        final Client<RpcRequest, RpcResponse> delegate = mock(Client.class);
        when(delegate.execute(any(), any())).thenReturn(successRes);

        final CircuitBreakerRpcClient stub =
                new CircuitBreakerRpcClient(delegate, (ctx, req) -> circuitBreaker, strategy());

        stub.execute(ctxA, reqA);

        verify(delegate, times(1)).execute(eq(ctxA), eq(reqA));
    }

    @Test
    public void testDelegateIfFailToGetCircuitBreaker() throws Exception {
        @SuppressWarnings("unchecked")
        final Client<RpcRequest, RpcResponse> delegate = mock(Client.class);
        when(delegate.execute(any(), any())).thenReturn(successRes);

        final CircuitBreakerMapping mapping = (ctx, req) -> {
            throw Exceptions.clearTrace(new AnticipatedException("bug!"));
        };
        final CircuitBreakerRpcClient stub = new CircuitBreakerRpcClient(delegate, mapping, strategy());

        stub.execute(ctxA, reqA);

        // make sure that remote service is invoked even if cb mapping is failed
        verify(delegate, times(1)).execute(eq(ctxA), eq(reqA));
    }

    @Test
    public void testStateTransition() throws Exception {
        final FakeTicker ticker = new FakeTicker();
        final CircuitBreaker circuitBreaker = buildCircuitBreaker(ticker);

        @SuppressWarnings("unchecked")
        final Client<RpcRequest, RpcResponse> delegate = mock(Client.class);
        // return failed future
        when(delegate.execute(ctxA, reqA)).thenReturn(failureRes);

        final CircuitBreakerRpcClient stub =
                new CircuitBreakerRpcClient(delegate, (ctx, req) -> circuitBreaker, strategy());

        // CLOSED
        for (int i = 0; i < minimumRequestThreshold + 1; i++) {
            // Need to call execute() one more to change the state of the circuit breaker.

            assertThat(stub.execute(ctxA, reqA).cause()).isInstanceOf(AnticipatedException.class);
            ticker.advance(Duration.ofMillis(1).toNanos());
        }

        // OPEN
        assertThatThrownBy(() -> stub.execute(ctxA, reqA)).isInstanceOf(FailFastException.class);

        ticker.advance(circuitOpenWindow.toNanos());

        // return success future
        when(delegate.execute(ctxA, reqA)).thenReturn(successRes);

        // HALF OPEN
        assertThat(stub.execute(ctxA, reqA).join()).isNull();

        // CLOSED
        assertThat(stub.execute(ctxA, reqA).join()).isNull();
    }

    @Test
    public void testServiceScope() throws Exception {
        final FakeTicker ticker = new FakeTicker();
        final CircuitBreaker circuitBreaker = buildCircuitBreaker(ticker);

        @SuppressWarnings("unchecked")
        final Client<RpcRequest, RpcResponse> delegate = mock(Client.class);
        // Always return failed future for methodA
        when(delegate.execute(ctxA, reqA)).thenReturn(failureRes);
        // Always return success future for methodB
        when(delegate.execute(ctxB, reqB)).thenReturn(successRes);

        final CircuitBreakerRpcClient stub =
                new CircuitBreakerRpcClient(delegate, (ctx, req) -> circuitBreaker, strategy());

        // CLOSED
        for (int i = 0; i < minimumRequestThreshold + 1; i++) {
            // Need to call execute() one more to change the state of the circuit breaker.

            assertThatThrownBy(() -> stub.execute(ctxA, reqA).join())
                    .hasCauseInstanceOf(AnticipatedException.class);
            ticker.advance(Duration.ofMillis(1).toNanos());
        }

        // OPEN (methodA)
        assertThatThrownBy(() -> stub.execute(ctxA, reqA)).isInstanceOf(FailFastException.class);

        // OPEN (methodB)
        assertThatThrownBy(() -> stub.execute(ctxB, reqB)).isInstanceOf(FailFastException.class);
    }

    @Test
    public void testPerMethodScope() throws Exception {
        final FakeTicker ticker = new FakeTicker();
        final Function<String, CircuitBreaker> factory = method -> buildCircuitBreaker(ticker);

        @SuppressWarnings("unchecked")
        final Client<RpcRequest, RpcResponse> delegate = mock(Client.class);
        // Always return failed future for methodA
        when(delegate.execute(ctxA, reqA)).thenReturn(failureRes);
        // Always return success future for methodB
        when(delegate.execute(ctxB, reqB)).thenReturn(successRes);

        final CircuitBreakerRpcClient stub =
                CircuitBreakerRpcClient.newPerMethodDecorator(factory, strategy()).apply(delegate);

        // CLOSED (methodA)
        for (int i = 0; i < minimumRequestThreshold + 1; i++) {
            // Need to call execute() one more to change the state of the circuit breaker.

            assertThatThrownBy(() -> stub.execute(ctxA, reqA).join())
                    .hasCauseInstanceOf(AnticipatedException.class);
            ticker.advance(Duration.ofMillis(1).toNanos());
        }

        // OPEN (methodA)
        assertThatThrownBy(() -> stub.execute(ctxA, reqA)).isInstanceOf(FailFastException.class);

        // CLOSED (methodB)
        assertThat(stub.execute(ctxB, reqB).join()).isNull();
    }

    private static CircuitBreaker buildCircuitBreaker(FakeTicker ticker) {
        return new CircuitBreakerBuilder(remoteServiceName)
                .minimumRequestThreshold(minimumRequestThreshold)
                .circuitOpenWindow(circuitOpenWindow)
                .counterSlidingWindow(counterSlidingWindow)
                .counterUpdateInterval(counterUpdateInterval)
                .ticker(ticker)
                .build();
    }

    private static void failFastInvocation(
            Function<Client<RpcRequest, RpcResponse>, CircuitBreakerRpcClient> decorator,
            int count) {

        for (int i = 0; i < count; i++) {
            assertThatThrownBy(() -> invoke(decorator)).isInstanceOf(FailFastException.class);
        }
    }

    private static void invoke(
            Function<Client<RpcRequest, RpcResponse>, CircuitBreakerRpcClient> decorator) throws Exception {

        @SuppressWarnings("unchecked")
        final Client<RpcRequest, RpcResponse> client = mock(Client.class);
        final Client<RpcRequest, RpcResponse> decorated = decorator.apply(client);

        decorated.execute(ctxA, reqA);
    }

    /**
     * Returns a {@link CircuitBreakerStrategy} which returns {@code true} when there's
     * no {@link Exception} raised.
     */
    private static CircuitBreakerStrategyWithContent<RpcResponse> strategy() {
        return (ctx, response) -> response.handle((unused, cause) -> cause == null);
    }
}
