/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.client.circuitbreaker;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
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
import com.linecorp.armeria.client.circuitbreaker.KeyedCircuitBreakerMapping.KeySelector;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.thrift.ThriftCall;
import com.linecorp.armeria.common.thrift.ThriftReply;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.service.test.thrift.main.HelloService;

import io.netty.channel.DefaultEventLoop;

public class CircuitBreakerClientTest {

    private static final String remoteServiceName = "testService";

    // Remote invocation parameters
    private static final ClientRequestContext ctx = new DefaultClientRequestContext(
            new DefaultEventLoop(), SessionProtocol.H2C,
            Endpoint.of("dummyhost", 8080),
            "POST", "/", ClientOptions.DEFAULT,
            new ThriftCall(0, HelloService.Iface.class, "methodA", "a", "b"));

    private static final ClientRequestContext ctxB = new DefaultClientRequestContext(
            new DefaultEventLoop(), SessionProtocol.H2C,
            Endpoint.of("dummyhost", 8080),
            "POST", "/", ClientOptions.DEFAULT,
            new ThriftCall(0, HelloService.Iface.class, "methodB", "c", "d"));

    private static final ThriftCall req = ctx.request();
    private static final ThriftCall reqB = ctxB.request();
    private static final ThriftReply successRes = new ThriftReply(0, (Object) null);
    private static final ThriftReply failureRes = new ThriftReply(0, Exceptions.clearTrace(new Exception("bug")));

