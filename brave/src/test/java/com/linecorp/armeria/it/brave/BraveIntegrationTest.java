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
import static com.linecorp.armeria.common.HttpStatus.OK;
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
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.InvalidResponseHeadersException;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.brave.ArmeriaHttpClientParser;
import com.linecorp.armeria.client.brave.BraveClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.brave.HelloService;
import com.linecorp.armeria.common.brave.HelloService.AsyncIface;
import com.linecorp.armeria.common.brave.RequestContextCurrentTraceContext;
import com.linecorp.armeria.common.thrift.ThriftCompletableFuture;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.brave.ArmeriaHttpServerParser;
import com.linecorp.armeria.server.brave.BraveService;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.testing.junit4.server.ServerRule;

import brave.ScopedSpan;
import brave.Tracer.SpanInScope;
import brave.Tracing;
import brave.http.HttpTracing;
import brave.propagation.CurrentTraceContext;
import brave.propagation.StrictScopeDecorator;
import brave.sampler.Sampler;
import zipkin2.Span;
import zipkin2.reporter.Reporter;

public class BraveIntegrationTest {

    private static final ReporterImpl spanReporter = new ReporterImpl();

    private HelloService.Iface fooClient;
    private HelloService.Iface fooClientWithoutTracing;
    private HelloService.Iface timeoutClient;
    private HelloService.Iface timeoutClientClientTimesOut;
    private HelloService.AsyncIface barClient;
    private HelloService.AsyncIface quxClient;
    private HelloService.Iface zipClient;
    private HttpClient poolHttpClient;

