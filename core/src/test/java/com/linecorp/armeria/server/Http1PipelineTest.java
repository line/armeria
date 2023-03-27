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
}
