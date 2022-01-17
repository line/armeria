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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.ResponseEntity;

class ResponseAsTest {

    @TempDir
    static Path tempDir;

    @Test
    void defaultAggregationRequired() {
        final ResponseAs<HttpResponse, Integer> responseAs = response -> 1;
        assertThat(responseAs.aggregationRequired()).isFalse();
    }

    @Test
    void composeAggregationRequired() {
        final ResponseAs<HttpResponse, HttpResponse> noAggregation = response -> response;
        final ResponseAs<HttpResponse, HttpResponse> aggregation =
                new ResponseAs<HttpResponse, HttpResponse>() {
                    @Override
                    public HttpResponse as(HttpResponse response) {
                        return response;
                    }

                    @Override
                    public boolean aggregationRequired() {
                        return true;
                    }
                };
        assertThat(noAggregation.aggregationRequired()).isFalse();
        assertThat(aggregation.aggregationRequired()).isTrue();
        // 'aggregationRequired()' should be overwritten
        assertThat(noAggregation.andThen(aggregation).aggregationRequired()).isTrue();

        // The response was aggregated already. 'aggregationRequired()' should be preserved.
        assertThat(aggregation.andThen(noAggregation).aggregationRequired()).isTrue();
    }

    @Test
    void bytes() {
        final String content = "bytes";
        final HttpResponse response = HttpResponse.of(content);
        final ResponseEntity<byte[]> entity = ResponseAs.bytes().as(response).join();
        assertThat(entity.content()).isEqualTo(content.getBytes());
    }

    @Test
    void string() {
        final String content = "string";
        final HttpResponse response = HttpResponse.of(content);
        final ResponseEntity<String> entity = ResponseAs.string().as(response).join();
        assertThat(entity.content()).isEqualTo(content);
    }

    @Test
    void file() throws IOException {
        final String content = "file";
        final HttpResponse response = HttpResponse.of(content);

        final ResponseEntity<Path> entity = ResponseAs.path(tempDir.resolve("as.txt")).as(response).join();
        assertThat(Files.readAllBytes(entity.content())).isEqualTo(content.getBytes());
    }

    @Test
    void jsonObject() {
        final MyObject myObject = new MyObject();
        myObject.setId(10);
        final HttpResponse response = HttpResponse.ofJson(myObject);

        final ResponseEntity<MyObject> entity = ResponseAs.json(MyObject.class).as(response).join();
        assertThat(entity.content()).isEqualTo(myObject);
    }

    @Test
    void jsonObject_customMapper() {
        final HttpResponse response = HttpResponse.of("{ 'id': 10 }");

        final JsonMapper mapper = JsonMapper.builder()
                                            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
                                            .build();
        final ResponseEntity<MyObject> entity = ResponseAs.json(MyObject.class, mapper).as(response).join();
        final MyObject myObject = new MyObject();
        myObject.setId(10);
        assertThat(entity.content()).isEqualTo(myObject);
    }

    @Test
    void jsonGeneric() {
        final MyObject myObject = new MyObject();
        myObject.setId(10);
        final HttpResponse response = HttpResponse.ofJson(ImmutableList.of(myObject));

        final ResponseEntity<List<MyObject>> entity =
                ResponseAs.json(new TypeReference<List<MyObject>>() {}).as(response).join();
        final List<MyObject> content = entity.content();
        assertThat(content).containsExactly(myObject);
    }

    @Test
    void jsonGeneric_customMapper() {
        final HttpResponse response = HttpResponse.of("[{ 'id': 10 }]");
        final JsonMapper mapper = JsonMapper.builder()
                                            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
                                            .build();
        final ResponseEntity<List<MyObject>> entity =
                ResponseAs.json(new TypeReference<List<MyObject>>() {}, mapper).as(response).join();
        final List<MyObject> content = entity.content();
        final MyObject myObject = new MyObject();
        myObject.setId(10);
        assertThat(content).containsExactly(myObject);
    }

    static class MyObject {

        private int id;

        public void setId(int id) {
            this.id = id;
        }

        public int getId() {
            return id;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof MyObject)) {
                return false;
            }
            final MyObject myObject = (MyObject) o;
            return id == myObject.id;
        }

        @Override
        public int hashCode() {
            return id;
        }
    }
}
