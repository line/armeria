/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class RequestAbortLeakTest {

    private static final ReferenceQueue<ServiceRequestContext> refQueue = new ReferenceQueue<>();

    private static final Set<Object> weakRefSet = Collections.synchronizedSet(new HashSet<>());

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {

            sb.route()
              .path("/server-auto-abort")
              .requestAutoAbortDelayMillis(1_000)
              .build((ctx, req) -> {
                  weakRefSet.add(new WeakReference<>(ctx, refQueue));
                  return HttpResponse.of(200);
              });

            sb.route()
              .path("/server-abort")
              .requestAutoAbortDelayMillis(-1)
              .build((ctx, req) -> {
                  weakRefSet.add(new WeakReference<>(ctx, refQueue));
                  ctx.eventLoop().schedule(() -> req.abort(), 1, TimeUnit.SECONDS);
                  return HttpResponse.of(200);
              });

            sb.route()
              .path("/content-length")
              .maxRequestLength(5)
              .build((ctx, req) -> {
                  weakRefSet.add(new WeakReference<>(ctx, refQueue));
                  return HttpResponse.of(200);
              });
        }

        @Override
        protected boolean shouldCapture(ServiceRequestContext ctx) {
            return false;
        }
    };

    @BeforeEach
    void beforeEach() {
        assertThat(refQueue.poll()).isNull();
        weakRefSet.clear();
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = {"H1C", "H2C"})
    void serverFirstAborts(SessionProtocol protocol) throws Exception {
        final WebClient client = WebClient.builder(server.uri(protocol))
                                          .requestAutoAbortDelayMillis(-1)
                                          .build();
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/server-abort");
        final HttpRequestWriter writer = HttpRequest.streaming(headers);
        final HttpResponse res = client.execute(writer);
        assertThat(res.aggregate().join().status().code()).isEqualTo(200);

        if (!protocol.isMultiplex()) {
            // close the request to ensure Http1RequestDecoder releases the reference to req
            writer.close();
        }

        await().pollInterval(1, TimeUnit.SECONDS)
               .untilAsserted(() -> {
                   System.gc();
                   final Reference<? extends ServiceRequestContext> ref = refQueue.poll();
                   assertThat(ref).isNotNull();
                   assertThat(ref.get()).isNull();
               });
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = {"H1C", "H2C"})
    void serverAutoAbort(SessionProtocol protocol) throws Exception {
        final WebClient client = WebClient.builder(server.uri(protocol))
                                          .requestAutoAbortDelayMillis(-1)
                                          .build();
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/server-auto-abort");
        final HttpRequestWriter writer = HttpRequest.streaming(headers);
        final HttpResponse res = client.execute(writer);
        assertThat(res.aggregate().join().status().code()).isEqualTo(200);

        if (!protocol.isMultiplex()) {
            // close the request to ensure Http1RequestDecoder releases the reference to req
            writer.close();
        }

        await().pollInterval(1, TimeUnit.SECONDS)
               .untilAsserted(() -> {
                   System.gc();
                   final Reference<? extends ServiceRequestContext> ref = refQueue.poll();
                   assertThat(ref).isNotNull();
                   assertThat(ref.get()).isNull();
               });
    }

    @SuppressWarnings("CheckReturnValue")
    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = {"H1C", "H2C"})
    void maxContentLength(SessionProtocol protocol) throws Exception {
        final WebClient client = WebClient.builder(server.uri(protocol))
                                          .requestAutoAbortDelayMillis(-1)
                                          .build();
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/content-length");
        final HttpRequestWriter writer = HttpRequest.streaming(headers);
        final HttpResponse res = client.execute(writer);
        assertThat(res.aggregate().join().status().code()).isEqualTo(200);

        await().pollInterval(1, TimeUnit.SECONDS)
               .untilAsserted(() -> {
                   writer.tryWrite(HttpData.ofAscii("1234567890"));
                   System.gc();
                   final Reference<? extends ServiceRequestContext> ref = refQueue.poll();
                   assertThat(ref).isNotNull();
                   assertThat(ref.get()).isNull();
               });
    }
}
