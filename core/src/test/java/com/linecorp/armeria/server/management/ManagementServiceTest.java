/*
 * Copyright 2021 LINE Corporation
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
 * under the License
 */

package com.linecorp.armeria.server.management;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.primitives.Bytes;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ContentDisposition;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SplitHttpResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import reactor.core.publisher.Flux;

class ManagementServiceTest {
    private static final ObjectMapper mapper = new ObjectMapper();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.serviceUnder("/internal/management", ManagementService.of());
        }
    };

    @Test
    void threadDump() throws Exception {
        final HttpRequest request = HttpRequest.of(HttpMethod.GET, "/");
        final ServiceRequestContext ctx = ServiceRequestContext.of(request);
        final AggregatedHttpResponse response =
                ThreadDumpService.INSTANCE.serve(ctx, request).aggregate().join();
        assertThat(response.contentType()).isEqualTo(MediaType.PLAIN_TEXT);
        assertThat(response.contentUtf8()).contains(Thread.currentThread().getName());
    }

    @Test
    void threadDumpWithJson() throws Exception {
        final HttpRequest request = HttpRequest.builder()
                                               .get("/")
                                               .header(HttpHeaderNames.ACCEPT, MediaType.JSON)
                                               .build();
        final ServiceRequestContext ctx = ServiceRequestContext.of(request);
        final AggregatedHttpResponse response =
                ThreadDumpService.INSTANCE.serve(ctx, request).aggregate().join();

        assertThat(response.contentType()).isEqualTo(MediaType.JSON);
        final String content = response.contentUtf8();
        assertThat(content).contains(Thread.currentThread().getName());
        assertThat(mapper.readTree(content).isArray()).isTrue();
    }

    @Test
    void heapDump() throws InterruptedException {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .responseTimeout(Duration.ofSeconds(45)) // Heap dump can take time.
                                          .build();
        final HttpResponse response = client.get("/internal/management/jvm/heapdump");
        final SplitHttpResponse splitHttpResponse = response.split();
        final ResponseHeaders headers = splitHttpResponse.headers().join();
        final ContentDisposition disposition = headers.contentDisposition();

        assertThat(disposition).isNotNull();
        final String filename = disposition.filename();
        assertThat(filename).startsWith("heapdump_pid");
        assertThat(filename).endsWith(".hprof");

        final byte[] fileHeader = "JAVA PROFILE".getBytes(StandardCharsets.UTF_8);
        final AtomicInteger counter = new AtomicInteger();
        final byte[] actual = Flux.from(splitHttpResponse.body())
                                  .map(HttpData::array)
                                  .takeUntil(bytes -> fileHeader.length <= counter.addAndGet(bytes.length))
                                  .reduce(Bytes::concat)
                                  .block();

        // Make sure that the returned file has a valid hprof format
        assertThat(Arrays.copyOf(actual, fileHeader.length)).isEqualTo(fileHeader);
    }
}
