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
import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.BINARY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.IntStream;

import org.apache.thrift.async.AsyncMethodCallback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Uninterruptibles;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.InvalidResponseHeadersException;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.brave.BraveClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.brave.HelloService;
import com.linecorp.armeria.common.brave.HelloService.AsyncIface;
import com.linecorp.armeria.common.brave.RequestContextCurrentTraceContext;
import com.linecorp.armeria.common.thrift.ThriftFuture;
import com.linecorp.armeria.common.util.ThreadFactories;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.brave.BraveService;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

import brave.ScopedSpan;
import brave.Tracer.SpanInScope;
import brave.Tracing;
import brave.propagation.CurrentTraceContext;
import brave.propagation.StrictScopeDecorator;
import brave.sampler.Sampler;
import zipkin2.Annotation;
import zipkin2.Span;
import zipkin2.reporter.Reporter;

class BraveIntegrationTest {

    private static final ReporterImpl spanReporter = new ReporterImpl();

    private static HelloService.Iface fooClient;
    private static HelloService.Iface fooClientWithoutTracing;
    private static HelloService.Iface timeoutClient;
    private static HelloService.Iface timeoutClientClientTimesOut;
    private static HelloService.Iface http1TimeoutClientClientTimesOut;
    private static HelloService.AsyncIface barClient;
    private static HelloService.AsyncIface quxClient;
    private static HelloService.Iface zipClient;
    private static WebClient poolWebClient;

