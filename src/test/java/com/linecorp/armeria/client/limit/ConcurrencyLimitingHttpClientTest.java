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

package com.linecorp.armeria.client.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;

import org.junit.AfterClass;
import org.junit.Test;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.common.http.DefaultHttpResponse;
import com.linecorp.armeria.common.http.DeferredHttpResponse;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.common.stream.NoopSubscriber;

import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoop;

public class ConcurrencyLimitingHttpClientTest {

    private static final EventLoop eventLoop = new DefaultEventLoop();

    @AfterClass
    public static void destroy() {
        eventLoop.shutdownGracefully();
    }

    /**
     * Tests the request pattern  that does not exceed maxConcurrency.
     */
    @Test
    public void testOrdinaryRequest() throws Exception {
        final ClientRequestContext ctx = newContext();
        final HttpRequest req = mock(HttpRequest.class);
        final DefaultHttpResponse actualRes = new DefaultHttpResponse();

        @SuppressWarnings("unchecked")
        final Client<HttpRequest, HttpResponse> delegate = mock(Client.class);
        when(delegate.execute(ctx, req)).thenReturn(actualRes);

        final ConcurrencyLimitingHttpClient client =
                ConcurrencyLimitingHttpClient.newDecorator(1).apply(delegate);
        assertThat(client.numActiveRequests()).isZero();

        final HttpResponse res = client.execute(ctx, req);
        assertThat(res).isInstanceOf(DeferredHttpResponse.class);
        assertThat(res.isOpen()).isTrue();
        assertThat(client.numActiveRequests()).isEqualTo(1);

        closeAndDrain(actualRes, res);

        assertThat(res.isOpen()).isFalse();
        assertThat(client.numActiveRequests()).isZero();
    }

    /**
     * Tests the request pattern that exceeds maxConcurrency.
     */
    @Test
    public void testLimitedRequest() throws Exception {
        final ClientRequestContext ctx1 = newContext();
        final ClientRequestContext ctx2 = newContext();
        final HttpRequest req1 = mock(HttpRequest.class);
        final HttpRequest req2 = mock(HttpRequest.class);
        final DefaultHttpResponse actualRes1 = new DefaultHttpResponse();
        final DefaultHttpResponse actualRes2 = new DefaultHttpResponse();

        @SuppressWarnings("unchecked")
        final Client<HttpRequest, HttpResponse> delegate = mock(Client.class);
        when(delegate.execute(ctx1, req1)).thenReturn(actualRes1);
        when(delegate.execute(ctx2, req2)).thenReturn(actualRes2);

        final ConcurrencyLimitingHttpClient client =
                ConcurrencyLimitingHttpClient.newDecorator(1).apply(delegate);

        // The first request should be delegated immediately.
        final HttpResponse res1 = client.execute(ctx1, req1);
        verify(delegate).execute(ctx1, req1);
        assertThat(res1).isInstanceOf(DeferredHttpResponse.class);
        assertThat(res1.isOpen()).isTrue();

        // The second request should never be delegated until the first response is closed.
        final HttpResponse res2 = client.execute(ctx2, req2);
        verify(delegate, never()).execute(ctx2, req2);
        assertThat(res2).isInstanceOf(DeferredHttpResponse.class);
        assertThat(res2.isOpen()).isTrue();
        assertThat(client.numActiveRequests()).isEqualTo(1); // Only req1 is active.

        // Complete res1.
        closeAndDrain(actualRes1, res1);

        // Once res1 is complete, req2 should be delegated.
        verify(delegate).execute(ctx2, req2);
        assertThat(client.numActiveRequests()).isEqualTo(1); // Only req2 is active.

        // Complete res2, leaving no active requests.
        closeAndDrain(actualRes2, res2);
        assertThat(client.numActiveRequests()).isZero();
    }

    /**
     * Tests if the request is not delegated but closed when the timeout is reached before delegation.
     */
    @Test
    public void testTimeout() throws Exception {
        final ClientRequestContext ctx1 = newContext();
        final ClientRequestContext ctx2 = newContext();
        final HttpRequest req1 = mock(HttpRequest.class);
        final HttpRequest req2 = mock(HttpRequest.class);
        final DefaultHttpResponse actualRes1 = new DefaultHttpResponse();
        final DefaultHttpResponse actualRes2 = new DefaultHttpResponse();

        @SuppressWarnings("unchecked")
        final Client<HttpRequest, HttpResponse> delegate = mock(Client.class);
        when(delegate.execute(ctx1, req1)).thenReturn(actualRes1);
        when(delegate.execute(ctx2, req2)).thenReturn(actualRes2);

        final ConcurrencyLimitingHttpClient client =
                ConcurrencyLimitingHttpClient.newDecorator(1, 500, TimeUnit.MILLISECONDS).apply(delegate);

        // Send two requests, where only the first one is delegated.
        final HttpResponse res1 = client.execute(ctx1, req1);
        final HttpResponse res2 = client.execute(ctx2, req2);

        // Let req2 time out.
        Thread.sleep(1000);
        res2.subscribe(NoopSubscriber.get());
        assertThat(res2.isOpen()).isFalse();
        assertThat(res2.closeFuture()).isCompletedExceptionally();
        assertThatThrownBy(() -> res2.closeFuture().get()).hasCauseInstanceOf(ResponseTimeoutException.class);

        // req1 should not time out because it's been delegated already.
        res1.subscribe(NoopSubscriber.get());
        assertThat(res1.isOpen()).isTrue();
        assertThat(res1.closeFuture()).isNotDone();

        // Close req1 and make sure req2 does not affect numActiveRequests.
        actualRes1.close();
        waitForEventLoop();
        assertThat(client.numActiveRequests()).isZero();
    }

