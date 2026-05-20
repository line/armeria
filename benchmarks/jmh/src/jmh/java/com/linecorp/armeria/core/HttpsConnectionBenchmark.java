/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
package com.linecorp.armeria.core;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicLong;

import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ConnectionPoolListenerAdapter;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.util.SelfSignedCertificate;
import com.linecorp.armeria.server.Server;

import io.netty.util.AttributeMap;

/**
 * Measures per-connection TLS overhead. Each JMH thread sends one HTTP/1.1 request
 * with {@code Connection: close} per iteration, forcing a fresh TLS handshake every time.
 */
@State(Scope.Benchmark)
public class HttpsConnectionBenchmark {

    private static final RequestHeaders HEADERS =
            RequestHeaders.builder(HttpMethod.GET, "/")
                          .add(HttpHeaderNames.CONNECTION, "close")
                          .build();

    final AtomicLong connectionsOpened = new AtomicLong();

    @Nullable
    private Server server;
    @Nullable
    private ClientFactory clientFactory;
    @Nullable
    private WebClient client;

    @AuxCounters(AuxCounters.Type.EVENTS)
    @State(Scope.Thread)
    public static class Counters {
        private long success;
        private long failure;
        private long connectionsOpened;

        public long success() {
            return success;
        }

        public long failure() {
            return failure;
        }

        public long connectionsOpened() {
            return connectionsOpened;
        }

        @Setup(Level.Iteration)
        public void reset(HttpsConnectionBenchmark bench) {
            success = 0;
            failure = 0;
            connectionsOpened = 0;
        }

        @TearDown(Level.Iteration)
        public void snapshot(HttpsConnectionBenchmark bench) {
            connectionsOpened = bench.connectionsOpened.getAndSet(0);
        }
    }

    @Setup(Level.Trial)
    public void startServer() throws Exception {
        final SelfSignedCertificate ssc = new SelfSignedCertificate();

        server = Server.builder()
                       .https(0)
                       .tls(ssc.certificate(), ssc.privateKey())
                       .service("/", (ctx, req) -> HttpResponse.of(200))
                       .build();
        server.start().join();

        clientFactory = ClientFactory.builder()
                                     .tlsNoVerify()
                                     .connectionPoolListener(new ConnectionPoolListenerAdapter() {
                                         @Override
                                         public void connectionOpen(
                                                 SessionProtocol protocol,
                                                 InetSocketAddress remoteAddr,
                                                 InetSocketAddress localAddr,
                                                 AttributeMap attrs) {
                                             connectionsOpened.incrementAndGet();
                                         }
                                     })
                                     .build();
        client = WebClient.builder("h1://127.0.0.1:" + server.activeLocalPort())
                          .factory(clientFactory)
                          .build();
    }

    @TearDown(Level.Trial)
    public void stopServer() {
        if (clientFactory != null) {
            clientFactory.closeAsync().join();
        }
        if (server != null) {
            server.stop().join();
        }
    }

    @Benchmark
    @Threads(200)
    public void tlsConnect(Counters counters) {
        assert client != null;
        try {
            final int code = client.execute(HEADERS).aggregate().join().status().code();
            if (code == 200) {
                counters.success++;
            } else {
                counters.failure++;
            }
        } catch (Exception e) {
            counters.failure++;
        }
    }
}
