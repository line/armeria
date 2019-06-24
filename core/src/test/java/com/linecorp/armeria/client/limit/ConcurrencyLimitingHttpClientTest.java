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

package com.linecorp.armeria.client.limit;

import static com.linecorp.armeria.client.limit.ConcurrencyLimitingHttpClient.newDecorator;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextBuilder;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.stream.NoopSubscriber;
import com.linecorp.armeria.testing.junit.common.EventLoopExtension;

class ConcurrencyLimitingHttpClientTest {

    @RegisterExtension
    static final EventLoopExtension eventLoop = new EventLoopExtension();

    @Mock
    private Client<HttpRequest, HttpResponse> delegate;

    /**
     * Tests the request pattern  that does not exceed maxConcurrency.
     */
    @Test
    void testOrdinaryRequest() throws Exception {
        final HttpRequest req = newReq();
        final ClientRequestContext ctx = newContext();
        final HttpResponseWriter actualRes = HttpResponse.streaming();

        when(delegate.execute(ctx, req)).thenReturn(actualRes);

        final ConcurrencyLimitingHttpClient client =
                newDecorator(1).apply(delegate);
        assertThat(client.numActiveRequests()).isZero();

        final HttpResponse res = client.execute(ctx, req);
        assertThat(res.isOpen()).isTrue();
        assertThat(client.numActiveRequests()).isEqualTo(1);

        closeAndDrain(actualRes, res);

        assertThat(res.isOpen()).isFalse();
        await().untilAsserted(() -> assertThat(client.numActiveRequests()).isZero());
    }

    /**
     * Tests the request pattern that exceeds maxConcurrency.
     */
    @Test
    void testLimitedRequest() throws Exception {
        final ClientRequestContext ctx1 = newContext();
        final ClientRequestContext ctx2 = newContext();
        final HttpRequest req1 = newReq();
        final HttpRequest req2 = newReq();
        final HttpResponseWriter actualRes1 = HttpResponse.streaming();
        final HttpResponseWriter actualRes2 = HttpResponse.streaming();

        when(delegate.execute(ctx1, req1)).thenReturn(actualRes1);
        when(delegate.execute(ctx2, req2)).thenReturn(actualRes2);

        final ConcurrencyLimitingHttpClient client =
                newDecorator(1).apply(delegate);

        // The first request should be delegated immediately.
        final HttpResponse res1 = client.execute(ctx1, req1);
        verify(delegate).execute(ctx1, req1);
        assertThat(res1.isOpen()).isTrue();

        // The second request should never be delegated until the first response is closed.
        final HttpResponse res2 = client.execute(ctx2, req2);
        verify(delegate, never()).execute(ctx2, req2);
        assertThat(res2.isOpen()).isTrue();
        assertThat(client.numActiveRequests()).isEqualTo(1); // Only req1 is active.

        // Complete res1.
        closeAndDrain(actualRes1, res1);

        // Once res1 is complete, req2 should be delegated.
        await().untilAsserted(() -> verify(delegate).execute(ctx2, req2));
        assertThat(client.numActiveRequests()).isEqualTo(1); // Only req2 is active.

        // Complete res2, leaving no active requests.
        closeAndDrain(actualRes2, res2);
        await().untilAsserted(() -> assertThat(client.numActiveRequests()).isZero());
    }