    /**
     * Tests the case where a delegate raises an exception rather than returning a response.
     */
    @Test
    public void testFaultyDelegate() throws Exception {
        final ClientRequestContext ctx = newContext();
        final HttpRequest req = mock(HttpRequest.class);

        @SuppressWarnings("unchecked")
        final Client<HttpRequest, HttpResponse> delegate = mock(Client.class);
        when(delegate.execute(ctx, req)).thenThrow(Exception.class);

        final ConcurrencyLimitingHttpClient client =
                ConcurrencyLimitingHttpClient.newDecorator(1).apply(delegate);
        assertThat(client.numActiveRequests()).isZero();

        final HttpResponse res = client.execute(ctx, req);

        // Consume everything from the returned response so its close future is completed.
        res.subscribe(NoopSubscriber.get());

        assertThat(res).isInstanceOf(DeferredHttpResponse.class);
        assertThat(res.isOpen()).isFalse();
        assertThat(res.closeFuture()).isCompletedExceptionally();
        assertThatThrownBy(() -> res.closeFuture().get()).hasCauseInstanceOf(Exception.class);
        assertThat(client.numActiveRequests()).isZero();
    }

    @Test
    public void testUnlimitedRequest() throws Exception {
        final ClientRequestContext ctx = newContext();
        final HttpRequest req = mock(HttpRequest.class);
        final DefaultHttpResponse actualRes = new DefaultHttpResponse();

        @SuppressWarnings("unchecked")
        final Client<HttpRequest, HttpResponse> delegate = mock(Client.class);
        when(delegate.execute(ctx, req)).thenReturn(actualRes);

        final ConcurrencyLimitingHttpClient client =
                ConcurrencyLimitingHttpClient.newDecorator(0).apply(delegate);

        // A request should be delegated immediately, creating no deferred response.
        final HttpResponse res = client.execute(ctx, req);
        verify(delegate).execute(ctx, req);
        assertThat(res).isNotInstanceOf(DeferredHttpResponse.class);
        assertThat(res.isOpen()).isTrue();
        assertThat(client.numActiveRequests()).isEqualTo(1);

        // Complete the response, leaving no active requests.
        closeAndDrain(actualRes, res);
        assertThat(client.numActiveRequests()).isZero();
    }

    @Test
    public void testUnlimitedRequestWithFaultyDelegate() throws Exception {
        final ClientRequestContext ctx = newContext();
        final HttpRequest req = mock(HttpRequest.class);

        @SuppressWarnings("unchecked")
        final Client<HttpRequest, HttpResponse> delegate = mock(Client.class);
        when(delegate.execute(ctx, req)).thenThrow(Exception.class);

        final ConcurrencyLimitingHttpClient client =
                ConcurrencyLimitingHttpClient.newDecorator(0).apply(delegate);

        // A request should be delegated immediately, rethrowing the exception from the delegate.
        assertThatThrownBy(() -> client.execute(ctx, req)).isInstanceOf(Exception.class);
        verify(delegate).execute(ctx, req);

        // The number of active requests should increase and then immediately decrease. i.e. stay back at 0.
        assertThat(client.numActiveRequests()).isZero();
    }

    private static ClientRequestContext newContext() {
        final ClientRequestContext ctx = mock(ClientRequestContext.class);
        when(ctx.eventLoop()).thenReturn(eventLoop);
        return ctx;
    }

    /**
     * Closes the response returned by the delegate and consumes everything from it, so that its close future
     * is completed.
     */
    private static void closeAndDrain(DefaultHttpResponse actualRes, HttpResponse deferredRes) {
        actualRes.close();
        deferredRes.subscribe(NoopSubscriber.get());
        waitForEventLoop();
    }

    private static void waitForEventLoop() {
        eventLoop.submit(() -> {}).syncUninterruptibly();
    }
}