    @Test
    public void testSingletonDecorator() throws Exception {
        CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);
        final int COUNT = 1;
        failFastInvocation(circuitBreaker, CircuitBreakerClient.newDecorator(circuitBreaker), COUNT);
        verify(circuitBreaker, times(COUNT)).canRequest();
    }

    @Test
    public void testPerMethodDecorator() throws Exception {
        CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);
        @SuppressWarnings("unchecked")
        Function<String, CircuitBreaker> factory = (Function<String, CircuitBreaker>) mock(Function.class);
        when(factory.apply(any())).thenReturn(circuitBreaker);

        final int COUNT = 2;
        failFastInvocation(circuitBreaker, CircuitBreakerClient.newPerMethodDecorator(factory), COUNT);

        verify(circuitBreaker, times(COUNT)).canRequest();
        verify(factory, times(1)).apply("methodA");
    }

    @Test
    public void testPerHostDecorator() throws Exception {
        CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);
        @SuppressWarnings("unchecked")
        Function<String, CircuitBreaker> factory = (Function<String, CircuitBreaker>) mock(Function.class);
        when(factory.apply(any())).thenReturn(circuitBreaker);

        final int COUNT = 2;
        failFastInvocation(circuitBreaker, CircuitBreakerClient.newPerHostDecorator(factory), COUNT);

        verify(circuitBreaker, times(COUNT)).canRequest();
        verify(factory, times(1)).apply("dummyhost:8080");
    }

    @Test
    public void testPerHostAndMethodDecorator() throws Exception {
        CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);

        @SuppressWarnings("unchecked")
        Function<String, CircuitBreaker> factory = (Function<String, CircuitBreaker>) mock(Function.class);
        when(factory.apply(any())).thenReturn(circuitBreaker);

        final int COUNT = 2;
        failFastInvocation(circuitBreaker, CircuitBreakerClient.newPerHostAndMethodDecorator(factory), COUNT);

        verify(circuitBreaker, times(COUNT)).canRequest();
        verify(factory, times(1)).apply("dummyhost:8080#methodA");
    }

    @Test
    public void testDelegate() throws Exception {
        FakeTicker ticker = new FakeTicker();
        CircuitBreaker circuitBreaker = new CircuitBreakerBuilder(remoteServiceName)
                .ticker(ticker)
                .build();

        @SuppressWarnings("unchecked")
        Client<ThriftCall, ThriftReply> delegate = mock(Client.class);
        when(delegate.execute(any(), any())).thenReturn(successRes);

        CircuitBreakerMapping mapping = (ctx, req) -> circuitBreaker;
        CircuitBreakerClient<ThriftCall, ThriftReply> stub = new CircuitBreakerClient<>(delegate, mapping);

        stub.execute(ctx, req);

        verify(delegate, times(1)).execute(eq(ctx), eq(req));
    }

    @Test
    public void testDelegateIfFailToGetCircuitBreaker() throws Exception {
        @SuppressWarnings("unchecked")
        Client<ThriftCall, ThriftReply> delegate = mock(Client.class);
        when(delegate.execute(any(), any())).thenReturn(successRes);

        CircuitBreakerMapping mapping = (ctx, req) -> {
            throw Exceptions.clearTrace(new IllegalArgumentException("bug!"));
        };
        CircuitBreakerClient<ThriftCall, ThriftReply> stub = new CircuitBreakerClient<>(delegate, mapping);

        stub.execute(ctx, req);

        // make sure that remote service is invoked even if cb mapping is failed
        verify(delegate, times(1)).execute(eq(ctx), eq(req));
    }

    @Test
    public void testStateTransition() throws Exception {
        FakeTicker ticker = new FakeTicker();
        int minimumRequestThreshold = 2;
        Duration circuitOpenWindow = Duration.ofSeconds(60);
        Duration counterSlidingWindow = Duration.ofSeconds(180);
        Duration counterUpdateInterval = Duration.ofMillis(1);

        CircuitBreaker circuitBreaker = new CircuitBreakerBuilder(remoteServiceName)
                .minimumRequestThreshold(minimumRequestThreshold)
                .circuitOpenWindow(circuitOpenWindow)
                .counterSlidingWindow(counterSlidingWindow)
                .counterUpdateInterval(counterUpdateInterval)
                .ticker(ticker)
                .build();

        @SuppressWarnings("unchecked")
        Client<ThriftCall, ThriftReply> delegate = mock(Client.class);
        // return failed future
        when(delegate.execute(ctx, req)).thenReturn(failureRes);

        CircuitBreakerMapping mapping = (ctx, req) -> circuitBreaker;
        CircuitBreakerClient<ThriftCall, ThriftReply> stub = new CircuitBreakerClient<>(delegate, mapping);

        // CLOSED
        for (int i = 0; i < minimumRequestThreshold + 1; i++) {
            ThriftReply future = stub.execute(ctx, req);
            // The future is `failureRes` itself
            assertThat(future.isCompletedExceptionally(), is(true));
            // This is not a CircuitBreakerException
            assertThat(future.getCause(), is(not(instanceOf(FailFastException.class))));
            ticker.advance(Duration.ofMillis(1).toNanos());
        }

        // OPEN
        try {
            stub.execute(ctx, req);
            fail();
        } catch (FailFastException e) {
            // The circuit is OPEN
            assertThat(e.getCircuitBreaker(), is(circuitBreaker));
        }

        ticker.advance(circuitOpenWindow.toNanos());

        // return success future
        when(delegate.execute(ctx, req)).thenReturn(successRes);

        // HALF OPEN
        ThriftReply future2 = stub.execute(ctx, req);
        assertThat(future2.get(), is(nullValue()));

        // CLOSED
        ThriftReply future3 = stub.execute(ctx, req);
        assertThat(future3.get(), is(nullValue()));
    }

    @Test
    public void testServiceScope() throws Exception {
        FakeTicker ticker = new FakeTicker();
        int minimumRequestThreshold = 2;
        Duration circuitOpenWindow = Duration.ofSeconds(60);
        Duration counterSlidingWindow = Duration.ofSeconds(180);
        Duration counterUpdateInterval = Duration.ofMillis(1);

        CircuitBreaker circuitBreaker = new CircuitBreakerBuilder(remoteServiceName)
                .minimumRequestThreshold(minimumRequestThreshold)
                .circuitOpenWindow(circuitOpenWindow)
                .counterSlidingWindow(counterSlidingWindow)
                .counterUpdateInterval(counterUpdateInterval)
                .ticker(ticker)
                .build();

        @SuppressWarnings("unchecked")
        Client<ThriftCall, ThriftReply> delegate = mock(Client.class);
        // Always return failed future for methodA
        when(delegate.execute(ctx, req)).thenReturn(failureRes);
        // Always return success future for methodB
        when(delegate.execute(ctxB, reqB)).thenReturn(successRes);

        CircuitBreakerMapping mapping = (ctx, req) -> circuitBreaker;
        CircuitBreakerClient<ThriftCall, ThriftReply> stub = new CircuitBreakerClient<>(delegate, mapping);

        // CLOSED
        for (int i = 0; i < minimumRequestThreshold + 1; i++) {
            stub.execute(ctx, req);
            ticker.advance(Duration.ofMillis(1).toNanos());
        }

        // OPEN (methodA)
        try {
            stub.execute(ctx, req);
            fail();
        } catch (FailFastException e) {
            // Expected
        }

        // OPEN (methodB)
        try {
            stub.execute(ctxB, reqB);
            fail();
        } catch (FailFastException e) {
            // Expected
        }
    }

    @Test
    public void testPerMethodScope() throws Exception {
        FakeTicker ticker = new FakeTicker();
        int minimumRequestThreshold = 2;
        Duration circuitOpenWindow = Duration.ofSeconds(60);
        Duration counterSlidingWindow = Duration.ofSeconds(180);
        Duration counterUpdateInterval = Duration.ofMillis(1);

        Function<String, CircuitBreaker> factory = method ->
                new CircuitBreakerBuilder(remoteServiceName)
                        .minimumRequestThreshold(minimumRequestThreshold)
                        .circuitOpenWindow(circuitOpenWindow)
                        .counterSlidingWindow(counterSlidingWindow)
                        .counterUpdateInterval(counterUpdateInterval)
                        .ticker(ticker)
                        .build();

        @SuppressWarnings("unchecked")
        Client<ThriftCall, ThriftReply> delegate = mock(Client.class);
        // Always return failed future for methodA
        when(delegate.execute(ctx, req)).thenReturn(failureRes);
        // Always return success future for methodB
        when(delegate.execute(ctxB, reqB)).thenReturn(successRes);

        CircuitBreakerMapping mapping = new KeyedCircuitBreakerMapping<>(KeySelector.METHOD, factory);
        CircuitBreakerClient<ThriftCall, ThriftReply> stub = new CircuitBreakerClient<>(delegate, mapping);

        // CLOSED (methodA)
        for (int i = 0; i < minimumRequestThreshold + 1; i++) {
            try {
                stub.execute(ctx, req);
                assertThat(i, is(lessThanOrEqualTo(minimumRequestThreshold)));
            } catch (FailFastException e) {
                assertThat(i, is(greaterThan(minimumRequestThreshold)));
            }
            ticker.advance(Duration.ofMillis(1).toNanos());
        }

        // OPEN (methodA)
        try {
            stub.execute(ctx, req);
            fail();
        } catch (FailFastException e) {
            // Expected
        }

        // CLOSED (methodB)
        ThriftReply future2 = stub.execute(ctxB, reqB);
        assertThat(future2.get(), is(nullValue()));
    }

    @Test
    public void testExceptionFilter() throws Exception {
        FakeTicker ticker = new FakeTicker();
        int minimumRequestThreshold = 2;
        Duration circuitOpenWindow = Duration.ofSeconds(60);
        Duration counterSlidingWindow = Duration.ofSeconds(180);
        Duration counterUpdateInterval = Duration.ofMillis(1);

        // a filter that ignores all exception
        ExceptionFilter exceptionFilter = cause -> false;

        CircuitBreaker circuitBreaker = new CircuitBreakerBuilder(remoteServiceName)
                .minimumRequestThreshold(minimumRequestThreshold)
                .circuitOpenWindow(circuitOpenWindow)
                .counterSlidingWindow(counterSlidingWindow)
                .counterUpdateInterval(counterUpdateInterval)
                .exceptionFilter(exceptionFilter)
                .ticker(ticker)
                .build();

        @SuppressWarnings("unchecked")
        Client<ThriftCall, ThriftReply> delegate = mock(Client.class);
        // return failed future
        when(delegate.execute(ctx, req)).thenReturn(failureRes);

        CircuitBreakerMapping mapping = (ctx, req) -> circuitBreaker;
        CircuitBreakerClient<ThriftCall, ThriftReply> stub = new CircuitBreakerClient<>(delegate, mapping);

        // CLOSED
        for (int i = 0; i < minimumRequestThreshold + 1; i++) {
            ThriftReply future = stub.execute(ctx, req);
            // The future is `failedFuture` itself
            assertThat(future.isCompletedExceptionally(), is(true));
            // This is not a CircuitBreakerException
            assertThat(future.getCause(), is(not(instanceOf(FailFastException.class))));
            ticker.advance(Duration.ofMillis(1).toNanos());
        }

        // OPEN
        ThriftReply future1 = stub.execute(ctx, req);
        // The circuit is still CLOSED
        assertThat(future1.isCompletedExceptionally(), is(true));
        assertThat(future1.getCause(), is(not(instanceOf(FailFastException.class))));
    }

    private static void invoke(Function<Client<? super ThriftCall, ? extends ThriftReply>,
                                        ? extends Client<ThriftCall, ThriftReply>> decorator) throws Exception {

        @SuppressWarnings("unchecked")
        Client<ThriftCall, ThriftReply> client = mock(Client.class);
        Client<ThriftCall, ThriftReply> decorated = decorator.apply(client);

        decorated.execute(ctx, req);
    }

    private static void failFastInvocation(
            CircuitBreaker circuitBreaker,
            Function<Client<? super ThriftCall, ? extends ThriftReply>,
                     ? extends Client<ThriftCall, ThriftReply>> decorator, int count) throws Exception {

        for (int i = 0; i < count; i++) {
            try {
                invoke(decorator);
                fail();
            } catch (FailFastException e) {
                assertThat(e.getCircuitBreaker(), is(circuitBreaker));
            }
        }
    }
}
