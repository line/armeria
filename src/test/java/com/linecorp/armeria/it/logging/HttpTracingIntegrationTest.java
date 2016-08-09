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

package com.linecorp.armeria.it.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.thrift.async.AsyncMethodCallback;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.github.kristofa.brave.Brave;
import com.github.kristofa.brave.EmptySpanCollector;
import com.github.kristofa.brave.Sampler;
import com.twitter.zipkin.gen.Span;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.tracing.HttpTracingClient;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.server.AbstractServerTest;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.server.tracing.HttpTracingService;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.service.test.thrift.main.HelloService.AsyncIface;

public class HttpTracingIntegrationTest extends AbstractServerTest {

    private static final SpanCollectorImpl spanCollector = new SpanCollectorImpl();

    private HelloService.Iface fooClient;
    private HelloService.Iface fooClientWithoutTracing;
    private HelloService.AsyncIface barClient;
    private HelloService.AsyncIface quxClient;

    @Override
    protected void configureServer(ServerBuilder sb) throws Exception {
        sb.port(0, SessionProtocol.HTTP);

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

    @Before
    public void setupClients() {
        fooClient = new ClientBuilder("tbinary+" + uri("/foo"))
                .decorator(HttpRequest.class, HttpResponse.class,
                           HttpTracingClient.newDecorator(newBrave("client/foo")))
                .build(HelloService.Iface.class);
        fooClientWithoutTracing = Clients.newClient("tbinary+" + uri("/foo"), HelloService.Iface.class);
        barClient = newClient("/bar");
        quxClient = newClient("/qux");
    }

    @After
    public void shouldHaveNoExtraSpans() {
        assertThat(spanCollector.spans).isEmpty();
    }

    private static HttpTracingService decorate(String name, Service<HttpRequest, HttpResponse> service) {
        return HttpTracingService.newDecorator(newBrave(name)).apply(service);
    }

    private static HelloService.AsyncIface newClient(String path) {
        return new ClientBuilder("tbinary+" + uri(path))
                .decorator(HttpRequest.class, HttpResponse.class,
                           HttpTracingClient.newDecorator(newBrave("client" + path)))
                .build(HelloService.AsyncIface.class);
    }

    private static Brave newBrave(String name) {
        return new Brave.Builder(name).spanCollector(spanCollector)
                                      .traceSampler(Sampler.ALWAYS_SAMPLE).build();
    }

    @Test(timeout = 10000)
    public void testClientInitiatedTrace() throws Exception {
        assertThat(fooClient.hello("Lee")).isEqualTo("Hello, Ms. Lee!");

        final Span[] spans = spanCollector.take(6);
        final long traceId = spans[0].getTrace_id();
        assertThat(spans).allMatch(s -> s.getTrace_id() == traceId);

        // client/foo and service/foo should have no parents.
        assertThat(spans[0].getParent_id()).isNull();
        assertThat(spans[1].getParent_id()).isNull();

        // client/foo and service/foo should have the ID values identical with their traceIds.
        assertThat(spans[0].getId()).isEqualTo(traceId);
        assertThat(spans[1].getId()).isEqualTo(traceId);

        // The spans that do no cross the network boundary should have the same ID.
        for (int i = 0; i < spans.length; i += 2) {
            assertThat(spans[i].getId()).isEqualTo(spans[i + 1].getId());
        }

        // Check the parentIds.
        for (int i = 2; i < spans.length; i += 2) {
            final long expectedParentId = spans[i - 2].getId();
            assertThat(spans[i].getParent_id()).isEqualTo(expectedParentId);
            assertThat(spans[i + 1].getParent_id()).isEqualTo(expectedParentId);
        }

        // Check the service names.
        assertThat(spans[0].getAnnotations()).allMatch(a -> "client/foo".equals(a.host.service_name));
        assertThat(spans[1].getAnnotations()).allMatch(a -> "service/foo".equals(a.host.service_name));
        assertThat(spans[2].getAnnotations()).allMatch(a -> "client/bar".equals(a.host.service_name));
        assertThat(spans[3].getAnnotations()).allMatch(a -> "service/bar".equals(a.host.service_name));
        assertThat(spans[4].getAnnotations()).allMatch(a -> "client/qux".equals(a.host.service_name));
        assertThat(spans[5].getAnnotations()).allMatch(a -> "service/qux".equals(a.host.service_name));

        // Check the span names.
        assertThat(spans).allMatch(s -> "hello".equals(s.getName()));
    }

    @Test(timeout = 10000)
    public void testServiceInitiatedTrace() throws Exception {
        assertThat(fooClientWithoutTracing.hello("Lee")).isEqualTo("Hello, Ms. Lee!");

        final Span[] spans = spanCollector.take(5);
        final long traceId = spans[0].getTrace_id();
        assertThat(spans).allMatch(s -> s.getTrace_id() == traceId);

        // service/foo should have no parent.
        assertThat(spans[0].getParent_id()).isNull();

        // service/foo should have the ID value identical with its traceId.
        assertThat(spans[0].getId()).isEqualTo(traceId);

        // The spans that do no cross the network boundary should have the same ID.
        for (int i = 1; i < spans.length; i += 2) {
            assertThat(spans[i].getId()).isEqualTo(spans[i + 1].getId());
        }

        // Check the parentIds
        for (int i = 1; i < spans.length; i += 2) {
            final long expectedParentId = spans[i - 1].getId();
            assertThat(spans[i].getParent_id()).isEqualTo(expectedParentId);
            assertThat(spans[i + 1].getParent_id()).isEqualTo(expectedParentId);
        }

        // Check the service names.
        assertThat(spans[0].getAnnotations()).allMatch(a -> "service/foo".equals(a.host.service_name));
        assertThat(spans[1].getAnnotations()).allMatch(a -> "client/bar".equals(a.host.service_name));
        assertThat(spans[2].getAnnotations()).allMatch(a -> "service/bar".equals(a.host.service_name));
        assertThat(spans[3].getAnnotations()).allMatch(a -> "client/qux".equals(a.host.service_name));
        assertThat(spans[4].getAnnotations()).allMatch(a -> "service/qux".equals(a.host.service_name));

        // Check the span names.
        assertThat(spans).allMatch(s -> "hello".equals(s.getName()));
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

    private static class SpanCollectorImpl extends EmptySpanCollector {

        private final BlockingQueue<Span> spans = new LinkedBlockingQueue<>();

        @Override
        public void collect(Span span) {
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
