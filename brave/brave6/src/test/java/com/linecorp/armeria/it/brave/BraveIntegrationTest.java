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
package com.linecorp.armeria.it.brave;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.util.concurrent.Futures.allAsList;
import static com.google.common.util.concurrent.Futures.transformAsync;
import static com.linecorp.armeria.common.SessionProtocol.H1C;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.apache.thrift.async.AsyncMethodCallback;
import org.apache.thrift.transport.TTransportException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.InvalidResponseHeadersException;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.brave.BraveClient;
import com.linecorp.armeria.client.thrift.ThriftClients;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.brave.RequestContextCurrentTraceContext;
import com.linecorp.armeria.common.thrift.ThriftFuture;
import com.linecorp.armeria.common.util.ThreadFactories;
import com.linecorp.armeria.internal.testing.BlockingUtils;
import com.linecorp.armeria.internal.testing.GenerateNativeImageTrace;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.brave.BraveService;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import brave.ScopedSpan;
import brave.Span;
import brave.Tracer.SpanInScope;
import brave.Tracing;
import brave.handler.MutableSpan;
import brave.handler.SpanHandler;
import brave.propagation.CurrentTraceContext;
import brave.propagation.StrictScopeDecorator;
import brave.propagation.TraceContext;
import brave.sampler.Sampler;
import testing.brave.TestService;
import testing.brave.TestService.AsyncIface;

@GenerateNativeImageTrace
class BraveIntegrationTest {

    private static final SpanHandlerImpl spanHandler = new SpanHandlerImpl();

    private static TestService.Iface fooClient;
    private static TestService.Iface fooClientWithoutTracing;
    private static TestService.Iface timeoutClient;
    private static TestService.Iface timeoutClientClientTimesOut;
    private static TestService.Iface http1TimeoutClientClientTimesOut;
    private static TestService.AsyncIface barClient;
    private static TestService.AsyncIface quxClient;
    private static TestService.Iface zipClient;
    private static WebClient poolWebClient;

    @RegisterExtension
    static ServerExtension server = new ServerExtension(true) {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            // Our test that triggers a timeout will take 10 seconds to run to avoid flakiness
            // that a client cancels a request before a server receives it.
            sb.requestTimeout(Duration.ofSeconds(10));

            sb.service("/foo", decorate("service/foo", THttpService.of(
                    (AsyncIface) (name, resultHandler) ->
                            barClient.hello("Miss. " + name, new DelegatingCallback(resultHandler)))));

            sb.service("/bar", decorate("service/bar", THttpService.of(
                    (AsyncIface) (name, resultHandler) -> {
                        if (name.startsWith("Miss. ")) {
                            name = "Ms. " + name.substring(6);
                        }
                        quxClient.hello(name, new DelegatingCallback(resultHandler));
                    })));

            sb.service("/zip", decorate("service/zip", THttpService.of(
                    (AsyncIface) (name, resultHandler) -> {
                        final ThriftFuture<String> f1 = new ThriftFuture<>();
                        final ThriftFuture<String> f2 = new ThriftFuture<>();
                        quxClient.hello(name, f1);
                        quxClient.hello(name, f2);
                        CompletableFuture.allOf(f1, f2).whenCompleteAsync((aVoid, throwable) -> {
                            resultHandler.onComplete(f1.getNow(null) + ", and " + f2.getNow(null));
                        }, RequestContext.current().eventLoop());
                    })));

            sb.service("/qux", decorate("service/qux", THttpService.of(
                    (AsyncIface) (name, resultHandler) -> resultHandler.onComplete("Hello, " + name + '!'))));

            sb.service("/pool", decorate("service/pool", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                        throws Exception {
                    final ListeningExecutorService executorService = MoreExecutors.listeningDecorator(
                            Executors.newFixedThreadPool(2));
                    final CountDownLatch countDownLatch = new CountDownLatch(2);

                    final ListenableFuture<List<Object>> spanAware = allAsList(IntStream.range(1, 3).mapToObj(
                            i -> executorService.submit(
                                    RequestContext.current().makeContextAware(() -> {
                                        if (i == 2) {
                                            countDownLatch.countDown();
                                            countDownLatch.await();
                                        }
                                        final Span span = Tracing.currentTracer().nextSpan().start();
                                        try (SpanInScope unused =
                                                     Tracing.currentTracer().withSpanInScope(span)) {
                                            if (i == 1) {
                                                countDownLatch.countDown();
                                                countDownLatch.await();
                                                // to wait second task get span.
                                                Thread.sleep(1000L);
                                            }
                                        } finally {
                                            span.finish();
                                        }
                                        return null;
                                    }))).collect(toImmutableList()));

                    final CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
                    final HttpResponse res = HttpResponse.of(responseFuture);
                    transformAsync(spanAware,
                                   result -> allAsList(IntStream.range(1, 3).mapToObj(
                                           i -> executorService.submit(
                                                   RequestContext.current().makeContextAware(() -> {
                                                       final ScopedSpan span = Tracing.currentTracer()
                                                                                      .startScopedSpan("aloha");
                                                       try {
                                                           return null;
                                                       } finally {
                                                           span.finish();
                                                       }
                                                   })
                                           )).collect(toImmutableList())),
                                   RequestContext.current().eventLoop())
                            .addListener(() -> {
                                responseFuture.complete(HttpResponse.of(HttpStatus.OK,
                                                                        MediaType.PLAIN_TEXT_UTF_8,
                                                                        "Lee"));
                            }, RequestContext.current().eventLoop());
                    return res;
                }
            }));