    @RegisterExtension
    static ServerExtension server = new ServerExtension(true) {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            // Our test that triggers a timeout will take a second to run. Hopefully it doesn't cause flakiness
            // for being too short.
            sb.requestTimeout(Duration.ofSeconds(1));

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
                        }, RequestContext.current().contextAwareExecutor());
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
                                        final brave.Span span = Tracing.currentTracer().nextSpan().start();
                                        try (SpanInScope spanInScope =
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
                    final HttpResponse res = HttpResponse.from(responseFuture);
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
                                   RequestContext.current().contextAwareExecutor())
                            .addListener(() -> {
                                responseFuture.complete(HttpResponse.of(HttpStatus.OK,
                                                                        MediaType.PLAIN_TEXT_UTF_8,
                                                                        "Lee"));
                            }, RequestContext.current().contextAwareExecutor());
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
        fooClient = Clients.builder(server.httpUri(BINARY) + "/foo")
                           .decorator(BraveClient.newDecorator(newTracing("client/foo")))
                           .build(HelloService.Iface.class);
        zipClient = Clients.builder(server.httpUri(BINARY) + "/zip")
                           .decorator(BraveClient.newDecorator(newTracing("client/zip")))
                           .build(HelloService.Iface.class);
        fooClientWithoutTracing = Clients.newClient(server.httpUri(BINARY) + "/foo", HelloService.Iface.class);
        barClient = newClient("/bar");
        quxClient = newClient("/qux");
        poolWebClient = WebClient.of(server.httpUri());
        timeoutClient = Clients.builder(server.httpUri(BINARY) + "/timeout")
                               .decorator(BraveClient.newDecorator(newTracing("client/timeout")))
                               .build(HelloService.Iface.class);
        timeoutClientClientTimesOut =
                Clients.builder(server.httpUri(BINARY) + "/timeout")
                       .decorator(BraveClient.newDecorator(newTracing("client/timeout")))
                       .responseTimeout(Duration.ofMillis(10))
                       .build(HelloService.Iface.class);
        http1TimeoutClientClientTimesOut =
                Clients.builder(server.uri(H1C, BINARY) + "/timeout")
                       .decorator(BraveClient.newDecorator(newTracing("client/timeout")))
                       .responseTimeout(Duration.ofMillis(10))
                       .build(HelloService.Iface.class);
    }

    @AfterEach
    void tearDown() {
        Tracing.current().close();
    }

    @AfterEach
    void shouldHaveNoExtraSpans() {
        assertThat(spanReporter.spans).isEmpty();
    }

    private static BraveService decorate(String name, HttpService service) {
        return BraveService.newDecorator(newTracing(name)).apply(service);
    }

    private static HelloService.AsyncIface newClient(String path) {
        return Clients.builder(server.httpUri(BINARY).resolve(path))
                      .decorator(BraveClient.newDecorator(newTracing("client" + path)))
                      .build(HelloService.AsyncIface.class);
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
                      .spanReporter(spanReporter)
                      .sampler(Sampler.ALWAYS_SAMPLE)
                      .build();
    }

    @Test
    void testTimingAnnotations() {
        // Use separate client factory to make sure connection is created.
        final ClientFactory clientFactory = ClientFactory.builder().build();
        final WebClient client = WebClient.builder(server.httpUri())
                                          .factory(clientFactory)
                                          .decorator(BraveClient.newDecorator(newTracing("timed-client")))
                                          .build();
        assertThat(client.get("/http").aggregate().join().status()).isEqualTo(HttpStatus.OK);
        final Span[] initialConnectSpans = spanReporter.take(1);
        assertThat(initialConnectSpans[0].annotations())
                .extracting(Annotation::value).containsExactlyInAnyOrder(
                "connection-acquire.start",
                "socket-connect.start",
                "socket-connect.end",
                "connection-acquire.end",
                "ws",
                "wr");

        // Make another request which will reuse the connection so no connection timing.
        assertThat(client.get("/http").aggregate().join().status()).isEqualTo(HttpStatus.OK);

        final Span[] secondConnectSpans = spanReporter.take(1);
        assertThat(secondConnectSpans[0].annotations())
                .extracting(Annotation::value).containsExactlyInAnyOrder(
                "ws",
                "wr");
    }

    @Test
    void testServiceHasMultipleClientRequests() throws Exception {
        assertThat(zipClient.hello("Lee")).isEqualTo("Hello, Lee!, and Hello, Lee!");

        final Span[] spans = spanReporter.take(6);
        final String traceId = spans[0].traceId();
        assertThat(spans).allMatch(s -> s.traceId().equals(traceId));
    }

    @Test
    void testClientInitiatedTrace() throws Exception {
        assertThat(fooClient.hello("Lee")).isEqualTo("Hello, Ms. Lee!");

        final Span[] spans = spanReporter.take(6);
        final String traceId = spans[0].traceId();
        assertThat(spans).allMatch(s -> s.traceId().equals(traceId));

        // Find all spans.
        final Span clientFooSpan = findSpan(spans, "client/foo");
        final Span serviceFooSpan = findSpan(spans, "service/foo");
        final Span clientBarSpan = findSpan(spans, "client/bar");
        final Span serviceBarSpan = findSpan(spans, "service/bar");
        final Span clientQuxSpan = findSpan(spans, "client/qux");
        final Span serviceQuxSpan = findSpan(spans, "service/qux");

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
        final long clientStartTime = clientFooSpan.timestampAsLong();
        final long clientWireSendTime = clientFooSpan.annotations().stream()
                                                     .filter(a -> "ws".equals(a.value()))
                                                     .findFirst().get().timestamp();
        final long clientWireReceiveTime = clientFooSpan.annotations().stream()
                                                        .filter(a -> "wr".equals(a.value()))
                                                        .findFirst().get().timestamp();
        final long clientEndTime = clientStartTime + clientFooSpan.durationAsLong();

        final long serverStartTime = serviceFooSpan.timestampAsLong();
        final long serverWireSendTime = serviceFooSpan.annotations().stream()
                                                      .filter(a -> "ws".equals(a.value()))
                                                      .findFirst().get().timestamp();
        final long serverWireReceiveTime = serviceFooSpan.annotations().stream()
                                                         .filter(a -> "wr".equals(a.value()))
                                                         .findFirst().get().timestamp();
        final long serverEndTime = serverStartTime + serviceFooSpan.durationAsLong();

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

        final Span[] spans = spanReporter.take(5);
        final String traceId = spans[0].traceId();
        assertThat(spans).allMatch(s -> s.traceId().equals(traceId));

        // Find all spans.
        final Span serviceFooSpan = findSpan(spans, "service/foo");
        final Span clientBarSpan = findSpan(spans, "client/bar");
        final Span serviceBarSpan = findSpan(spans, "service/bar");
        final Span clientQuxSpan = findSpan(spans, "client/qux");
        final Span serviceQuxSpan = findSpan(spans, "service/qux");

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
        final Span[] spans = spanReporter.take(5);
        assertThat(Arrays.stream(spans).map(Span::traceId).collect(toImmutableSet())).hasSize(1);
        assertThat(Arrays.stream(spans).map(Span::parentId)
                         .filter(Objects::nonNull)
                         .collect(toImmutableSet())).hasSize(1);
    }

    @Test
    void testServerTimesOut() throws Exception {
        assertThatThrownBy(() -> timeoutClient.hello("name"))
                .isInstanceOf(InvalidResponseHeadersException.class);
        final Span[] spans = spanReporter.take(2);

        final Span serverSpan = findSpan(spans, "service/timeout");
        final Span clientSpan = findSpan(spans, "client/timeout");

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

    private static void testClientTimesOut(HelloService.Iface client) {
        assertThatThrownBy(() -> client.hello("name"))
                .isInstanceOf(ResponseTimeoutException.class);
        final Span[] spans = spanReporter.take(2);

        final Span serverSpan = findSpan(spans, "service/timeout");
        final Span clientSpan = findSpan(spans, "client/timeout");

        // Collect all annotations except for connection attempts.
        final List<Annotation> serverAnnotations = serverSpan.annotations();
        final List<Annotation> clientAnnotations = clientSpan.annotations().stream()
                                                             .filter(a -> !a.value().contains("connect"))
                                                             .collect(toImmutableList());

        // Client timed out, so no response data was ever sent from the server.
        // There is a wire send in the server and no wire receive in the client.
        assertThat(serverAnnotations).hasSize(1);
        assertThat(serverAnnotations.get(0).value()).isEqualTo("wr");
        assertThat(clientAnnotations).hasSize(1);
        assertThat(clientAnnotations.get(0).value()).isEqualTo("ws");
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

            spanReporter.take(2);
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

                           spanReporter.take(2);
                           done.countDown();
                       }).start();
        done.await();
    }

    private static Span findSpan(Span[] spans, String serviceName) {
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

    private static class ReporterImpl implements Reporter<Span> {
        private final BlockingQueue<Span> spans = new LinkedBlockingQueue<>();

        @Override
        public void report(Span span) {
            spans.add(span);
        }

        Span[] take(int numSpans) {
            final List<Span> taken = new ArrayList<>();
            while (taken.size() < numSpans) {
                taken.add(Uninterruptibles.takeUninterruptibly(spans));
            }

            // Reverse the collected spans to sort the spans by request time.
            Collections.reverse(taken);
            return taken.toArray(new Span[numSpans]);
        }
    }
}
