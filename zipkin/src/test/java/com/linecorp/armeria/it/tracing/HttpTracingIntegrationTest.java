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

package com.linecorp.armeria.it.tracing;

import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.BINARY;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.thrift.async.AsyncMethodCallback;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import com.github.kristofa.brave.Brave;
import com.github.kristofa.brave.Sampler;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.tracing.HttpTracingClient;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.common.tracing.HelloService;
import com.linecorp.armeria.common.tracing.HelloService.AsyncIface;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.server.tracing.HttpTracingService;
import com.linecorp.armeria.testing.server.ServerRule;

import zipkin.Span;
import zipkin.reporter.Reporter;

public class HttpTracingIntegrationTest {

    private static final ReporterImpl spanReporter = new ReporterImpl();

    private HelloService.Iface fooClient;
    private HelloService.Iface fooClientWithoutTracing;
    private HelloService.AsyncIface barClient;
    private HelloService.AsyncIface quxClient;

    @Rule
    public final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.serviceAt("/foo", decorate("service/foo", THttpService.of(
                    (AsyncIface) (name, resultHandler) ->
                            barClient.hello("Miss. " + name, new DelegatingCallback(resultHandler)))));

            sb.serviceAt("/bar", decorate("service/bar", THttpService.of(
                    (AsyncIface) (name, resultHandler) -> {
                        if (name.startsWith("Miss. ")) {
                            name = "Ms. " + name.substring(6);
                        }
                        quxClient.hello(name, new DelegatingCallback(resultHandler));
                    })));