            sb.service("/timeout", decorate("service/timeout", THttpService.of(
                    // This service never calls the handler and will timeout.
                    (AsyncIface) (name, resultHandler) -> {
                    })));

            sb.service("/http", (req, ctx) -> HttpResponse.of(HttpStatus.OK));
        }
    };

    @BeforeEach
    void setupClients() {
        fooClient = ThriftClients.builder(server.httpUri())
                                 .path("/foo")
                                 .decorator(BraveClient.newDecorator(newTracing("client/foo")))
                                 .build(TestService.Iface.class);
        zipClient = ThriftClients.builder(server.httpUri())
                                 .path("/zip")
                                 .decorator(BraveClient.newDecorator(newTracing("client/zip")))
                                 .build(TestService.Iface.class);
        fooClientWithoutTracing = ThriftClients.newClient(server.httpUri() + "/foo", TestService.Iface.class);
        barClient = newClient("/bar");
        quxClient = newClient("/qux");
        poolWebClient = WebClient.of(server.httpUri());
        timeoutClient = ThriftClients.builder(server.httpUri())
                                     .path("/timeout")
                                     .decorator(BraveClient.newDecorator(newTracing("client/timeout")))
                                     .build(TestService.Iface.class);
        timeoutClientClientTimesOut =
                ThriftClients.builder(server.httpUri())
                             .path("/timeout")
                             .decorator(BraveClient.newDecorator(newTracing("client/timeout")))
                             .responseTimeout(Duration.ofSeconds(3))
                             .build(TestService.Iface.class);
        http1TimeoutClientClientTimesOut =
                ThriftClients.builder(server.uri(H1C))
                             .path("/timeout")
                             .decorator(BraveClient.newDecorator(newTracing("client/timeout")))
                             .responseTimeout(Duration.ofSeconds(3))
                             .build(TestService.Iface.class);
    }

    @AfterEach
    void tearDown() {
        Tracing.current().close();
    }

    @AfterEach
    void shouldHaveNoExtraSpans() {
        assertThat(spanHandler.spans).isEmpty();
    }

    private static BraveService decorate(String name, HttpService service) {
        return BraveService.newDecorator(newTracing(name)).apply(service);
    }

    private static TestService.AsyncIface newClient(String path) {
        return ThriftClients.builder(server.httpUri())
                            .path(path)
                            .decorator(BraveClient.newDecorator(newTracing("client" + path)))
                            .build(TestService.AsyncIface.class);
    }

    private static Tracing newTracing(String name) {
        final CurrentTraceContext currentTraceContext =
                RequestContextCurrentTraceContext.builder()
                                                 .nonRequestThread("nonrequest-")
                                                 .addScopeDecorator(StrictScopeDecorator.create())
                                                 .build();
        return Tracing.newBuilder()
                      .currentTraceContext(currentTraceContext)
                      .localServiceName(name)
                      .addSpanHandler(spanHandler)
                      .sampler(Sampler.ALWAYS_SAMPLE)
                      .build();
    }

    @Test
    void testTimingAnnotations() {
        // Use separate client factory to make sure connection is created.
        try (ClientFactory clientFactory = ClientFactory.builder().build()) {
            final BlockingWebClient client =
                    WebClient.builder(server.httpUri())
                             .factory(clientFactory)
                             .decorator(BraveClient.newDecorator(newTracing("timed-client")))
                             .build()
                             .blocking();
            assertThat(client.get("/http").status()).isEqualTo(HttpStatus.OK);
            final MutableSpan[] initialConnectSpans = spanHandler.take(1);
            assertThat(initialConnectSpans[0].annotations())
                    .extracting(Map.Entry::getValue).containsExactlyInAnyOrder(
                            "connection-acquire.start",
                            "socket-connect.start",
                            "socket-connect.end",
                            "connection-acquire.end",
                            "ws",
                            "wr");

            // Make another request which will reuse the connection so no connection timing.
            assertThat(client.get("/http").status()).isEqualTo(HttpStatus.OK);

            final MutableSpan[] secondConnectSpans = spanHandler.take(1);
            assertThat(secondConnectSpans[0].annotations())
                    .extracting(Map.Entry::getValue).containsExactlyInAnyOrder(
                            "ws",
                            "wr");
        }
    }

    @Test
    void testServiceHasMultipleClientRequests() throws Exception {
        assertThat(zipClient.hello("Lee")).isEqualTo("Hello, Lee!, and Hello, Lee!");

        final MutableSpan[] spans = spanHandler.take(6);
        final String traceId = spans[0].traceId();
        assertThat(spans).allMatch(s -> s.traceId().equals(traceId));
    }

    @Test
    void testClientInitiatedTrace() throws Exception {
        assertThat(fooClient.hello("Lee")).isEqualTo("Hello, Ms. Lee!");

        final MutableSpan[] spans = spanHandler.take(6);
        final String traceId = spans[0].traceId();
        assertThat(spans).allMatch(s -> s.traceId().equals(traceId));

        // Find all spans.
        final MutableSpan clientFooSpan = findSpan(spans, "client/foo");
        final MutableSpan serviceFooSpan = findSpan(spans, "service/foo");
        final MutableSpan clientBarSpan = findSpan(spans, "client/bar");
        final MutableSpan serviceBarSpan = findSpan(spans, "service/bar");
        final MutableSpan clientQuxSpan = findSpan(spans, "client/qux");
        final MutableSpan serviceQuxSpan = findSpan(spans, "service/qux");

        // client/foo and service/foo should have no parents.
        assertThat(clientFooSpan.parentId()).isNull();
        assertThat(serviceFooSpan.parentId()).isNull();

        // client/foo and service/foo should have the ID values identical with their traceIds.
        assertThat(clientFooSpan.id()).isEqualTo(traceId);
        assertThat(serviceFooSpan.id()).isEqualTo(traceId);

        // The spans that do not cross the network boundary should have the same ID.
        assertThat(clientFooSpan.id()).isEqualTo(serviceFooSpan.id());
        assertThat(clientBarSpan.id()).isEqualTo(serviceBarSpan.id());
        assertThat(clientQuxSpan.id()).isEqualTo(serviceQuxSpan.id());

        // Check the parentIds.
        assertThat(clientBarSpan.parentId()).isEqualTo(clientFooSpan.id());
        assertThat(serviceBarSpan.parentId()).isEqualTo(clientFooSpan.id());
        assertThat(clientQuxSpan.parentId()).isEqualTo(clientBarSpan.id());
        assertThat(serviceQuxSpan.parentId()).isEqualTo(clientBarSpan.id());

        // Check the service names.
        assertThat(clientFooSpan.localServiceName()).isEqualTo("client/foo");
        assertThat(serviceFooSpan.localServiceName()).isEqualTo("service/foo");
        assertThat(clientBarSpan.localServiceName()).isEqualTo("client/bar");
        assertThat(serviceBarSpan.localServiceName()).isEqualTo("service/bar");
        assertThat(clientQuxSpan.localServiceName()).isEqualTo("client/qux");
        assertThat(serviceQuxSpan.localServiceName()).isEqualTo("service/qux");

        // Check RPC request can update http request.
        assertThat(clientFooSpan.tags().get("http.protocol")).isEqualTo("h2c");
        assertThat(clientFooSpan.tags().get("http.host")).startsWith("127.0.0.1");

        // Check the span names.
        assertThat(spans).allMatch(s -> "hello".equals(s.name()));

        // Check wire times
        final long clientStartTime = clientFooSpan.startTimestamp();
        final long clientWireSendTime = clientFooSpan.annotations().stream()
                                                     .filter(a -> "ws".equals(a.getValue()))
                                                     .findFirst().get().getKey();
        final long clientWireReceiveTime = clientFooSpan.annotations().stream()
                                                        .filter(a -> "wr".equals(a.getValue()))
                                                        .findFirst().get().getKey();
        final long clientEndTime = clientFooSpan.finishTimestamp();

        final long serverStartTime = serviceFooSpan.startTimestamp();
        final long serverWireSendTime = serviceFooSpan.annotations().stream()
                                                      .filter(a -> "ws".equals(a.getValue()))
                                                      .findFirst().get().getKey();
        final long serverWireReceiveTime = serviceFooSpan.annotations().stream()
                                                         .filter(a -> "wr".equals(a.getValue()))
                                                         .findFirst().get().getKey();
        final long serverEndTime = serviceFooSpan.finishTimestamp();

        // These values are taken at microsecond precision and should be reliable to compare to each other.

        // Because of the small deltas among these numbers in a unit test, a thread context switch can cause
        // client - server values to not compare correctly. We go ahead and only verify values recorded from the
        // same thread.

        assertThat(clientStartTime).isNotZero();
        assertThat(clientWireSendTime).isGreaterThanOrEqualTo(clientStartTime);
        assertThat(clientWireReceiveTime).isGreaterThanOrEqualTo(clientWireSendTime);
        assertThat(clientEndTime).isGreaterThanOrEqualTo(clientWireReceiveTime);

        // Server start time and wire receive time are essentially the same in our current model, and whether
        // one is greater than the other is mostly an implementation detail, so we don't compare them to each
        // other.

        assertThat(serverWireSendTime).isGreaterThanOrEqualTo(serverStartTime);
        assertThat(serverWireSendTime).isGreaterThanOrEqualTo(serverWireReceiveTime);
        assertThat(serverEndTime).isGreaterThanOrEqualTo(serverWireSendTime);
    }

    @Test
    void testServiceInitiatedTrace() throws Exception {
        assertThat(fooClientWithoutTracing.hello("Lee")).isEqualTo("Hello, Ms. Lee!");

        final MutableSpan[] spans = spanHandler.take(5);
        final String traceId = spans[0].traceId();
        assertThat(spans).allMatch(s -> s.traceId().equals(traceId));

        // Find all spans.
        final MutableSpan serviceFooSpan = findSpan(spans, "service/foo");
        final MutableSpan clientBarSpan = findSpan(spans, "client/bar");
        final MutableSpan serviceBarSpan = findSpan(spans, "service/bar");
        final MutableSpan clientQuxSpan = findSpan(spans, "client/qux");
        final MutableSpan serviceQuxSpan = findSpan(spans, "service/qux");

        // service/foo should have no parent.
        assertThat(serviceFooSpan.parentId()).isNull();

        // service/foo should have the ID value identical with its traceId.
        assertThat(serviceFooSpan.id()).isEqualTo(traceId);

        // The spans that do not cross the network boundary should have the same ID.
        assertThat(clientBarSpan.id()).isEqualTo(serviceBarSpan.id());
        assertThat(clientQuxSpan.id()).isEqualTo(serviceQuxSpan.id());

        // Check the parentIds
        assertThat(clientBarSpan.parentId()).isEqualTo(serviceFooSpan.id());
        assertThat(serviceBarSpan.parentId()).isEqualTo(serviceFooSpan.id());
        assertThat(clientQuxSpan.parentId()).isEqualTo(serviceBarSpan.id());
        assertThat(serviceQuxSpan.parentId()).isEqualTo(serviceBarSpan.id());

        // Check the service names.
        assertThat(serviceFooSpan.localServiceName()).isEqualTo("service/foo");
        assertThat(clientBarSpan.localServiceName()).isEqualTo("client/bar");
        assertThat(serviceBarSpan.localServiceName()).isEqualTo("service/bar");
        assertThat(clientQuxSpan.localServiceName()).isEqualTo("client/qux");
        assertThat(serviceQuxSpan.localServiceName()).isEqualTo("service/qux");

        // Check the span names.
        assertThat(spans).allMatch(s -> "hello".equals(s.name()));
    }

    @Test
    void testSpanInThreadPoolHasSameTraceId() throws Exception {
        poolWebClient.get("pool").aggregate().get();
        final MutableSpan[] spans = spanHandler.take(5);
        assertThat(Arrays.stream(spans).map(MutableSpan::traceId).collect(toImmutableSet())).hasSize(1);
        assertThat(Arrays.stream(spans).map(MutableSpan::parentId)
                         .filter(Objects::nonNull)
                         .collect(toImmutableSet())).hasSize(1);
    }

    @Test
    void testServerTimesOut() throws Exception {
        assertThatThrownBy(() -> timeoutClient.hello("name"))
                .isInstanceOf(TTransportException.class)
                .hasCauseInstanceOf(InvalidResponseHeadersException.class);
        final MutableSpan[] spans = spanHandler.take(2);

        final MutableSpan serverSpan = findSpan(spans, "service/timeout");
        final MutableSpan clientSpan = findSpan(spans, "client/timeout");

        // Server timed out meaning it did still send a timeout response to the client and we have all
        // annotations.
        assertThat(serverSpan.annotations()).hasSize(2);
        assertThat(clientSpan.annotations()).hasSize(2);
    }

    @Test
    void testHttp2ClientTimesOut() throws Exception {
        testClientTimesOut(timeoutClientClientTimesOut);
    }

    @Test
    void testHttp1ClientTimesOut() throws Exception {
        testClientTimesOut(http1TimeoutClientClientTimesOut);
    }

    private static void testClientTimesOut(TestService.Iface client) {
        assertThatThrownBy(() -> client.hello("name"))
                .isInstanceOf(TTransportException.class)
                .hasCauseInstanceOf(ResponseTimeoutException.class);
        final MutableSpan[] spans = spanHandler.take(2);

        final MutableSpan serverSpan = findSpan(spans, "service/timeout");
        final MutableSpan clientSpan = findSpan(spans, "client/timeout");

        // Collect all annotations except for connection attempts.
        final List<String> serverAnnotations = serverSpan.annotations().stream()
                                                         .map(Map.Entry::getValue)
                                                         .collect(toImmutableList());
        final List<String> clientAnnotations = clientSpan.annotations().stream()
                                                         .filter(a -> !a.getValue().contains("connect"))
                                                         .map(Map.Entry::getValue)
                                                         .collect(toImmutableList());

        // Client timed out, so no response data was ever sent from the server.
        // There is a wire send in the server and no wire receive in the client.
        assertThat(serverAnnotations).containsExactly("wr");
        assertThat(clientAnnotations).containsExactly("ws");
    }

    @Test
    void testNoRequestContextTraceable() throws Exception {
        RequestContextCurrentTraceContext.setCurrentThreadNotRequestThread(true);
        try {
            final Tracing tracing = newTracing("no-request");
            final ScopedSpan span1 = tracing.tracer().startScopedSpan("span1");
            final ScopedSpan span2 = tracing.tracer().startScopedSpan("span2");

            assertThat(span2.context().traceId()).isEqualTo(span1.context().traceId());

            span2.finish();
            span1.finish();

            spanHandler.take(2);
        } finally {
            RequestContextCurrentTraceContext.setCurrentThreadNotRequestThread(false);
        }
    }

    @Test
    void testNonRequestContextThreadPatternTraceable() throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        ThreadFactories.builder("nonrequest-").eventLoop(false)
                       .build()
                       .newThread(() -> {
                           final Tracing tracing = newTracing("no-request");
                           final ScopedSpan span1 = tracing.tracer().startScopedSpan("span1");
                           final ScopedSpan span2 = tracing.tracer().startScopedSpan("span2");

                           assertThat(span2.context().traceId()).isEqualTo(span1.context().traceId());

                           span2.finish();
                           span1.finish();

                           spanHandler.take(2);
                           done.countDown();
                       }).start();
        done.await();
    }

    private static MutableSpan findSpan(MutableSpan[] spans, String serviceName) {
        return Arrays.stream(spans)
                     .filter(s -> serviceName.equals(s.localServiceName()))
                     .findAny()
                     .orElseThrow(() -> new AssertionError(
                             "Can't find a Span with service name: " + serviceName));
    }

    private static class DelegatingCallback implements AsyncMethodCallback<String> {
        private final AsyncMethodCallback<Object> resultHandler;

        @SuppressWarnings({ "unchecked", "rawtypes" })
        DelegatingCallback(AsyncMethodCallback resultHandler) {
            this.resultHandler = resultHandler;
        }

        @Override
        public void onComplete(String response) {
            resultHandler.onComplete(response);
        }

        @Override
        public void onError(Exception exception) {
            resultHandler.onError(exception);
        }
    }

    private static class SpanHandlerImpl extends SpanHandler {
        private final BlockingQueue<MutableSpan> spans = new LinkedBlockingQueue<>();

        @Override
        public boolean end(TraceContext context, MutableSpan span, Cause cause) {
            return BlockingUtils.blockingRun(() -> spans.add(span));
        }

        MutableSpan[] take(int numSpans) {
            final List<MutableSpan> taken = new ArrayList<>();
            while (taken.size() < numSpans) {
                BlockingUtils.blockingRun(() -> taken.add(spans.poll(30, TimeUnit.SECONDS)));
            }

            // Reverse the collected spans to sort the spans by request time.
            Collections.reverse(taken);
            return taken.toArray(new MutableSpan[numSpans]);
        }
    }
}
