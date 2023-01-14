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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ResponseAsTest.MyObject;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.internal.common.JacksonUtil;

class AggregatedResponseAsTest {

    private final ResponseHeaders headers = ResponseHeaders.of(200);

    @Test
    void bytes() {
        final String content = "bytes";
        final AggregatedHttpResponse response = AggregatedHttpResponse.of(headers, HttpData.ofUtf8(content));
        final ResponseEntity<byte[]> entity = AggregatedResponseAs.bytes().as(response);
        assertThat(entity.content()).isEqualTo(content.getBytes());
    }

    @Test
    void string() {
        final String content = "string";
        final AggregatedHttpResponse response = AggregatedHttpResponse.of(headers, HttpData.ofUtf8(content));
        final ResponseEntity<String> entity = AggregatedResponseAs.string().as(response);
        assertThat(entity.content()).isEqualTo(content);
    }

    @Test
    void jsonObject() throws JsonProcessingException {
        final MyObject myObject = new MyObject();
        myObject.setId(10);
        final byte[] content = JacksonUtil.writeValueAsBytes(myObject);
        final AggregatedHttpResponse response = AggregatedHttpResponse.of(headers, HttpData.wrap(content));

        final ResponseEntity<MyObject> entity = AggregatedResponseAs.json(MyObject.class).as(response);
        assertThat(entity.content()).isEqualTo(myObject);
    }

    @CsvSource({ "200", "500" })
    @ParameterizedTest
    void jsonObject_withHttpStatusPredicate(int statusCode) throws JsonProcessingException {
        final ResponseHeaders headers = ResponseHeaders.of(statusCode);
        final HttpStatus httpStatus = HttpStatus.valueOf(statusCode);

        final MyObject myObject = new MyObject();
        myObject.setId(10);
        final byte[] content = JacksonUtil.writeValueAsBytes(myObject);
        final AggregatedHttpResponse response = AggregatedHttpResponse.of(headers, HttpData.wrap(content));

        final ResponseEntity<MyObject> entity = AggregatedResponseAs.json(
                MyObject.class,
                HttpStatusPredicate.of(httpStatus)).as(response);
        assertThat(entity.content()).isEqualTo(myObject);
    }

    @CsvSource({ "200", "500" })
    @ParameterizedTest
    void jsonObject_withHttpStatusClassPredicate(int statusCode) throws JsonProcessingException {
        final ResponseHeaders headers = ResponseHeaders.of(statusCode);
        final HttpStatusClass httpStatusClass = HttpStatusClass.valueOf(statusCode);

        final MyObject myObject = new MyObject();
        myObject.setId(10);
        final byte[] content = JacksonUtil.writeValueAsBytes(myObject);
        final AggregatedHttpResponse response = AggregatedHttpResponse.of(headers, HttpData.wrap(content));

        final ResponseEntity<MyObject> entity = AggregatedResponseAs.json(
                MyObject.class,
                HttpStatusClassPredicates.of(httpStatusClass)).as(response);
        assertThat(entity.content()).isEqualTo(myObject);
    }

    @Test
    void jsonObject_withPredicate() throws JsonProcessingException {
        final MyObject myObject = new MyObject();
        myObject.setId(10);
        final byte[] content = JacksonUtil.writeValueAsBytes(myObject);
        final AggregatedHttpResponse response = AggregatedHttpResponse.of(headers,
                                                                          HttpData.wrap(content));

        final ResponseEntity<MyObject> entity = AggregatedResponseAs.json(
                MyObject.class,
                httpStatus -> httpStatus.equals(HttpStatus.OK)).as(response);
        assertThat(entity.content()).isEqualTo(myObject);
    }

