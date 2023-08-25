/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.testing.junit5.server.mock;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

/**
 * An {@link Extension} primarily for testing clients. {@link MockWebServerExtension} will start and stop a
 * {@link Server} at the beginning and end of a test class. Call
 * {@link MockWebServerExtension#enqueue(HttpResponse)} as many times as you will make requests
 * within the test. The enqueued responses will be returned in order for each of these requests. Later, call
 * {@link MockWebServerExtension#takeRequest()} to retrieve requests in the order the server received them to
 * validate the request parameters.
 *
 * <p>Note, tests in a class using {@link MockWebServerExtension} cannot be run concurrently, i.e., do not set
 * {@link org.junit.jupiter.api.parallel.Execution} to
 * {@link org.junit.jupiter.api.parallel.ExecutionMode#CONCURRENT}.
 *
 * <p>For example, <pre>{@code
 *
 * > class MyTest {
 * >
 * >   @RegisterExtension
 * >   static MockWebServerExtension server = new MockWebServerExtension();
 * >
 * >   @Test
 * >   void checkSomething() {
 * >       WebClient client = WebClient.of(server.httpUri());
 * >
 * >       server.enqueue(AggregatedHttpResponse.of(HttpStatus.OK));
 * >       server.enqueue(AggregatedHttpResponse.of(HttpStatus.FORBIDDEN));
 * >
 * >       assertThat(client.get("/").aggregate().join().status()).isEqualTo(HttpStatus.OK);
 * >       assertThat(client.get("/bad").aggregate().join().status()).isEqualTo(HttpStatus.FORBIDDEN);
 * >
 * >       assertThat(server.takeRequest().path()).isEqualTo("/");
 * >       assertThat(server.takeRequest().path()).isEqualTo("/bad");
 * >   }
 * > }
 * }</pre>
 */
public class MockWebServerExtension extends ServerExtension implements BeforeTestExecutionCallback {

    private final BlockingQueue<HttpResponse> mockResponses = new LinkedBlockingQueue<>();
    private final BlockingQueue<RecordedRequest> recordedRequests = new LinkedBlockingQueue<>();

    /**
     * Enqueues the {@link HttpResponse} to return to a client of this {@link MockWebServerExtension}. Multiple
     * calls will return multiple responses in order.
     */
    public final MockWebServerExtension enqueue(HttpResponse response) {
        requireNonNull(response, "response");
        mockResponses.add(response);
        return this;
    }

    /**
     * Enqueues the {@link AggregatedHttpResponse} to return to a client of this {@link MockWebServerExtension}.
     * Multiple calls will return multiple responses in order.
     */
    public final MockWebServerExtension enqueue(AggregatedHttpResponse response) {
        requireNonNull(response, "response");
        mockResponses.add(response.toHttpResponse());
        return this;
    }

    /**
     * Returns the next {@link RecordedRequest} the server received. Call this method multiple times to retrieve
     * the requests, in order. Will block up to 10 seconds waiting for a request.
     */
    @Nullable
    public final RecordedRequest takeRequest() {
        return takeRequest(10, TimeUnit.SECONDS);
    }

    /**
     * Returns the next {@link RecordedRequest} the server received. Call this method multiple times to retrieve
     * the requests, in order. Will block the given amount of time waiting for a request.
     */
    @Nullable
    public final RecordedRequest takeRequest(int amount, TimeUnit unit) {
        requireNonNull(unit, "unit");
        boolean interrupted = false;
        try {
            long remainingNanos = unit.toNanos(amount);
            final long end = System.nanoTime() + remainingNanos;

            while (true) {
                try {
                    // BlockingQueue treats negative timeouts just like zero.
                    return recordedRequests.poll(remainingNanos, NANOSECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                    remainingNanos = end - System.nanoTime();
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    protected final void configure(ServerBuilder sb) throws Exception {
        sb.http(0);
        sb.https(0);
        sb.tlsSelfSigned();

        sb.serviceUnder("/", new MockWebService());

        configureServer(sb);
    }

    /**
     * Configures the {@link ServerBuilder} beyond the defaults of enabling HTTPs and registering a service for
     * handling enqueued responses. Override this method to configure advanced behavior such as client
     * authentication and timeouts. Don't call any of the {@code service*} methods, even if you do they will be
     * ignored as {@link MockWebServerExtension} can only serve responses registered with
     * {@link MockWebServerExtension#enqueue(HttpResponse)}.
     */
    protected void configureServer(ServerBuilder sb) throws Exception {
        // Do nothing by default.
    }

    @Override
    public final void beforeTestExecution(ExtensionContext context) {
        reset();
    }

    /**
     * Resets the mocking state of this extension. This only needs to be called if using this class without
     * JUnit 5.
     */
    public void reset() {
        mockResponses.clear();
        recordedRequests.clear();
    }

    private class MockWebService implements HttpService {
        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            return HttpResponse.of(req.aggregate().thenApply(aggReq -> {
                recordedRequests.add(new RecordedRequest(ctx, aggReq));

                final HttpResponse response = mockResponses.poll();
                if (response == null) {
                    throw new IllegalStateException(
                            "No response enqueued. Did you call MockWebServer.enqueue? Request: " + aggReq);
                }

                return response;
            }));
        }
    }
}
