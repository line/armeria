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
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletableFuture;

import org.junit.ClassRule;
import org.junit.Test;
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
import com.linecorp.armeria.testing.junit4.server.ServerRule;

import okhttp3.ResponseBody;
import retrofit2.converter.jackson.JacksonConverterFactory;
import retrofit2.http.GET;
import retrofit2.http.Streaming;

public class ArmeriaCallFactoryLargeStreamTest {

    interface Service {
        @Streaming
        @GET("/large-stream")
        CompletableFuture<ResponseBody> largeStream();
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @ClassRule
    public static final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/large-stream", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    return HttpResponse.of(s -> s.onSubscribe(new Subscription() {
                        int count;

                        @Override
                        public void request(long n) {
                            for (int i = 0; i < n; i++) {
                                if (count == 0) {
                                    s.onNext(ResponseHeaders.of(HttpStatus.OK));
                                } else {
                                    s.onNext(HttpData.wrap(new byte[1024]));
                                }
                            }
                            count += n;
                            // 10MB
                            if (count > 1024 * 10) {
                                s.onComplete();
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

    @Test(timeout = 30 * 1000L)
    public void largeStream() throws Exception {
        final WebClient webClient =
                WebClient.builder(server.httpUri())
                         .maxResponseLength(Long.MAX_VALUE)
                         .responseTimeout(Duration.ofSeconds(30))
                         .build();
        final Service downloadService =
                ArmeriaRetrofit.builder(webClient)
                               .addConverterFactory(JacksonConverterFactory.create(OBJECT_MAPPER))
                               .build()
                               .create(Service.class);

        final ResponseBody responseBody = downloadService.largeStream().get();
        try (BufferedInputStream bufferedInputStream = new BufferedInputStream(responseBody.byteStream())) {
            long sum = 0;
            int read;
            while ((read = bufferedInputStream.read(new byte[4096])) != -1) {
                sum += read;
            }
            assertThat(sum).isEqualTo(1024 * 1024 * 10L);
        }
    }
}
