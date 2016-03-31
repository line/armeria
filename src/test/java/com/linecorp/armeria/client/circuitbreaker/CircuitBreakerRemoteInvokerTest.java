/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.circuitbreaker;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.net.URI;
import java.time.Duration;
import java.util.function.Function;

import org.junit.Test;

import com.google.common.testing.FakeTicker;

import com.linecorp.armeria.client.ClientCodec;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.RemoteInvoker;
import com.linecorp.armeria.client.circuitbreaker.KeyedCircuitBreakerMapping.KeySelector;

import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

public class CircuitBreakerRemoteInvokerTest {

    private static String remoteServiceName = "testservice";

    // Remote invocation parameters

    private static EventLoop eventLoop = new DefaultEventLoop();

    private static ClientOptions options = ClientOptions.of();

    private static ClientCodec codec = mock(ClientCodec.class);

    private static Object[] args = { "a", "b" };

    private static URI uri = URI.create("http://xxx");

    private static class EmptyService {
        public void methodA() {}

        public void methodB() {}
    }

    private static Method methodA() throws NoSuchMethodException {
        return EmptyService.class.getMethod("methodA");
    }

    private static Method methodB() throws NoSuchMethodException {
        return EmptyService.class.getMethod("methodB");
    }

    // Mock Futures

    @SuppressWarnings("unchecked")
    private static <T> Future<T> mockFuture() {
        Future<T> future = (Future<T>) mock(Future.class);
        when(future.addListener(any())).then(invoc -> {
            GenericFutureListener<Future<T>> listener = invoc.getArgumentAt(0, GenericFutureListener.class);
            listener.operationComplete(future);
            return future;
        });
        return future;
    }

    @SuppressWarnings("unchecked")
    private static <T> Future<T> successFuture() {
        Future<T> future = mockFuture();
        when(future.isSuccess()).thenReturn(true);
        return future;
    }

    @SuppressWarnings("unchecked")
    private static <T> Future<T> failedFuture() {
        Future<T> future = mockFuture();
        when(future.isSuccess()).thenReturn(false);
        when(future.cause()).thenReturn(new Exception());
        return future;
    }

    // Tests

    @Test
    public void testDelegateRemoteInvocation() throws Exception {
        FakeTicker ticker = new FakeTicker();
        Future<Object> successFuture = successFuture();

        CircuitBreaker circuitBreaker = new CircuitBreakerBuilder(remoteServiceName)
                .ticker(ticker)
                .build();

        RemoteInvoker remoteInvoker = mock(RemoteInvoker.class);
        when(remoteInvoker.invoke(any(), any(), any(), any(), any(), any())).thenReturn(successFuture);

        CircuitBreakerMapping mapping = (eventLoop1, uri, options1, codec1, method, args1) -> circuitBreaker;
        CircuitBreakerRemoteInvoker stub = new CircuitBreakerRemoteInvoker(remoteInvoker, mapping);

        stub.invoke(eventLoop, uri, options, codec, methodA(), args);

        verify(remoteInvoker, times(1))
                .invoke(eq(eventLoop), eq(uri), eq(options), eq(codec), eq(methodA()), eq(args));
    }

    @Test
    public void testDelegateRemoteInvocationIfFailToGetCircuitBreaker() throws Exception {
        Future<Object> successFuture = successFuture();

        RemoteInvoker remoteInvoker = mock(RemoteInvoker.class);
        when(remoteInvoker.invoke(any(), any(), any(), any(), any(), any())).thenReturn(successFuture);

        CircuitBreakerMapping mapping = (eventLoop1, uri, options1, codec1, method, args1) -> {
            throw new IllegalArgumentException();
        };
        CircuitBreakerRemoteInvoker stub = new CircuitBreakerRemoteInvoker(remoteInvoker, mapping);

        stub.invoke(eventLoop, uri, options, codec, methodA(), args);

        // make sure that remote service is invoked even if cb mapping is failed
        verify(remoteInvoker, times(1))
                .invoke(eq(eventLoop), eq(uri), eq(options), eq(codec), eq(methodA()), eq(args));
    }

