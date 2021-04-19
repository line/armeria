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

package com.linecorp.armeria.server.metric;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ThreadDumpServiceTest {
    private static final ObjectMapper mapper = new ObjectMapper();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/internal/threads", ThreadDumpService.of());
        }
    };

    @Test
    void threadDump() throws InterruptedException {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse response = client.get("/internal/threads").aggregate().join();
        assertThat(response.contentType()).isEqualTo(MediaType.PLAIN_TEXT);
        assertThat(response.contentUtf8()).contains(Thread.currentThread().getName());
    }

    @Test
    void threadDumpWithJson() throws Exception {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse response =
                client.prepare()
                      .get("/internal/threads")
                      .header(HttpHeaderNames.ACCEPT, MediaType.JSON)
                      .execute().aggregate().join();

        assertThat(response.contentType()).isEqualTo(MediaType.JSON);
        final String content = response.contentUtf8();
        assertThat(content).contains(Thread.currentThread().getName());
        assertThat(mapper.readTree(content).isArray()).isTrue();
    }
}