    @Rule
    public final ServerRule server = new ServerRule() {
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
                        final ThriftCompletableFuture<String> f1 = new ThriftCompletableFuture<>();
                        final ThriftCompletableFuture<String> f2 = new ThriftCompletableFuture<>();
                        quxClient.hello(name, f1);
                        quxClient.hello(name, f2);
                        CompletableFuture.allOf(f1, f2).whenCompleteAsync((aVoid, throwable) -> {
                            resultHandler.onComplete(f1.join() + ", and " + f2.join());
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
                                        brave.Span span = Tracing.currentTracer().nextSpan().start();
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
                                                       ScopedSpan span = Tracing.currentTracer()
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
                                responseFuture.complete(HttpResponse.of(OK, MediaType.PLAIN_TEXT_UTF_8, "Lee"));
                            }, RequestContext.current().contextAwareExecutor());
                    return res;
                }
            }));

            sb.service("/timeout", decorate("service/timeout", THttpService.of(
                    // This service never calls the handler and will timeout.
                    (AsyncIface) (name, resultHandler) -> {
                    })));
        }
    };

    @Before
    public void setupClients() {
        fooClient = new ClientBuilder(server.uri(BINARY, "/foo"))
                .decorator(BraveClient.newDecorator(newTracing("client/foo")))
                .build(HelloService.Iface.class);
        zipClient = new ClientBuilder(server.uri(BINARY, "/zip"))
                .decorator(BraveClient.newDecorator(newTracing("client/zip")))
                .build(HelloService.Iface.class);
        fooClientWithoutTracing = Clients.newClient(server.uri(BINARY, "/foo"), HelloService.Iface.class);
        barClient = newClient("/bar");
        quxClient = newClient("/qux");
        poolHttpClient = HttpClient.of(server.uri("/"));
        timeoutClient = new ClientBuilder(server.uri(BINARY, "/timeout"))
                .decorator(BraveClient.newDecorator(newTracing("client/timeout")))
                .build(HelloService.Iface.class);
        timeoutClientClientTimesOut = new ClientBuilder(server.uri(BINARY, "/timeout"))
                .decorator(BraveClient.newDecorator(newTracing("client/timeout")))
                .responseTimeout(Duration.ofMillis(10))
                .build(HelloService.Iface.class);
    }

    @After
    public void tearDown() {
        Tracing.current().close();
    }

    @After
    public void shouldHaveNoExtraSpans() {
        assertThat(spanReporter.spans).isEmpty();
    }

    private static BraveService decorate(String name, Service<HttpRequest, HttpResponse> service) {
        return BraveService.newDecorator(newTracing(name)).apply(service);
    }

    private HelloService.AsyncIface newClient(String path) {
        return new ClientBuilder(server.uri(BINARY, path))
                .decorator(BraveClient.newDecorator(newTracing("client" + path)))
                .build(HelloService.AsyncIface.class);
    }

    private static HttpTracing newTracing(String name) {
        final CurrentTraceContext currentTraceContext =
                RequestContextCurrentTraceContext.builder()
                                                 .addScopeDecorator(StrictScopeDecorator.create())
                                                 .build();
        return HttpTracing.newBuilder(Tracing.newBuilder()
                                             .currentTraceContext(currentTraceContext)
                                             .localServiceName(name)
                                             .spanReporter(spanReporter)
                                             .sampler(Sampler.ALWAYS_SAMPLE)
                                             .build())
                          .clientParser(ArmeriaHttpClientParser.get())
                          .serverParser(ArmeriaHttpServerParser.get())
                          .build();
    }

    @Test(timeout = 10000)
    public void testServiceHasMultipleClientRequests() throws Exception {
        assertThat(zipClient.hello("Lee")).isEqualTo("Hello, Lee!, and Hello, Lee!");

        final Span[] spans = spanReporter.take(6);
        final String traceId = spans[0].traceId();
        assertThat(spans).allMatch(s -> s.traceId().equals(traceId));
    }

    @Test(timeout = 10000)
    public void testClientInitiatedTrace() throws Exception {
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

        // Check the span names.
        assertThat(spans).allMatch(s -> "hello".equals(s.name()));

        // Check wire times
        final long clientStartTime = clientFooSpan.timestampAsLong();
        final long clientWireSendTime = clientFooSpan.annotations().stream()
                                                     .filter(a -> a.value().equals("ws"))
                                                     .findFirst().get().timestamp();
        final long clientWireReceiveTime = clientFooSpan.annotations().stream()
                                                        .filter(a -> a.value().equals("wr"))
                                                        .findFirst().get().timestamp();
        final long clientEndTime = clientStartTime + clientFooSpan.durationAsLong();

        final long serverStartTime = serviceFooSpan.timestampAsLong();
        final long serverWireSendTime = serviceFooSpan.annotations().stream()
                                                      .filter(a -> a.value().equals("ws"))
                                                      .findFirst().get().timestamp();
        final long serverWireReceiveTime = serviceFooSpan.annotations().stream()
                                                         .filter(a -> a.value().equals("wr"))
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

    @Test(timeout = 10000)
    public void testServiceInitiatedTrace() throws Exception {
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

    @Test(timeout = 10000)
    public void testSpanInThreadPoolHasSameTraceId() throws Exception {
        poolHttpClient.get("pool").aggregate().get();
        final Span[] spans = spanReporter.take(5);
        assertThat(Arrays.stream(spans).map(Span::traceId).collect(toImmutableSet())).hasSize(1);
        assertThat(Arrays.stream(spans).map(Span::parentId)
                         .filter(Objects::nonNull)
                         .collect(toImmutableSet())).hasSize(1);
    }

    @Test(timeout = 10000)
    public void testServerTimesOut() throws Exception {
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

    @Test(timeout = 10000)
    public void testClientTimesOut() throws Exception {
        assertThatThrownBy(() -> timeoutClientClientTimesOut.hello("name"))
                .isInstanceOf(ResponseTimeoutException.class);
        final Span[] spans = spanReporter.take(2);

        final Span serverSpan = findSpan(spans, "service/timeout");
        final Span clientSpan = findSpan(spans, "client/timeout");

        // Client timed out, so no response data was ever sent from the server. There is no wire send in the
        // server and no wire receive in the client.
        assertThat(serverSpan.annotations()).hasSize(1);
        assertThat(clientSpan.annotations()).hasSize(1);
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

        Span[] take(int numSpans) throws InterruptedException {
            final List<Span> taken = new ArrayList<>();
            while (taken.size() < numSpans) {
                taken.add(spans.take());
            }

            // Reverse the collected spans to sort the spans by request time.
            Collections.reverse(taken);
            return taken.toArray(new Span[numSpans]);
        }
    }
}