    @Test
    void jsonObject_withInvalidStatus() throws JsonProcessingException {
        final MyObject myObject = new MyObject();
        myObject.setId(10);
        final byte[] content = JacksonUtil.writeValueAsBytes(myObject);
        final AggregatedHttpResponse response =
                AggregatedHttpResponse.of(ResponseHeaders.of(500), HttpData.wrap(content));

        assertThatThrownBy(() -> AggregatedResponseAs.json(MyObject.class).as(response))
                .isInstanceOf(InvalidHttpResponseException.class)
                .hasMessageContaining("(expect: the SUCCESS class");
    }

    @Test
    void jsonObject_withInvalidHttpStatusPredicate() throws JsonProcessingException {
        final MyObject myObject = new MyObject();
        myObject.setId(10);
        final byte[] content = JacksonUtil.writeValueAsBytes(myObject);
        final AggregatedHttpResponse response =
                AggregatedHttpResponse.of(ResponseHeaders.of(200), HttpData.wrap(content));

        assertThatThrownBy(() -> AggregatedResponseAs.json(
                MyObject.class,
                HttpStatusPredicate.of(HttpStatus.INTERNAL_SERVER_ERROR)).as(response))
                .isInstanceOf(InvalidHttpResponseException.class)
                .hasMessageContaining("status: 200 OK (expect: the Internal Server Error class (500)");
    }

    @Test
    void jsonObject_withInvalidHttpStatusClassPredicate() throws JsonProcessingException {
        final MyObject myObject = new MyObject();
        myObject.setId(10);
        final byte[] content = JacksonUtil.writeValueAsBytes(myObject);
        final AggregatedHttpResponse response =
                AggregatedHttpResponse.of(ResponseHeaders.of(200), HttpData.wrap(content));

        assertThatThrownBy(() -> AggregatedResponseAs.json(
                MyObject.class,
                HttpStatusClassPredicates.of(HttpStatusClass.SERVER_ERROR)).as(response))
                .isInstanceOf(InvalidHttpResponseException.class)
                .hasMessageContaining("status: 200 OK (expect: the SERVER_ERROR class. response");
    }

    @Test
    void jsonObject_withInvalidPredicate() throws JsonProcessingException {
        final MyObject myObject = new MyObject();
        myObject.setId(10);
        final byte[] content = JacksonUtil.writeValueAsBytes(myObject);
        final AggregatedHttpResponse response =
                AggregatedHttpResponse.of(ResponseHeaders.of(200), HttpData.wrap(content));

        assertThatThrownBy(() -> AggregatedResponseAs.json(
                MyObject.class,
                httpStatus -> httpStatus.equals(HttpStatus.INTERNAL_SERVER_ERROR)).as(response))
                .isInstanceOf(InvalidHttpResponseException.class)
                .hasMessageContaining("status: 200 OK is not expected by predicate method.");
    }

    @Test
    void jsonObject_withInvalidContent() throws JsonProcessingException {
        final AggregatedHttpResponse response =
                AggregatedHttpResponse.of(headers, HttpData.ofUtf8("{ 'id': 10 }"));

        assertThatThrownBy(() -> AggregatedResponseAs.json(MyObject.class).as(response))
                .isInstanceOf(InvalidHttpResponseException.class)
                .hasCauseInstanceOf(JsonProcessingException.class);
    }

    @Test
    void jsonObject_customMapper() {
        final AggregatedHttpResponse response =
                AggregatedHttpResponse.of(headers, HttpData.ofUtf8("{ 'id': 10 }"));

        final JsonMapper mapper = JsonMapper.builder()
                                            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
                                            .build();
        final ResponseEntity<MyObject> entity = AggregatedResponseAs.json(MyObject.class, mapper).as(response);
        final MyObject myObject = new MyObject();
        myObject.setId(10);
        assertThat(entity.content()).isEqualTo(myObject);
    }

