/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;

import org.junit.Test;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.netty.util.AsciiString;

public class HttpHeadersJsonDeserializerTest {

    private static final AsciiString NAME = AsciiString.of("a");

    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void singleString() throws IOException {
        assertThat(mapper.readValue("{\"a\":\"0\"}", HttpHeaders.class))
                .isEqualTo(HttpHeaders.of(NAME, "0"));
    }

    @Test
    public void multipleValues() throws IOException {
        final HttpHeaders expected = new DefaultHttpHeaders();
        expected.set(NAME, "foo", "bar", "baz");
        assertThat(mapper.readValue("{\"a\":[\"foo\",\"bar\",\"baz\"]}", HttpHeaders.class))
                .isEqualTo(expected);
    }

    @Test
    public void failOnNonObject() {
        assertThatThrownBy(() -> mapper.readValue("[]", HttpHeaders.class))
                .isInstanceOf(JsonMappingException.class);
    }

    @Test
    public void failOnNullValue1() {
        assertThatThrownBy(() -> mapper.readValue("{\"a\":null}", HttpHeaders.class))
                .isInstanceOf(JsonMappingException.class);
    }

    @Test
    public void failOnNullValue2() {
        assertThatThrownBy(() -> mapper.readValue("{\"a\":[null]}", HttpHeaders.class))
                .isInstanceOf(JsonMappingException.class);
    }

    @Test
    public void failOnNestedArray() {
        assertThatThrownBy(() -> mapper.readValue("{\"a\":[[]]}", HttpHeaders.class))
                .isInstanceOf(JsonMappingException.class);
    }

    @Test
    public void failOnNumberValue() throws IOException {
        assertThatThrownBy(() -> mapper.readValue("{\"a\":3.14}", HttpHeaders.class))
                .isInstanceOf(JsonMappingException.class);
    }

    @Test
    public void failOnBooleanValue() throws IOException {
        assertThatThrownBy(() -> mapper.readValue("{\"a\":true}", HttpHeaders.class))
                .isInstanceOf(JsonMappingException.class);
    }
}
