/*
 * Copyright 2022 LINE Corporation
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.base.Strings;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.ResponseAs;
import com.linecorp.armeria.client.RestClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.SplitHttpResponse;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.StreamWriter;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.testing.server.ServiceRequestContextCaptor;

class Http1PipelineTest {

    private static final CompletableFuture<Void> emptyResponseTrigger = new CompletableFuture<>();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/slow", (ctx, req) -> {
                return HttpResponse.delayed(HttpResponse.of("slow"), Duration.ofSeconds(3));
            });

            sb.service("/fast", (ctx, req) -> {
                return HttpResponse.of("fast");
            });

            sb.route()
              .path("/length-limit")
              .requestTimeoutMillis(0)
              .maxRequestLength(100)
              .build((ctx, req) -> {
                  final HttpResponseWriter writer = HttpResponse.streaming();
                  writer.write(ResponseHeaders.of(200));
                  req.aggregate().thenRun(() -> {
                      writer.write(HttpData.ofUtf8("Hello!"));
                      writer.close();
                  });
                  return writer;
              });

            // A service that completes its response stream without writing any response headers when
            // 'emptyResponseTrigger' is completed, so that the stream is reset via 'writeReset()'
            // while 'pendingWritesMap' has no entry for its request ID.
            sb.service("/empty", (ctx, req) -> {
                final HttpResponseWriter writer = HttpResponse.streaming();
                emptyResponseTrigger.thenRunAsync(writer::close, ctx.eventLoop());
                return writer;
            });
        }
    };

    @Test
    void httpPipelining() throws InterruptedException {
        try (ClientFactory factory = ClientFactory.builder()
                                                  .useHttp1Pipelining(true)
                                                  .build()) {
            final RestClient client = RestClient.builder(server.uri(SessionProtocol.H1C))
                                                .factory(factory)
                                                .build();

            final CompletableFuture<ResponseEntity<String>> response1;
            try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                response1 = client.get("/slow").execute(ResponseAs.string());
                captor.get().log().whenRequestComplete().join();
            }

            // Start the next request after the first request has completed to reuse the connection pool.
            final CompletableFuture<ResponseEntity<String>> response2 =
                    client.get("/fast").execute(ResponseAs.string());

            assertThat(response1.join().content()).isEqualTo("slow");
            assertThat(response2.join().content()).isEqualTo("fast");
        }
    }

    @Test
    void shouldResetIfTwoHeadersAreWritten() throws InterruptedException {
        try (ClientFactory factory = ClientFactory.builder()
                                                  .useHttp1Pipelining(true)
                                                  .build()) {
            final WebClient client = WebClient.builder(server.uri(SessionProtocol.H1C))
                                              .factory(factory)
                                              .build();

            final StreamWriter<HttpData> stream = StreamMessage.streaming();
            final HttpResponse response =
                    client.prepare()
                          .post("/length-limit")
                          .content(MediaType.PLAIN_TEXT, stream)
                          .execute();
            final SplitHttpResponse splitHttpResponse = response.split();
            final ResponseHeaders headers = splitHttpResponse.headers().join();
            assertThat(headers.status()).isEqualTo(HttpStatus.OK);

            // Trigger ContentTooLargeException to return "413 Request Entity Too Large" status.
            stream.write(HttpData.ofUtf8(Strings.repeat("a", 101)));
            stream.close();

            // The connection should be reset as the seconds headers including "413 Request Entity Too Large"
            // is about to be written.
            assertThatThrownBy(() -> {
                splitHttpResponse.body().collect().join();
            }).isInstanceOf(CompletionException.class)
              .hasCauseInstanceOf(ClosedSessionException.class);
        }
    }

    /**
     * Regression test for the {@link NullPointerException} that was thrown by
     * {@code Http1ObjectEncoder.doWriteReset()} when {@code pendingWritesMap} is sparse.
     *
     * <p>{@code doWriteReset()} iterates over the IDs in {@code [minClosedId, maxIdWithPendingWrites]}
     * to fail the pending writes, but the map contains entries only for the IDs whose responses were
     * queued out of order. The following scenario makes the range contain an ID without an entry:
     * <ol>
     *   <li>id=1 {@code /slow} - holds the write turn so that the other responses are queued.</li>
     *   <li>id=2 {@code /empty} - completes its response stream without writing any headers, which
     *       resets the stream via {@code writeReset(2)} while {@code pendingWritesMap} has no entry
     *       for id=2.</li>
     *   <li>id=3 {@code /fast} - responds immediately so that its response is queued at id=3.</li>
     * </ol>
     * {@code doWriteReset(2)} then iterates over {@code [2, 3]} and must skip id=2.
     */
    @Test
    void resetShouldNotThrowNpeWhenPendingWritesMapIsSparse() throws Exception {
        // Use a raw socket to pipeline the three requests on a single connection in a single burst.
        try (Socket socket = new Socket()) {
            socket.connect(server.httpSocketAddress());
            socket.setSoTimeout(10_000);

            final PrintWriter out = new PrintWriter(socket.getOutputStream(), false);
            out.print("GET /slow HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n");
            out.print("GET /empty HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n");
            out.print("GET /fast HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n");
            out.flush();

            final ServiceRequestContextCaptor captor = server.requestContextCaptor();
            final ServiceRequestContext slowCtx = captor.take();
            final ServiceRequestContext emptyCtx = captor.take();
            final ServiceRequestContext fastCtx = captor.take();
            assertThat(slowCtx.path()).isEqualTo("/slow");
            assertThat(emptyCtx.path()).isEqualTo("/empty");
            assertThat(fastCtx.path()).isEqualTo("/fast");

            // Wait until the '/fast' response is queued in 'pendingWritesMap' and then let '/empty'
            // complete its response stream without headers, which triggers 'doWriteReset(2)'.
            fastCtx.log().whenAvailable(RequestLogProperty.RESPONSE_HEADERS).get(10, TimeUnit.SECONDS);
            emptyResponseTrigger.complete(null);

            // The reset must fail the queued '/fast' response with a ClosedSessionException.
            // Before the fix, the NPE aborted the iteration so that the queued response was
            // never failed and this log never completed.
            final RequestLog fastLog = fastCtx.log().whenComplete().get(10, TimeUnit.SECONDS);
            assertThat(fastLog.responseCause()).isInstanceOf(ClosedSessionException.class);

            // The failed write closes the connection cleanly because the queued responses cannot
            // be written to an HTTP/1 connection anymore.
            final BufferedReader reader =
                    new BufferedReader(new InputStreamReader(socket.getInputStream()));
            assertThat(reader.readLine()).isNull();
        }
    }
}