    @CsvSource({ "200", "500" })
    @ParameterizedTest
    void jsonObject_customMapper_withHttpStatusPredicate(int statusCode) {
        final ResponseHeaders headers = ResponseHeaders.of(statusCode);
        final HttpStatus httpStatus = HttpStatus.valueOf(statusCode);

        final AggregatedHttpResponse response =
                AggregatedHttpResponse.of(headers, HttpData.ofUtf8("{ 'id': 10 }"));

        final JsonMapper mapper = JsonMapper.builder()
                                            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
                                            .build();
        final ResponseEntity<MyObject> entity = AggregatedResponseAs.json(
                MyObject.class, mapper, HttpStatusPredicate.of(httpStatus)).as(response);
        final MyObject myObject = new MyObject();
        myObject.setId(10);
        assertThat(entity.content()).isEqualTo(myObject);
    }

    @CsvSource({ "200", "500" })
    @ParameterizedTest
    void jsonObject_customMapper_withHttpStatusClassPredicate(int statusCode) {
        final ResponseHeaders headers = ResponseHeaders.of(statusCode);
        final HttpStatusClass httpStatusClass = HttpStatusClass.valueOf(statusCode);

        final AggregatedHttpResponse response =
                AggregatedHttpResponse.of(headers, HttpData.ofUtf8("{ 'id': 10 }"));

        final JsonMapper mapper = JsonMapper.builder()
                                            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
                                            .build();
        final ResponseEntity<MyObject> entity = AggregatedResponseAs.json(
                MyObject.class, mapper,
                HttpStatusClassPredicates.of(httpStatusClass)).as(response);
        final MyObject myObject = new MyObject();
        myObject.setId(10);
        assertThat(entity.content()).isEqualTo(myObject);
    }

    @Test
    void jsonGeneric() throws JsonProcessingException {
        final MyObject myObject = new MyObject();
        myObject.setId(10);
        final byte[] content = JacksonUtil.writeValueAsBytes(ImmutableList.of(myObject));
        final AggregatedHttpResponse response = AggregatedHttpResponse.of(headers, HttpData.wrap(content));

        final ResponseEntity<List<MyObject>> entity =
                AggregatedResponseAs.json(new TypeReference<List<MyObject>>() {}).as(response);
        final List<MyObject> objects = entity.content();
        assertThat(objects).containsExactly(myObject);
    }

    @CsvSource({ "200", "500" })
    @ParameterizedTest
    void jsonGeneric_withHttpStatusPredicate(int statusCode) throws JsonProcessingException {
        final ResponseHeaders headers = ResponseHeaders.of(statusCode);
        final HttpStatus httpStatus = HttpStatus.valueOf(statusCode);

        final MyObject myObject = new MyObject();
        myObject.setId(10);
        final byte[] content = JacksonUtil.writeValueAsBytes(ImmutableList.of(myObject));
        final AggregatedHttpResponse response = AggregatedHttpResponse.of(headers, HttpData.wrap(content));

        final ResponseEntity<List<MyObject>> entity = AggregatedResponseAs.json(
                new TypeReference<List<MyObject>>() {},
                HttpStatusPredicate.of(httpStatus)).as(response);
        final List<MyObject> objects = entity.content();
        assertThat(objects).containsExactly(myObject);
    }

    @CsvSource({ "200", "500" })
    @ParameterizedTest
    void jsonGeneric_withHttpStatusClassPredicate(int statusCode) throws JsonProcessingException {
        final ResponseHeaders headers = ResponseHeaders.of(statusCode);
        final HttpStatusClass httpStatusClass = HttpStatusClass.valueOf(statusCode);

        final MyObject myObject = new MyObject();
        myObject.setId(10);
        final byte[] content = JacksonUtil.writeValueAsBytes(ImmutableList.of(myObject));
        final AggregatedHttpResponse response = AggregatedHttpResponse.of(headers, HttpData.wrap(content));

        final ResponseEntity<List<MyObject>> entity = AggregatedResponseAs.json(
                new TypeReference<List<MyObject>>() {},
                HttpStatusClassPredicates.of(httpStatusClass)).as(response);
        final List<MyObject> objects = entity.content();
        assertThat(objects).containsExactly(myObject);
    }