    @Test
    public void testStateTransition() throws Exception {
        FakeTicker ticker = new FakeTicker();
        int minimumRequestThreshold = 2;
        Duration circuitOpenWindow = Duration.ofSeconds(60);
        Duration counterSlidingWindow = Duration.ofSeconds(180);
        Duration counterUpdateInterval = Duration.ofMillis(1);
        Future<Object> successFuture = successFuture();
        Future<Object> failedFuture = failedFuture();

        CircuitBreaker circuitBreaker = new CircuitBreakerBuilder(remoteServiceName)
                .minimumRequestThreshold(minimumRequestThreshold)
                .circuitOpenWindow(circuitOpenWindow)
                .counterSlidingWindow(counterSlidingWindow)
                .counterUpdateInterval(counterUpdateInterval)
                .ticker(ticker)
                .build();

        RemoteInvoker remoteInvoker = mock(RemoteInvoker.class);
        // return failed future
        when(remoteInvoker.invoke(any(), any(), any(), any(), any(), any())).thenReturn(failedFuture);

        CircuitBreakerMapping mapping = (eventLoop1, uri, options1, codec1, method, args1) -> circuitBreaker;
        CircuitBreakerRemoteInvoker stub = new CircuitBreakerRemoteInvoker(remoteInvoker, mapping);

        // CLOSED
        for (int i = 0; i < minimumRequestThreshold + 1; i++) {
            Future<Object> future = stub.invoke(eventLoop, uri, options, codec, methodA(), args);
            // The future is `failedFuture` itself
            assertThat(future.isSuccess(), is(false));
            // This is not a CircuitBreakerException
            assertThat(future.cause(), is(not(instanceOf(FailFastException.class))));
            ticker.advance(Duration.ofMillis(1).toNanos());
        }

        // OPEN
        Future<Object> future1 = stub.invoke(eventLoop, uri, options, codec, methodA(), args);
        // The circuit is OPEN
        assertThat(future1.isSuccess(), is(false));
        assertThat(future1.cause(), instanceOf(FailFastException.class));
        assertThat(((FailFastException) future1.cause()).getCircuitBreaker(), is(circuitBreaker));

        ticker.advance(circuitOpenWindow.toNanos());

        // return success future
        when(remoteInvoker.invoke(any(), any(), any(), any(), any(), any())).thenReturn(successFuture);

        // HALF OPEN
        Future<Object> future2 = stub.invoke(eventLoop, uri, options, codec, methodA(), args);
        assertThat(future2.isSuccess(), is(true));

        // CLOSED
        Future<Object> future3 = stub.invoke(eventLoop, uri, options, codec, methodA(), args);
        assertThat(future3.isSuccess(), is(true));
    }

    @Test
    public void testServiceScope() throws Exception {
        FakeTicker ticker = new FakeTicker();
        int minimumRequestThreshold = 2;
        Duration circuitOpenWindow = Duration.ofSeconds(60);
        Duration counterSlidingWindow = Duration.ofSeconds(180);
        Duration counterUpdateInterval = Duration.ofMillis(1);
        Future<Object> successFuture = successFuture();
        Future<Object> failedFuture = failedFuture();

        CircuitBreaker circuitBreaker = new CircuitBreakerBuilder(remoteServiceName)
                .minimumRequestThreshold(minimumRequestThreshold)
                .circuitOpenWindow(circuitOpenWindow)
                .counterSlidingWindow(counterSlidingWindow)
                .counterUpdateInterval(counterUpdateInterval)
                .ticker(ticker)
                .build();

        RemoteInvoker remoteInvoker = mock(RemoteInvoker.class);
        // Always return failed future for methodA
        when(remoteInvoker.invoke(any(), any(), any(), any(), eq(methodA()), any())).thenReturn(failedFuture);
        // Always return success future for methodB
        when(remoteInvoker.invoke(any(), any(), any(), any(), eq(methodB()), any())).thenReturn(successFuture);

        CircuitBreakerMapping mapping = (eventLoop1, uri, options1, codec1, method, args1) -> circuitBreaker;
        CircuitBreakerRemoteInvoker stub = new CircuitBreakerRemoteInvoker(remoteInvoker, mapping);

        // CLOSED
        for (int i = 0; i < minimumRequestThreshold + 1; i++) {
            stub.invoke(eventLoop, uri, options, codec, methodA(), args);
            ticker.advance(Duration.ofMillis(1).toNanos());
        }

        // OPEN (methodA)
        Future<Object> future1 = stub.invoke(eventLoop, uri, options, codec, methodA(), args);
        assertThat(future1.isSuccess(), is(false));
        assertThat(future1.cause(), instanceOf(FailFastException.class));

        // OPEN (methodB)
        Future<Object> future2 = stub.invoke(eventLoop, uri, options, codec, methodB(), args);
        assertThat(future2.isSuccess(), is(false));
        assertThat(future2.cause(), instanceOf(FailFastException.class));
    }

