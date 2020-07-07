/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.armeria.thrift;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.base.Strings;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.thrift.services.HelloService;
import com.linecorp.armeria.thrift.services.HelloService.AsyncIface;

/**
 * Compare performance of pooled vs unpooled {@link Service} response buffers.
 *
 * <p>20170511 Macbook Pro 2016 2.9 GHz Intel Core i5
 * <pre>
 * # Run complete. Total time: 00:19:32
 *
 * Benchmark                                Mode  Cnt    Score   Error  Units
 * PooledResponseBufferBenchmark.pooled    thrpt  200  562.800 ± 4.447  ops/s
 * PooledResponseBufferBenchmark.unpooled  thrpt  200  521.167 ± 4.445  ops/s
 * </pre>
 */
@State(Scope.Benchmark)
public class PooledResponseBufferBenchmark {

    private static final int RESPONSE_SIZE = 500 * 1024;
    private static final String RESPONSE = Strings.repeat("a", RESPONSE_SIZE);

    private static final class PooledDecoratingService extends SimpleDecoratingHttpService {

        private PooledDecoratingService(HttpService delegate) {
            super(delegate);
        }

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            final HttpResponse res = unwrap().serve(ctx, req);
            final HttpResponseWriter decorated = HttpResponse.streaming();
            res.subscribe(new Subscriber<HttpObject>() {
                @Override
                public void onSubscribe(Subscription s) {
                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(HttpObject httpObject) {
                    decorated.write(httpObject);
                }

                @Override
                public void onError(Throwable t) {
                    decorated.close(t);
                }

                @Override
                public void onComplete() {
                    decorated.close();
                }
            }, SubscriptionOption.WITH_POOLED_OBJECTS);
            return decorated;
        }
    }

    private static final class UnpooledDecoratingService extends SimpleDecoratingHttpService {

        private UnpooledDecoratingService(HttpService delegate) {
            super(delegate);
        }

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            final HttpResponse res = unwrap().serve(ctx, req);
            final HttpResponseWriter decorated = HttpResponse.streaming();
            res.subscribe(new Subscriber<HttpObject>() {
                @Override
                public void onSubscribe(Subscription s) {
                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(HttpObject httpObject) {
                    decorated.write(httpObject);
                }

                @Override
                public void onError(Throwable t) {
                    decorated.close(t);
                }

                @Override
                public void onComplete() {
                    decorated.close();
                }
            });
            return decorated;
        }
    }

    private Server server;
    private HelloService.Iface pooledClient;
    private HelloService.Iface unpooledClient;

    @Setup
    public void startServer() throws Exception {
        final ServerBuilder sb =
                Server.builder()
                      .service("/a", THttpService.of((AsyncIface) (name, cb) -> cb.onComplete(RESPONSE))
                                                 .decorate(PooledDecoratingService::new))
                      .service("/b", THttpService.of((AsyncIface) (name, cb) -> cb.onComplete(RESPONSE))
                                                 .decorate(UnpooledDecoratingService::new));
        server = sb.build();
        server.start().join();

        final int httpPort = server.activeLocalPort(SessionProtocol.HTTP);
        pooledClient = Clients.newClient("tbinary+http://127.0.0.1:" + httpPort + "/a",
                                         HelloService.Iface.class);
        unpooledClient = Clients.newClient("tbinary+http://127.0.0.1:" + httpPort + "/b",
                                           HelloService.Iface.class);
    }

    @TearDown
    public void stopServer() throws Exception {
        server.stop().join();
    }

    @Benchmark
    public void pooled(Blackhole bh) throws Exception {
        bh.consume(pooledClient.hello("hello"));
    }

    @Benchmark
    public void unpooled(Blackhole bh) throws Exception {
        bh.consume(unpooledClient.hello("hello"));
    }
}