    /**
     * Tests if the request is not delegated but closed when the timeout is reached before delegation.
     */
    @Test
    void testTimeout() throws Exception {
        final ClientRequestContext ctx1 = newContext();
        final ClientRequestContext ctx2 = newContext();
        final HttpRequest req1 = newReq();
        final HttpRequest req2 = newReq();
        final HttpResponseWriter actualRes1 = HttpResponse.streaming();
        final HttpResponseWriter actualRes2 = HttpResponse.streaming();

        when(delegate.execute(ctx1, req1)).thenReturn(actualRes1);

        final ConcurrencyLimitingHttpClient client =
                newDecorator(1, 500, TimeUnit.MILLISECONDS).apply(delegate);

        // Send two requests, where only the first one is delegated.
        final HttpResponse res1 = client.execute(ctx1, req1);
        final HttpResponse res2 = client.execute(ctx2, req2);

        // Let req2 time out.
        Thread.sleep(1000);
        res2.subscribe(NoopSubscriber.get());
        assertThatThrownBy(() -> res2.completionFuture().join())
                .hasCauseExactlyInstanceOf(UnprocessedRequestException.class)
                .hasRootCauseExactlyInstanceOf(PendingRequestTimeoutException.class);
        assertThat(res2.isOpen()).isFalse();

        // req1 should not time out because it's been delegated already.
        res1.subscribe(NoopSubscriber.get());
        assertThat(res1.isOpen()).isTrue();
        assertThat(res1.completionFuture()).isNotDone();

        // Close req1 and make sure req2 does not affect numActiveRequests.
        actualRes1.close();
        await().untilAsserted(() -> assertThat(client.numActiveRequests()).isZero());
    }

    /**
     * Tests the case where a delegate raises an exception rather than returning a response.
     */
    @Test
    void testFaultyDelegate() throws Exception {
        final ClientRequestContext ctx = newContext();
        final HttpRequest req = newReq();

        when(delegate.execute(ctx, req)).thenThrow(Exception.class);

        final ConcurrencyLimitingHttpClient client = newDecorator(1).apply(delegate);
        assertThat(client.numActiveRequests()).isZero();

        final HttpResponse res = client.execute(ctx, req);

        // Consume everything from the returned response so its close future is completed.
        res.subscribe(NoopSubscriber.get());

        assertThat(res.isOpen()).isFalse();
        assertThatThrownBy(() -> res.completionFuture().get()).hasCauseInstanceOf(Exception.class);
        await().untilAsserted(() -> assertThat(client.numActiveRequests()).isZero());
    }

    @Test
    void testUnlimitedRequest() throws Exception {
        final ClientRequestContext ctx = newContext();
        final HttpRequest req = newReq();
        final HttpResponseWriter actualRes = HttpResponse.streaming();

        when(delegate.execute(ctx, req)).thenReturn(actualRes);

        final ConcurrencyLimitingHttpClient client =
                newDecorator(0).apply(delegate);

        // A request should be delegated immediately, creating no deferred response.
        final HttpResponse res = client.execute(ctx, req);
        verify(delegate).execute(ctx, req);
        assertThat(res.isOpen()).isTrue();
        assertThat(client.numActiveRequests()).isEqualTo(1);

        // Complete the response, leaving no active requests.
        closeAndDrain(actualRes, res);
        await().untilAsserted(() -> assertThat(client.numActiveRequests()).isZero());
    }

    @Test
    void testUnlimitedRequestWithFaultyDelegate() throws Exception {
        final ClientRequestContext ctx = newContext();
        final HttpRequest req = newReq();

        when(delegate.execute(ctx, req)).thenThrow(Exception.class);

        final ConcurrencyLimitingHttpClient client = newDecorator(0).apply(delegate);

        // A request should be delegated immediately, rethrowing the exception from the delegate.
        assertThatThrownBy(() -> client.execute(ctx, req)).isInstanceOf(Exception.class);
        verify(delegate).execute(ctx, req);

        // The number of active requests should increase and then immediately decrease. i.e. stay back at 0.
        await().untilAsserted(() -> assertThat(client.numActiveRequests()).isZero());
    }

    private static ClientRequestContext newContext() {
        return ClientRequestContextBuilder.of(HttpRequest.of(HttpMethod.GET, "/"))
                                          .eventLoop(eventLoop.get())
                                          .build();
    }

    /**
     * Closes the response returned by the delegate and consumes everything from it, so that its close future
     * is completed.
     */
    private static void closeAndDrain(HttpResponseWriter actualRes, HttpResponse deferredRes) {
        actualRes.close();
        deferredRes.subscribe(NoopSubscriber.get());
        deferredRes.completionFuture().join();
        waitForEventLoop();
    }

    private static void waitForEventLoop() {
        eventLoop.get().submit(() -> { /* no-op */ }).syncUninterruptibly();
    }

    private static HttpRequest newReq() {
        return HttpRequest.of(HttpMethod.GET, "/dummy");
    }
}
