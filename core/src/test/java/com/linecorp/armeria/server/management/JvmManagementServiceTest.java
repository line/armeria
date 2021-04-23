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
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SplitHttpResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import reactor.core.publisher.Flux;

class JvmManagementServiceTest {
    private static final ObjectMapper mapper = new ObjectMapper();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.serviceUnder("/internal/management", JvmManagementService.of());
        }
    };

    @Test
    void threadDump() throws InterruptedException {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse response =
                client.get("/internal/management/threaddump").aggregate().join();
        assertThat(response.contentType()).isEqualTo(MediaType.PLAIN_TEXT);
        assertThat(response.contentUtf8()).contains(Thread.currentThread().getName());
    }

    @Test
    void threadDumpWithJson() throws Exception {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse response =
                client.prepare()
                      .get("/internal/management/threaddump")
                      .header(HttpHeaderNames.ACCEPT, MediaType.JSON)
                      .execute().aggregate().join();

        assertThat(response.contentType()).isEqualTo(MediaType.JSON);
        final String content = response.contentUtf8();
        assertThat(content).contains(Thread.currentThread().getName());
        assertThat(mapper.readTree(content).isArray()).isTrue();
    }

    @Test
    void heapDump() throws InterruptedException {
        final WebClient client = WebClient.of(server.httpUri());
        final HttpResponse response = client.get("/internal/management/heapdump");
        final SplitHttpResponse splitHttpResponse = response.split();
        final ResponseHeaders headers = splitHttpResponse.headers().join();
        final ContentDisposition disposition =
                ContentDisposition.parse(headers.get(HttpHeaderNames.CONTENT_DISPOSITION));

        assertThat(disposition).isNotNull();
        final String filename = disposition.filename();
        assertThat(filename).startsWith("heapdump_");
        assertThat(filename).endsWith(".hprof");

        final byte[] fileHeader = "JAVA PROFILE".getBytes(StandardCharsets.UTF_8);
        final AtomicInteger counter = new AtomicInteger();
        final byte[] actual = Flux.from(splitHttpResponse.body())
                                  .map(HttpData::array)
                                  .takeUntil(bytes -> counter.getAndAdd(bytes.length) <= fileHeader.length)
                                  .reduce(Bytes::concat)
                                  .block();

        // Make sure that the returned file has a valid hprof format
        assertThat(Arrays.copyOf(actual, fileHeader.length)).isEqualTo(fileHeader);
    }
}