    @Test
    public void testPerMethodScope() throws Exception {
        FakeTicker ticker = new FakeTicker();
        int minimumRequestThreshold = 2;
        Duration circuitOpenWindow = Duration.ofSeconds(60);
        Duration counterSlidingWindow = Duration.ofSeconds(180);
        Duration counterUpdateInterval = Duration.ofMillis(1);
        Future<Object> successFuture = successFuture();
        Future<Object> failedFuture = failedFuture();

        Function<String, CircuitBreaker> factory = method ->
                new CircuitBreakerBuilder(remoteServiceName)
                        .minimumRequestThreshold(minimumRequestThreshold)
                        .circuitOpenWindow(circuitOpenWindow)
                        .counterSlidingWindow(counterSlidingWindow)
                        .counterUpdateInterval(counterUpdateInterval)
                        .ticker(ticker)
                        .build();

        RemoteInvoker remoteInvoker = mock(RemoteInvoker.class);
        // Always return failed future for methodA
        when(remoteInvoker.invoke(any(), any(), any(), any(), eq(methodA()), any())).thenReturn(failedFuture);
        // Always return success future for methodB
        when(remoteInvoker.invoke(any(), any(), any(), any(), eq(methodB()), any())).thenReturn(successFuture);

        CircuitBreakerMapping mapping = new KeyedCircuitBreakerMapping<>(KeySelector.METHOD, factory::apply);
        CircuitBreakerRemoteInvoker stub = new CircuitBreakerRemoteInvoker(remoteInvoker, mapping);

        // CLOSED (methodA)
        for (int i = 0; i < minimumRequestThreshold + 1; i++) {
            stub.invoke(eventLoop, uri, options, codec, methodA(), args);
            ticker.advance(Duration.ofMillis(1).toNanos());
        }

        // OPEN (methodA)
        Future<Object> future1 = stub.invoke(eventLoop, uri, options, codec, methodA(), args);
        assertThat(future1.isSuccess(), is(false));
        assertThat(future1.cause(), instanceOf(FailFastException.class));

        // CLOSED (methodB)
        Future<Object> future2 = stub.invoke(eventLoop, uri, options, codec, methodB(), args);
        assertThat(future2.isSuccess(), is(true));
    }

    @Test
    public void testExceptionFilter() throws Exception {
        FakeTicker ticker = new FakeTicker();
        int minimumRequestThreshold = 2;
        Duration circuitOpenWindow = Duration.ofSeconds(60);
        Duration counterSlidingWindow = Duration.ofSeconds(180);
        Duration counterUpdateInterval = Duration.ofMillis(1);
        Future<Object> failedFuture = failedFuture();

        // a filter that ignores all exception
        ExceptionFilter exceptionFilter = (cause) -> false;

        CircuitBreaker circuitBreaker = new CircuitBreakerBuilder(remoteServiceName)
                .minimumRequestThreshold(minimumRequestThreshold)
                .circuitOpenWindow(circuitOpenWindow)
                .counterSlidingWindow(counterSlidingWindow)
                .counterUpdateInterval(counterUpdateInterval)
                .exceptionFilter(exceptionFilter)
                .ticker(ticker)
                .build();

        RemoteInvoker remoteInvoker = mock(RemoteInvoker.class);
        // return failed future
        when(remoteInvoker.invoke(any(), any(), any(), any(), any(), any())).thenReturn(failedFuture);

        CircuitBreakerMapping mapping = (eventLoop1, uri, options1, codec1, method, args1) -> circuitBreaker;
        CircuitBreakerRemoteInvoker stub = new CircuitBreakerRemoteInvoker(remoteInvoker, mapping);

        // CLOSED
        for (int i = 0; i < minimumRequestThreshold + 1; i++) {
            Future<Object> future = stub.invoke(eventLoop, uri, options, codec, methodA(), args);
            // The future is `failedFuture` itself
            assertThat(future.isSuccess(), is(false));
            // This is not a CircuitBreakerException
            assertThat(future.cause(), is(not(instanceOf(FailFastException.class))));
            ticker.advance(Duration.ofMillis(1).toNanos());
        }

        // OPEN
        Future<Object> future1 = stub.invoke(eventLoop, uri, options, codec, methodA(), args);
        // The circuit is still CLOSED
        assertThat(future1.isSuccess(), is(false));
        assertThat(future1.cause(), is(not(instanceOf(FailFastException.class))));
    }

}
