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

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.ClassRule;
import org.junit.Test;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.TestConverters.ByteArrayConverterFunction;
import com.linecorp.armeria.server.TestConverters.UnformattedStringConverterFunction;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.RequestConverter;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.RequestObject;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.server.ServerRule;

public class AnnotatedHttpServiceRequestConverterTest {

    @ClassRule
    public static final ServerRule rule = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService("/1", new MyDecorationService1(),
                                LoggingService.newDecorator());
            sb.annotatedService("/2", new MyDecorationService2(),
                                LoggingService.newDecorator());
        }
    };

    @ResponseConverter(UnformattedStringConverterFunction.class)
    @RequestConverter(TestRequestConverter1.class)
    public static class MyDecorationService1 {

        @Post("/convert1")
        public String convert1(@RequestObject RequestObj1 obj1) {
            assertThat(obj1).isNotNull();
            return obj1.toString();
        }

        @Post("/convert2")
        @RequestConverter(TestRequestConverter2.class)
        @RequestConverter(TestRequestConverter1A.class)
        @RequestConverter(TestRequestConverter1.class)
        public String convert2(@RequestObject RequestObj1 obj1) {
            assertThat(obj1).isNotNull();
            return obj1.toString();
        }

        @Post("/convert3")
        public String convert3(@RequestObject(TestRequestConverterOptional1.class) Optional<RequestObj1> obj1,
                               @RequestObject(TestRequestConverterOptional2.class) Optional<RequestObj2> obj2) {
            assertThat(obj1.isPresent()).isTrue();
            assertThat(obj2.isPresent()).isTrue();
            return obj2.get().strVal();
        }
    }

    @ResponseConverter(ByteArrayConverterFunction.class)
    @ResponseConverter(UnformattedStringConverterFunction.class)
    public static class MyDecorationService2 {
        @Post("/default/json")
        public String defaultJson(@RequestObject RequestObj1 obj1,
                                  @RequestObject RequestObj2 obj2) {
            assertThat(obj1).isNotNull();
            assertThat(obj2).isNotNull();
            return obj2.strVal();
        }

        @Post("/default/binary")
        public byte[] defaultBinary(@RequestObject HttpData obj1,
                                    @RequestObject byte[] obj2) {
            assertThat(obj1).isNotNull();
            assertThat(obj2).isNotNull();
            // Actually they have the same byte array.
            assertThat(obj1.array()).isSameAs(obj2);
            return obj2;
        }

        @Post("/default/text")
        public String defaultText(@RequestObject String obj1) {
            assertThat(obj1).isNotNull();
            return obj1;
        }

        @Post("/default/invalid")
        public String invalidJson(@RequestObject JsonNode node) {
            // Should never reach here because we are sending invalid JSON.
            throw new Error();
        }
    }

    static class RequestObj1 {
        private final int intVal;
        private final String strVal;

        @JsonCreator
        RequestObj1(@JsonProperty("intVal") int intVal,
                    @JsonProperty("strVal") String strVal) {
            this.intVal = intVal;
            this.strVal = requireNonNull(strVal, "strVal");
        }

        @JsonProperty
        int intVal() {
            return intVal;
        }

        @JsonProperty
        String strVal() {
            return strVal;
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + ':' + intVal() + ':' + strVal();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RequestObj2 {
        private final String strVal;

        @JsonCreator
        RequestObj2(@JsonProperty("strVal") String strVal) {
            this.strVal = requireNonNull(strVal, "strVal");
        }

        @JsonProperty
        String strVal() {
            return strVal;
        }
    }

    public static class TestRequestConverter1 implements RequestConverterFunction {
        private final ObjectMapper mapper = new ObjectMapper();

        @Override
        public RequestObj1 convertRequest(ServiceRequestContext ctx, AggregatedHttpMessage request,
                                          Class<?> expectedResultType) throws Exception {
            if (expectedResultType.isAssignableFrom(RequestObj1.class)) {
                return mapper.readValue(request.content().toStringUtf8(), RequestObj1.class);
            }
            return RequestConverterFunction.fallthrough();
        }
    }

    public static class TestRequestConverter1A implements RequestConverterFunction {
        private final ObjectMapper mapper = new ObjectMapper();

        @Override
        public RequestObj1 convertRequest(ServiceRequestContext ctx, AggregatedHttpMessage request,
                                          Class<?> expectedResultType) throws Exception {
            if (expectedResultType.isAssignableFrom(RequestObj1.class)) {
                final RequestObj1 obj1 = mapper.readValue(request.content().toStringUtf8(),
                                                          RequestObj1.class);
                return new RequestObj1(obj1.intVal() + 1, obj1.strVal() + 'a');
            }
            return RequestConverterFunction.fallthrough();
        }
    }

    public static class TestRequestConverter2 implements RequestConverterFunction {
        @Override
        public RequestObj2 convertRequest(ServiceRequestContext ctx, AggregatedHttpMessage request,
                                          Class<?> expectedResultType) throws Exception {
            if (expectedResultType.isAssignableFrom(RequestObj2.class)) {
                return new RequestObj2(request.headers().method().name());
            }
            return RequestConverterFunction.fallthrough();
        }
    }

    public static class TestRequestConverterOptional1 implements RequestConverterFunction {
        private final ObjectMapper mapper = new ObjectMapper();

        @Override
        public Optional<RequestObj1> convertRequest(ServiceRequestContext ctx, AggregatedHttpMessage request,
                                                    Class<?> expectedResultType) throws Exception {
            return Optional.of(mapper.readValue(request.content().toStringUtf8(), RequestObj1.class));
        }
    }

    public static class TestRequestConverterOptional2 implements RequestConverterFunction {
        @Override
        public Optional<RequestObj2> convertRequest(ServiceRequestContext ctx, AggregatedHttpMessage request,
                                                    Class<?> expectedResultType) throws Exception {
            return Optional.of(new RequestObj2(request.headers().method().name()));
        }
    }

    @Test
    public void testRequestConverter() throws Exception {
        final HttpClient client = HttpClient.of(rule.uri("/"));
        final ObjectMapper mapper = new ObjectMapper();

        AggregatedHttpMessage response;

        final RequestObj1 obj1 = new RequestObj1(1, "abc");
        final String content1 = mapper.writeValueAsString(obj1);

        response = client.post("/1/convert1", content1).aggregate().join();
        assertThat(response.headers().status()).isEqualTo(HttpStatus.OK);
        assertThat(response.content().toStringUtf8()).isEqualTo(obj1.toString());

        // The order of converters
        final RequestObj1 obj1a = new RequestObj1(2, "abca");
        response = client.post("/1/convert2", content1).aggregate().join();
        assertThat(response.headers().status()).isEqualTo(HttpStatus.OK);
        assertThat(response.content().toStringUtf8()).isEqualTo(obj1a.toString());

        // Multiple @RequestObject annotated parameters
        response = client.post("/1/convert3", content1).aggregate().join();
        assertThat(response.headers().status()).isEqualTo(HttpStatus.OK);
        assertThat(response.content().toStringUtf8()).isEqualTo(HttpMethod.POST.name());
    }

    @Test
    public void testDefaultRequestConverter() throws Exception {
        final HttpClient client = HttpClient.of(rule.uri("/"));
        final ObjectMapper mapper = new ObjectMapper();

        AggregatedHttpMessage response;

        final RequestObj1 obj1 = new RequestObj1(1, "abc");
        final String content1 = mapper.writeValueAsString(obj1);

        // MediaType.JSON_UTF_8
        response = client.execute(AggregatedHttpMessage.of(HttpMethod.POST, "/2/default/json",
                                                           MediaType.JSON_UTF_8, content1))
                         .aggregate().join();
        assertThat(response.headers().status()).isEqualTo(HttpStatus.OK);
        assertThat(response.content().toStringUtf8()).isEqualTo("abc");

        // MediaType.JSON_PATCH
        // obj1 is not a json-patch+json format, but just check if it's converted by
        // DefaultRequestConverter when it is valid JSON format
        response = client.execute(AggregatedHttpMessage.of(HttpMethod.POST, "/2/default/json",
                                                           MediaType.JSON_PATCH, content1))
                         .aggregate().join();
        assertThat(response.headers().status()).isEqualTo(HttpStatus.OK);
        assertThat(response.content().toStringUtf8()).isEqualTo("abc");

        // "application/vnd.api+json"
        response = client.execute(AggregatedHttpMessage.of(HttpMethod.POST, "/2/default/json",
                                                           MediaType.create("application", "vnd.api+json"),
                                                           content1))
                         .aggregate().join();
        assertThat(response.headers().status()).isEqualTo(HttpStatus.OK);
        assertThat(response.content().toStringUtf8()).isEqualTo("abc");

        final byte[] binary = { 0x00, 0x01, 0x02 };
        response = client.execute(AggregatedHttpMessage.of(HttpMethod.POST, "/2/default/binary",
                                                           MediaType.OCTET_STREAM, binary))
                         .aggregate().join();
        assertThat(response.headers().status()).isEqualTo(HttpStatus.OK);
        assertThat(response.content().array()).isEqualTo(binary);

        final byte[] utf8 = "¥".getBytes(StandardCharsets.UTF_8);
        response = client.execute(AggregatedHttpMessage.of(HttpMethod.POST, "/2/default/text",
                                                           MediaType.PLAIN_TEXT_UTF_8, utf8))
                         .aggregate().join();
        assertThat(response.headers().status()).isEqualTo(HttpStatus.OK);
        assertThat(response.content().array()).isEqualTo(utf8);

        final MediaType textPlain = MediaType.create("text", "plain");
        final byte[] iso8859_1 = "¥".getBytes(StandardCharsets.ISO_8859_1);
        response = client.execute(AggregatedHttpMessage.of(HttpMethod.POST, "/2/default/text",
                                                           textPlain, iso8859_1))
                         .aggregate().join();
        assertThat(response.headers().status()).isEqualTo(HttpStatus.OK);
        // Response is encoded as UTF-8.
        assertThat(response.content().array()).isEqualTo(utf8);

        String invalidJson = "{\"foo:\"bar\"}"; // should be \"foo\"
        response = client.execute(AggregatedHttpMessage.of(HttpMethod.POST, "/2/default/invalid",
                                                           MediaType.JSON_UTF_8, invalidJson))
                         .aggregate().join();
        assertThat(response.headers().status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
