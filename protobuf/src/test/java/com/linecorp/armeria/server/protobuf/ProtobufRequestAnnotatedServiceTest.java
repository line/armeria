/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.server.protobuf;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.protobuf.testing.Messages.SimpleRequest;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.ConsumesJson;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ProtobufRequestAnnotatedServiceTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService(new GreetingService());
        }
    };

    WebClient client;

    @BeforeEach
    void setUp() {
        client = WebClient.of(server.httpUri());
    }

    @Test
    void protobufRequest() throws InvalidProtocolBufferException {
        final SimpleRequest simpleRequest = SimpleRequest.newBuilder().setPayload("Armeria").build();
        final AggregatedHttpResponse response =
                client.post("/default-content-type", simpleRequest.toByteArray()).aggregate().join();

        assertThat(response.contentUtf8()).isEqualTo("Hello, Armeria!");
    }

    @Test
    void jsonRequest() throws InvalidProtocolBufferException {
        final SimpleRequest simpleRequest = SimpleRequest.newBuilder().setPayload("Armeria").build();
        final String json = JsonFormat.printer().print(simpleRequest);
        final HttpRequest request = HttpRequest.of(HttpMethod.POST, "/json", MediaType.JSON, json);
        final AggregatedHttpResponse response = client.execute(request).aggregate().join();

        assertThat(response.contentUtf8()).isEqualTo("Hello, Armeria!");
    }

    @CsvSource({ "/json+array", "/json+array2" })
    @ParameterizedTest
    void jsonArrayRequest(String path) throws InvalidProtocolBufferException {
        final SimpleRequest simpleRequest1 = SimpleRequest.newBuilder().setSize(1).build();
        final SimpleRequest simpleRequest2 = SimpleRequest.newBuilder().setSize(2).build();
        final String json1 = JsonFormat.printer().print(simpleRequest1);
        final String json2 = JsonFormat.printer().print(simpleRequest2);
        final String jsonArray = ImmutableList.of(json1, json2).stream()
                                              .collect(Collectors.joining(",", "[", "]"));
        final HttpRequest request = HttpRequest.of(HttpMethod.POST, path, MediaType.JSON, jsonArray);
        final AggregatedHttpResponse response = client.execute(request).aggregate().join();

        assertThat(response.contentUtf8()).isEqualTo("Sum: 3");
    }

    @Test
    void jsonObjectRequest() throws InvalidProtocolBufferException {
        final SimpleRequest simpleRequest1 = SimpleRequest.newBuilder().setSize(1).build();
        final SimpleRequest simpleRequest2 = SimpleRequest.newBuilder().setSize(2).build();
        final String json1 = JsonFormat.printer().print(simpleRequest1);
        final String json2 = JsonFormat.printer().print(simpleRequest2);
        final String jsonObject = "{ \"json1\":" + json1 +
                                  ", \"json2\":" + json2 + '}';

        final HttpRequest request = HttpRequest.of(HttpMethod.POST, "/json+object", MediaType.JSON, jsonObject);
        final AggregatedHttpResponse response = client.execute(request).aggregate().join();

        assertThat(response.contentUtf8()).isEqualTo("OK");
    }

    private static class GreetingService {
        @Post("/default-content-type")
        public String noContentType(SimpleRequest request) {
            return "Hello, Armeria!";
        }

        @Post("/json")
        @ConsumesJson
        public String consumeJson(SimpleRequest request) {
            return "Hello, Armeria!";
        }

        @Post("/json+array")
        @ConsumesJson
        public String consumeJson(List<SimpleRequest> request) {
            return "Sum: " + request.stream().mapToInt(SimpleRequest::getSize).sum();
        }

        @Post("/json+array2")
        @ConsumesJson
        public String consumeJson2(Set<SimpleRequest> request) {
            return "Sum: " + request.stream().mapToInt(SimpleRequest::getSize).sum();
        }

        @Post("/json+object")
        @ConsumesJson
        public String consumeJson3(Map<String, SimpleRequest> request) {
            assertThat(request.get("json1").getSize()).isEqualTo(1);
            assertThat(request.get("json2").getSize()).isEqualTo(2);
            return "OK";
        }
    }
}