            sb.serviceAt("/qux", decorate("service/qux", THttpService.of(
                    (AsyncIface) (name, resultHandler) -> resultHandler.onComplete("Hello, " + name + '!'))));
        }
    };

    @Before
    public void setupClients() {
        fooClient = new ClientBuilder(server.uri(BINARY, "/foo"))
                .decorator(HttpRequest.class, HttpResponse.class,
                           HttpTracingClient.newDecorator(newBrave("client/foo")))
                .build(HelloService.Iface.class);
        fooClientWithoutTracing = Clients.newClient(server.uri(BINARY, "/foo"), HelloService.Iface.class);
        barClient = newClient("/bar");
        quxClient = newClient("/qux");
    }

    @After
    public void shouldHaveNoExtraSpans() {
        assertThat(spanReporter.spans).isEmpty();
    }

    private static HttpTracingService decorate(String name, Service<HttpRequest, HttpResponse> service) {
        return HttpTracingService.newDecorator(newBrave(name)).apply(service);
    }

    private HelloService.AsyncIface newClient(String path) {
        return new ClientBuilder(server.uri(BINARY, path))
                .decorator(HttpRequest.class, HttpResponse.class,
                           HttpTracingClient.newDecorator(newBrave("client" + path)))
                .build(HelloService.AsyncIface.class);
    }

    private static Brave newBrave(String name) {
        return new Brave.Builder(name).reporter(spanReporter)
                                      .traceSampler(Sampler.ALWAYS_SAMPLE).build();
    }

    @Test(timeout = 10000)
    public void testClientInitiatedTrace() throws Exception {
        assertThat(fooClient.hello("Lee")).isEqualTo("Hello, Ms. Lee!");

        final Span[] spans = spanReporter.take(6);
        final long traceId = spans[0].traceId;
        assertThat(spans).allMatch(s -> s.traceId == traceId);

        // Find all spans.
        final Span clientFooSpan = findSpan(spans, "client/foo");
        final Span serviceFooSpan = findSpan(spans, "service/foo");
        final Span clientBarSpan = findSpan(spans, "client/bar");
        final Span serviceBarSpan = findSpan(spans, "service/bar");
        final Span clientQuxSpan = findSpan(spans, "client/qux");
        final Span serviceQuxSpan = findSpan(spans, "service/qux");

        // client/foo and service/foo should have no parents.
        assertThat(clientFooSpan.parentId).isNull();
        assertThat(serviceFooSpan.parentId).isNull();

        // client/foo and service/foo should have the ID values identical with their traceIds.
        assertThat(clientFooSpan.id).isEqualTo(traceId);
        assertThat(serviceFooSpan.id).isEqualTo(traceId);

        // The spans that do not cross the network boundary should have the same ID.
        assertThat(clientFooSpan.id).isEqualTo(serviceFooSpan.id);
        assertThat(clientBarSpan.id).isEqualTo(serviceBarSpan.id);
        assertThat(clientQuxSpan.id).isEqualTo(serviceQuxSpan.id);

        // Check the parentIds.
        assertThat(clientBarSpan.parentId).isEqualTo(clientFooSpan.id);
        assertThat(serviceBarSpan.parentId).isEqualTo(clientFooSpan.id);
        assertThat(clientQuxSpan.parentId).isEqualTo(clientBarSpan.id);
        assertThat(serviceQuxSpan.parentId).isEqualTo(clientBarSpan.id);

        // Check the service names.
        assertThat(clientFooSpan.annotations).allMatch(a -> "client/foo".equals(a.endpoint.serviceName));
        assertThat(serviceFooSpan.annotations).allMatch(a -> "service/foo".equals(a.endpoint.serviceName));
        assertThat(clientBarSpan.annotations).allMatch(a -> "client/bar".equals(a.endpoint.serviceName));
        assertThat(serviceBarSpan.annotations).allMatch(a -> "service/bar".equals(a.endpoint.serviceName));
        assertThat(clientQuxSpan.annotations).allMatch(a -> "client/qux".equals(a.endpoint.serviceName));
        assertThat(serviceQuxSpan.annotations).allMatch(a -> "service/qux".equals(a.endpoint.serviceName));

        // Check the span names.
        assertThat(spans).allMatch(s -> "hello".equals(s.name));
    }

    @Test(timeout = 10000)
    public void testServiceInitiatedTrace() throws Exception {
        assertThat(fooClientWithoutTracing.hello("Lee")).isEqualTo("Hello, Ms. Lee!");

        final Span[] spans = spanReporter.take(5);
        final long traceId = spans[0].traceId;
        assertThat(spans).allMatch(s -> s.traceId == traceId);

        // Find all spans.
        final Span serviceFooSpan = findSpan(spans, "service/foo");
        final Span clientBarSpan = findSpan(spans, "client/bar");
        final Span serviceBarSpan = findSpan(spans, "service/bar");
        final Span clientQuxSpan = findSpan(spans, "client/qux");
        final Span serviceQuxSpan = findSpan(spans, "service/qux");

        // service/foo should have no parent.
        assertThat(serviceFooSpan.parentId).isNull();

        // service/foo should have the ID value identical with its traceId.
        assertThat(serviceFooSpan.id).isEqualTo(traceId);

        // The spans that do not cross the network boundary should have the same ID.
        assertThat(clientBarSpan.id).isEqualTo(serviceBarSpan.id);
        assertThat(clientQuxSpan.id).isEqualTo(serviceQuxSpan.id);

        // Check the parentIds
        assertThat(clientBarSpan.parentId).isEqualTo(serviceFooSpan.id);
        assertThat(serviceBarSpan.parentId).isEqualTo(serviceFooSpan.id);
        assertThat(clientQuxSpan.parentId).isEqualTo(serviceBarSpan.id);
        assertThat(serviceQuxSpan.parentId).isEqualTo(serviceBarSpan.id);

        // Check the service names.
        assertThat(serviceFooSpan.annotations).allMatch(a -> "service/foo".equals(a.endpoint.serviceName));
        assertThat(clientBarSpan.annotations).allMatch(a -> "client/bar".equals(a.endpoint.serviceName));
        assertThat(serviceBarSpan.annotations).allMatch(a -> "service/bar".equals(a.endpoint.serviceName));
        assertThat(clientQuxSpan.annotations).allMatch(a -> "client/qux".equals(a.endpoint.serviceName));
        assertThat(serviceQuxSpan.annotations).allMatch(a -> "service/qux".equals(a.endpoint.serviceName));

        // Check the span names.
        assertThat(spans).allMatch(s -> "hello".equals(s.name));
    }

    private static Span findSpan(Span[] spans, String serviceName) {
        return Arrays.stream(spans)
                     .filter(s -> s.serviceNames().contains(serviceName))
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
