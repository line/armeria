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

package com.linecorp.armeria.testing.junit.server.mockwebserver;

import static java.util.concurrent.CompletableFuture.completedFuture;

import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

/**
 * An {@link Extension} primarily for testing clients. {@link MockWebServerExtension} will start and stop a
 * {@link Server} at the beginning and end of a test class. Call
 * {@link MockWebServerExtension#enqueue(MockResponse)} as many times as you will make requests
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
 * class MyTest {
 *
 * {@literal @}RegisterExtension
 * static MockWebServerExtension server = new MockWebServerExtension();
 *
 * {@literal @}Test
 * void checkSomething() {
 *     HttpClient client = HttpClient.of(server.httpUri("/"));
 *
 *     server.enqueue(AggregatedHttpResponse.of(HttpStatus.OK));
 *     server.enqueue(AggregatedHttpResponse.of(HttpStatus.FORBIDDEN));
 *
 *     assertThat(client.get("/").aggregate().join().status()).isEqualTo(HttpStatus.OK);
 *     assertThat(client.get("/bad").aggregate().join().status()).isEqualTo(HttpStatus.FORBIDDEN);
 *
 *     assertThat(server.takeRequest().path()).isEqualTo("/");
 *     assertThat(server.takeRequest().path()).isEqualTo("/bad");
 * }
 *
 * }
 * }</pre>
 */
public class MockWebServerExtension extends ServerExtension implements BeforeTestExecutionCallback {

    private final Queue<MockResponse> mockResponses = new LinkedBlockingQueue<>();
    private final Queue<RecordedRequest> recordedRequests = new LinkedBlockingQueue<>();

    public MockWebServerExtension enqueue(MockResponse response) {
        mockResponses.add(response);
        return this;
    }

    public MockWebServerExtension enqueue(AggregatedHttpResponse response) {
        mockResponses.add(MockResponse.of(response));
        return this;
    }

    @Nullable
    public RecordedRequest takeRequest() {
        return recordedRequests.poll();
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
     * {@link MockWebService#enqueue(MockResponse)}.
     */
    protected void configureServer(ServerBuilder sb) throws Exception {
        // Do nothing by default.
    }

    @Override
    public final void beforeTestExecution(ExtensionContext context) {
        mockResponses.clear();
        recordedRequests.clear();
    }

    private class MockWebService implements Service<HttpRequest, HttpResponse> {
        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            return HttpResponse.from(req.aggregate().thenApply(aggReq -> {
                recordedRequests.add(new RecordedRequest(aggReq, ctx));

                final MockResponse mockResponse = mockResponses.poll();
                if (mockResponse == null) {
                    throw new IllegalStateException(
                            "No response enqueued. Did you call MockWebServer.enqueue? Request: " + aggReq);
                }

                final Duration headersDelay = mockResponse.headersDelay();
                final Duration contentDelay = mockResponse.contentDelay();
                final Duration trailersDelay = mockResponse.trailersDelay();

                final AggregatedHttpResponse response = mockResponse.response();

                if (headersDelay.isZero() && contentDelay.isZero() && trailersDelay.isZero()) {
                    return HttpResponse.of(response);
                }

                HttpResponseWriter httpResponse = HttpResponse.streaming();

                CompletableFuture<Void> headersWritten = new CompletableFuture<>();
                if (headersDelay.isZero()) {
                    httpResponse.write(response.headers());
                    headersWritten.complete(null);
                } else {
                    ctx.eventLoop().schedule(() -> httpResponse.write(response.headers()),
                                             headersDelay.toNanos(), TimeUnit.NANOSECONDS)
                       .addListener(unused -> headersWritten.complete(null));
                }

                CompletableFuture<Void> bodyWritten = headersWritten
                        .thenCompose(unused -> {
                            if (contentDelay.isZero()) {
                                httpResponse.write(response.content());
                                return completedFuture(null);
                            } else {
                                CompletableFuture<Void> future = new CompletableFuture<>();
                                ctx.eventLoop().schedule(() -> httpResponse.write(response.content()),
                                                         contentDelay.toNanos(), TimeUnit.NANOSECONDS)
                                   .addListener(unused1 -> future.complete(null));
                                return future;
                            }
                        });

                bodyWritten.thenAccept(unused -> {
                    if (trailersDelay.isZero()) {
                        httpResponse.write(response.trailers());
                        httpResponse.close();
                    } else {
                        ctx.eventLoop().schedule(
                                () -> {
                                    httpResponse.write(response.trailers());
                                    httpResponse.close();
                                },
                                trailersDelay.toNanos(), TimeUnit.NANOSECONDS);
                    }
                });

                return httpResponse;
            }));
        }
    }
}
