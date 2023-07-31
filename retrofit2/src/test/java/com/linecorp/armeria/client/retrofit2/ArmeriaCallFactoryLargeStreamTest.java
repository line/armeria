/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.client.retrofit2;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedInputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.reactivestreams.Subscription;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import okhttp3.ResponseBody;
import retrofit2.converter.jackson.JacksonConverterFactory;
import retrofit2.http.GET;
import retrofit2.http.Streaming;

class ArmeriaCallFactoryLargeStreamTest {

    interface Service {
        @Streaming
        @GET("/large-stream")
        CompletableFuture<ResponseBody> largeStream();
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/large-stream", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    return HttpResponse.of(s -> s.onSubscribe(new Subscription() {
                        int sentCount;

                        @Override
                        public void request(long n) {
                            for (int i = 0; i < n; i++) {
                                if (sentCount == 0) {
                                    s.onNext(ResponseHeaders.of(HttpStatus.OK));
                                } else {
                                    final byte[] data = new byte[10_000];
                                    for (int j = 0; j < data.length; j++) {
                                        data[j] = (byte) (((sentCount - 1) * data.length + j) % 256);
                                    }
                                    s.onNext(HttpData.wrap(data));
                                }
                                sentCount += 1;

                                // Sent more than 512MB
                                if ((sentCount - 1) * 10_000 > 1024 * 1024 * 512) {
                                    s.onComplete();
                                    return;
                                }
                            }
                        }

                        @Override
                        public void cancel() {
                        }
                    }));
                }
            });
            sb.requestTimeout(Duration.of(30, ChronoUnit.SECONDS));
        }
    };

    @Test
    void largeStream() throws Exception {
        final WebClient webClient =
                WebClient.builder(server.httpUri())
                         .maxResponseLength(Long.MAX_VALUE)
                         .responseTimeout(Duration.ofSeconds(30))
                         .build();
        final Service downloadService =
                ArmeriaRetrofit.builder(webClient)
                               .addConverterFactory(JacksonConverterFactory.create(OBJECT_MAPPER))
                               .streaming(true)
                               .build()
                               .create(Service.class);

        final ResponseBody responseBody = downloadService.largeStream().get();
        try (BufferedInputStream bufferedInputStream = new BufferedInputStream(responseBody.byteStream())) {
            int read;
            final byte[] data = new byte[4096];
            final MessageDigest md5 = MessageDigest.getInstance("MD5");
            while ((read = bufferedInputStream.read(data)) != -1) {
                md5.update(data, 0, read);
            }
            assertThat(String.format("%032X", new BigInteger(1, md5.digest())))
                    .isEqualTo("B0D8D7FEA8B1875D41648CF181FF8073");
        }
    }
}
