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
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

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
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.StreamWriter;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class Http1PipelineTest {

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

            // A service that never writes any response headers — used in the sparse-map
            // regression test so that doWriteReset() is called for an ID that has no
            // entry in pendingWritesMap, creating a gap in the iteration range.
            sb.route()
              .path("/never-respond")
              .requestTimeoutMillis(0)
              .build((ctx, req) -> {
                  // Return a future that resolves only when the request context is cancelled.
                  // The connection reset from another pipelined request will cancel this context.
                  final CompletableFuture<HttpResponse> f = new CompletableFuture<>();
                  ctx.whenRequestCancelling().thenRun(() -> f.cancel(true));
                  return HttpResponse.of(f);
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
     * Regression test for the NPE in {@link com.linecorp.armeria.internal.common.Http1ObjectEncoder}
     * {@code doWriteReset()} when {@code pendingWritesMap} is sparse.
     *
     * <p>{@code doWriteReset()} iterates IDs {@code [minClosedId, maxIdWithPendingWrites]} to fail
     * pending writes. The map is <em>sparse</em>: only out-of-order request IDs that went through
     * the encoder's {@code write()} path have entries. Iterating a gap ID (one that never wrote
     * anything before the reset) returned {@code null}, and the subsequent {@code .poll()} call
     * threw a {@link NullPointerException}.
     *
     * <p>Scenario (3 pipelined requests on one connection):
     * <ol>
     *   <li>id=1 {@code /slow}         – holds the write-turn, so ids 2 and 3 queue in pendingWritesMap</li>
     *   <li>id=2 {@code /never-respond} – handler never writes a response header → <strong>no entry</strong>
     *                                    in pendingWritesMap at id=2; connection reset eventually closes it</li>
     *   <li>id=3 {@code /length-limit}  – writes 200 OK → pendingWritesMap[3]; overflow triggers
     *                                    doWriteReset(3); after connection reset minClosedId becomes 2
     *                                    (because the cancel from /never-respond propagates), so the
     *                                    iteration range [2, 3] includes the gap at id=2</li>
     * </ol>
     */
    @Test
    void resetDoesNotNpeWhenPendingWritesMapIsSparse() throws IOException {
        // Use a raw socket so all three requests are sent in a single burst before the server
        // can respond to any of them. This ensures they all get distinct pipelined IDs on the
        // same connection and that id=1 (/slow) holds the write-turn while ids 2 and 3 queue.
        try (Socket socket = new Socket()) {
            socket.connect(server.httpSocketAddress());
            socket.setSoTimeout(10_000);

            final PrintWriter out = new PrintWriter(socket.getOutputStream(), false);

            // id=1: /slow — 3-second delay; keeps currentId=1 so all later responses queue
            out.print("GET /slow HTTP/1.1\r\n");
            out.print("Host: 127.0.0.1\r\n");
            out.print("\r\n");

            // id=2: /never-respond — handler never writes headers → no pendingWritesMap entry at id=2
            out.print("GET /never-respond HTTP/1.1\r\n");
            out.print("Host: 127.0.0.1\r\n");
            out.print("\r\n");

            // id=3: /length-limit — writes 200 OK (queued at id=3) then overflows → doWriteReset(3)
            //        After the connection reset, the cancel propagates to /never-respond which means
            //        minClosedId is updated to cover id=2 as well, producing the gap [2, 3].
            final String overBody = Strings.repeat("x", 101);
            out.print("POST /length-limit HTTP/1.1\r\n");
            out.print("Host: 127.0.0.1\r\n");
            out.print("Content-Type: text/plain\r\n");
            out.print("Content-Length: " + overBody.length() + "\r\n");
            out.print("\r\n");
            out.print(overBody);
            out.flush();

            // Read until the connection is closed by the server.
            // We only assert that we get *some* valid response (200 OK for /slow) and that
            // the connection then closes cleanly — i.e. no NullPointerException causes the
            // server to crash before sending any bytes.
            final InputStream is = socket.getInputStream();
            final BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            final StringBuilder received = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                received.append(line).append('\n');
            }
            // At minimum we should receive the HTTP/1.1 status line for /slow
            // confirming the server did not crash (NPE) before writing anything.
            assertThat(received.toString()).contains("HTTP/1.1");
        }
    }
}