    @Test
    void jsonGeneric_withPredicate() throws JsonProcessingException {
        final MyObject myObject = new MyObject();
        myObject.setId(10);
        final byte[] content = JacksonUtil.writeValueAsBytes(ImmutableList.of(myObject));
        final AggregatedHttpResponse response = AggregatedHttpResponse.of(headers, HttpData.wrap(content));

        final ResponseEntity<List<MyObject>> entity = AggregatedResponseAs.json(
                new TypeReference<List<MyObject>>() {},
                httpStatus -> httpStatus.equals(HttpStatus.OK)).as(response);
        final List<MyObject> objects = entity.content();
        assertThat(objects).containsExactly(myObject);
    }

    @Test
    void jsonGeneric_customMapper() {
        final AggregatedHttpResponse response =
                AggregatedHttpResponse.of(headers, HttpData.ofUtf8("[{ 'id': 10 }]"));
        final JsonMapper mapper = JsonMapper.builder()
                                            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
                                            .build();
        final ResponseEntity<List<MyObject>> entity =
                AggregatedResponseAs.json(new TypeReference<List<MyObject>>() {}, mapper).as(response);
        final List<MyObject> content = entity.content();
        final MyObject myObject = new MyObject();
        myObject.setId(10);
        assertThat(content).containsExactly(myObject);
    }

    @CsvSource({ "200", "500" })
    @ParameterizedTest
    void jsonGeneric_customMapper_withHttpStatusPredicate(int statusCode) {
        final ResponseHeaders headers = ResponseHeaders.of(statusCode);
        final HttpStatus httpStatus = HttpStatus.valueOf(statusCode);

        final AggregatedHttpResponse response =
                AggregatedHttpResponse.of(headers, HttpData.ofUtf8("[{ 'id': 10 }]"));
        final JsonMapper mapper = JsonMapper.builder()
                                            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
                                            .build();
        final ResponseEntity<List<MyObject>> entity =
                AggregatedResponseAs.json(new TypeReference<List<MyObject>>() {}, mapper,
                                          HttpStatusPredicate.of(httpStatus)).as(response);
        final List<MyObject> content = entity.content();
        final MyObject myObject = new MyObject();
        myObject.setId(10);
        assertThat(content).containsExactly(myObject);
    }

    @CsvSource({ "200", "500" })
    @ParameterizedTest
    void jsonGeneric_customMapper_withHttpStatusClassPredicate(int statusCode) {
        final ResponseHeaders headers = ResponseHeaders.of(statusCode);
        final HttpStatusClass httpStatusClass = HttpStatusClass.valueOf(statusCode);

        final AggregatedHttpResponse response =
                AggregatedHttpResponse.of(headers, HttpData.ofUtf8("[{ 'id': 10 }]"));
        final JsonMapper mapper = JsonMapper.builder()
                                            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
                                            .build();
        final ResponseEntity<List<MyObject>> entity =
                AggregatedResponseAs.json(
                        new TypeReference<List<MyObject>>() {}, mapper,
                        HttpStatusClassPredicates.of(httpStatusClass)).as(response);
        final List<MyObject> content = entity.content();
        final MyObject myObject = new MyObject();
        myObject.setId(10);
        assertThat(content).containsExactly(myObject);
    }

    @Test
    void jsonGeneric_customMapper_withPredicate() {
        final AggregatedHttpResponse response =
                AggregatedHttpResponse.of(headers, HttpData.ofUtf8("[{ 'id': 10 }]"));
        final JsonMapper mapper = JsonMapper.builder()
                                            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
                                            .build();
        final ResponseEntity<List<MyObject>> entity =
                AggregatedResponseAs.json(new TypeReference<List<MyObject>>() {}, mapper,
                                          httpStatus -> httpStatus.equals(HttpStatus.OK)).as(response);
        final List<MyObject> content = entity.content();
        final MyObject myObject = new MyObject();
        myObject.setId(10);
        assertThat(content).containsExactly(myObject);
    }
}
