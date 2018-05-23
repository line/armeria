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

import static com.linecorp.armeria.server.AnnotatedHttpServiceRequestConverterTest.Gender.MALE;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import org.junit.ClassRule;
import org.junit.Test;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.TestConverters.ByteArrayConverterFunction;
import com.linecorp.armeria.server.TestConverters.UnformattedStringConverterFunction;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Header;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.RequestConverter;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.RequestObject;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.server.ServerRule;

import io.netty.util.AsciiString;

public class AnnotatedHttpServiceRequestConverterTest {

    @ClassRule
    public static final ServerRule rule = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService("/1", new MyService1(),
                                LoggingService.newDecorator());
            sb.annotatedService("/2", new MyService2(),
                                LoggingService.newDecorator());
        }
    };

    @ResponseConverter(UnformattedStringConverterFunction.class)
    @RequestConverter(TestRequestConverter1.class)
    public static class MyService1 {

        @Post("/convert1")
        public String convert1(@RequestObject RequestJsonObj1 obj1) {
            assertThat(obj1).isNotNull();
            return obj1.toString();
        }

        @Post("/convert2")
        @RequestConverter(TestRequestConverter2.class)
        @RequestConverter(TestRequestConverter1A.class)
        @RequestConverter(TestRequestConverter1.class)
        public String convert2(@RequestObject RequestJsonObj1 obj1) {
            assertThat(obj1).isNotNull();
            return obj1.toString();
        }

        @Post("/convert3")
        public String convert3(@RequestObject(TestRequestConverterOptional1.class)
                                       Optional<RequestJsonObj1> obj1,
                               @RequestObject(TestRequestConverterOptional2.class)
                                       Optional<RequestJsonObj2> obj2) {
            assertThat(obj1.isPresent()).isTrue();
            assertThat(obj2.isPresent()).isTrue();
            return obj2.get().strVal();
        }
    }

    @ResponseConverter(ByteArrayConverterFunction.class)
    @ResponseConverter(UnformattedStringConverterFunction.class)
    public static class MyService2 {
        private final ObjectMapper mapper = new ObjectMapper();

        @Post("/default/bean1/{userName}/{seqNum}")
        public String defaultBean1ForPost(@RequestObject RequestBean1 bean1)
                throws JsonProcessingException {
            assertThat(bean1).isNotNull();
            bean1.validate();
            return mapper.writeValueAsString(bean1);
        }

        @Get("/default/bean1/{userName}/{seqNum}")
        public String defaultBean1ForGet(@RequestObject RequestBean1 bean1)
                throws JsonProcessingException {
            assertThat(bean1).isNotNull();
            bean1.validate();
            return mapper.writeValueAsString(bean1);
        }

        @Post("/default/bean2/{userName}/{serialNo}")
        public String defaultBean2ForPost(@RequestObject RequestBean2 bean2)
                throws JsonProcessingException {
            assertThat(bean2).isNotNull();
            bean2.validate();
            return mapper.writeValueAsString(bean2);
        }

        @Get("/default/bean2/{userName}")
        public String defaultBean2ForGet(@RequestObject RequestBean2 bean2)
                throws JsonProcessingException {
            assertThat(bean2).isNotNull();
            bean2.validate();
            return mapper.writeValueAsString(bean2);
        }

        @Post("/default/bean3/{userName}/{departmentNo}")
        public String defaultBean3ForPost(@RequestObject RequestBean3 bean3)
                throws JsonProcessingException {
            assertThat(bean3).isNotNull();
            bean3.validate();
            return mapper.writeValueAsString(bean3);
        }

        @Get("/default/bean3/{userName}")
        public String defaultBean3ForGet(@RequestObject RequestBean3 bean3)
                throws JsonProcessingException {
            assertThat(bean3).isNotNull();
            bean3.validate();
            return mapper.writeValueAsString(bean3);
        }

        @Post("/default/json")
        public String defaultJson(@RequestObject RequestJsonObj1 obj1,
                                  @RequestObject RequestJsonObj2 obj2) {
            assertThat(obj1).isNotNull();
            assertThat(obj2).isNotNull();
            return obj2.strVal();
        }

        @Post("/default/invalidJson")
        public String invalidJson(@RequestObject JsonNode node) {
            // Should never reach here because we are sending invalid JSON.
            throw new Error();
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
    }

    static class RequestJsonObj1 {
        private final int intVal;
        private final String strVal;

        @JsonCreator
        RequestJsonObj1(@JsonProperty("intVal") int intVal,
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
    static class RequestJsonObj2 {
        private final String strVal;

        @JsonCreator
        RequestJsonObj2(@JsonProperty("strVal") String strVal) {
            this.strVal = requireNonNull(strVal, "strVal");
        }

        @JsonProperty
        String strVal() {
            return strVal;
        }
    }

    abstract static class AbstractRequestBean {
        // test case: field with annotation
        @Nullable
        @Param
        String userName;

        int age = Integer.MIN_VALUE;

        @Nullable
        Gender gender;

        @Nullable
        List<String> permissions;

        @Nullable
        String clientName;

        // test case: method parameters with annotation
        public void initParams(@Param("age") final int age,
                               @Header("x-client-name") final String clientName,
                               @Header final String xUserPermission) {
            this.age = age;
            this.clientName = clientName;
            permissions = Arrays.asList(xUserPermission.split(","));
        }

        @JsonProperty
        public int getAge() {
            return age;
        }

        @JsonProperty
        public Gender getGender() {
            return gender;
        }

        // test case: method with annotation
        @Param
        public void setGender(final Gender gender) {
            this.gender = gender;
        }

        @JsonProperty
        public String getUserName() {
            return userName;
        }

        @JsonProperty
        public List<String> getPermissions() {
            return permissions;
        }

        @JsonProperty
        public String getClientName() {
            return clientName;
        }

        public void validate() {
            assertThat(userName).isNotNull();
            assertThat(age).isNotEqualTo(Integer.MIN_VALUE);
            assertThat(gender).isNotNull();
            assertThat(permissions).isNotNull();
            assertThat(clientName).isNotNull();
        }
    }

    enum Gender {
        MALE,
        FEMALE
    }

    // test case: default constructor(with no args)
    static class RequestBean1 extends AbstractRequestBean {
        // test case: field with annotation
        @Nullable
        @Param("seqNum")
        private Long seqNum;

        // test case: field with annotation
        @Nullable
        @Param("manager")
        private Boolean manager;

        @Nullable
        private String notPopulatedStr;

        private int notPopulatedInt;

        @Nullable
        private Long notPopulatedLong;

        @Nullable
        private Boolean notPopulatedBoolean;

        @JsonProperty
        public long getSeqNum() {
            return seqNum;
        }

        @JsonProperty
        public boolean isManager() {
            return manager;
        }

        @Override
        public void validate() {
            super.validate();

            assertThat(seqNum).isNotNull();
            assertThat(manager).isNotNull();

            assertThat(notPopulatedStr).isNull();
            assertThat(notPopulatedInt).isEqualTo(0);
            assertThat(notPopulatedLong).isNull();
            assertThat(notPopulatedBoolean).isNull();
        }
    }

    static class RequestBean2 extends AbstractRequestBean {
        // test case: field with annotation
        @Param("serialNo")
        private final Long serialNo;

        private final String uid;

        // test case: constructor args with annotations
        RequestBean2(@Param("serialNo") Long serialNo,
                     @Header("uid") String uid) {
            this.serialNo = serialNo;
            this.uid = uid;
        }

        @Override
        public void validate() {
            super.validate();

            assertThat(serialNo).isNotNull();
            assertThat(uid).isNotNull();
        }
    }

    static class RequestBean3 extends AbstractRequestBean {
        private int departmentNo = Integer.MIN_VALUE;

        // test case: constructor with annotations
        @Param("departmentNo")
        RequestBean3(int departmentNo) {
            this.departmentNo = departmentNo;
        }

        @Override
        public void validate() {
            super.validate();

            assertThat(departmentNo).isNotEqualTo(Integer.MIN_VALUE);
        }
    }

    public static class TestRequestConverter1 implements RequestConverterFunction {
        private final ObjectMapper mapper = new ObjectMapper();

        @Override
        public RequestJsonObj1 convertRequest(ServiceRequestContext ctx, AggregatedHttpMessage request,
                                              Class<?> expectedResultType) throws Exception {
            if (expectedResultType.isAssignableFrom(RequestJsonObj1.class)) {
                return mapper.readValue(request.content().toStringUtf8(), RequestJsonObj1.class);
            }
            return RequestConverterFunction.fallthrough();
        }
    }

    public static class TestRequestConverter1A implements RequestConverterFunction {
        private final ObjectMapper mapper = new ObjectMapper();

        @Override
        public RequestJsonObj1 convertRequest(ServiceRequestContext ctx, AggregatedHttpMessage request,
                                              Class<?> expectedResultType) throws Exception {
            if (expectedResultType.isAssignableFrom(RequestJsonObj1.class)) {
                final RequestJsonObj1 obj1 = mapper.readValue(request.content().toStringUtf8(),
                                                              RequestJsonObj1.class);
                return new RequestJsonObj1(obj1.intVal() + 1, obj1.strVal() + 'a');
            }
            return RequestConverterFunction.fallthrough();
        }
    }

    public static class TestRequestConverter2 implements RequestConverterFunction {
        @Override
        public RequestJsonObj2 convertRequest(ServiceRequestContext ctx, AggregatedHttpMessage request,
                                              Class<?> expectedResultType) throws Exception {
            if (expectedResultType.isAssignableFrom(RequestJsonObj2.class)) {
                return new RequestJsonObj2(request.headers().method().name());
            }
            return RequestConverterFunction.fallthrough();
        }
    }

    public static class TestRequestConverterOptional1 implements RequestConverterFunction {
        private final ObjectMapper mapper = new ObjectMapper();

        @Override
        public Optional<RequestJsonObj1> convertRequest(ServiceRequestContext ctx,
                                                        AggregatedHttpMessage request,
                                                        Class<?> expectedResultType) throws Exception {
            return Optional.of(mapper.readValue(request.content().toStringUtf8(), RequestJsonObj1.class));
        }
    }

    public static class TestRequestConverterOptional2 implements RequestConverterFunction {
        @Override
        public Optional<RequestJsonObj2> convertRequest(ServiceRequestContext ctx,
                                                        AggregatedHttpMessage request,
                                                        Class<?> expectedResultType) throws Exception {
            return Optional.of(new RequestJsonObj2(request.headers().method().name()));
        }
    }

    @Test
    public void testRequestConverter() throws Exception {
        final HttpClient client = HttpClient.of(rule.uri("/"));
        final ObjectMapper mapper = new ObjectMapper();

        AggregatedHttpMessage response;

        final RequestJsonObj1 obj1 = new RequestJsonObj1(1, "abc");
        final String content1 = mapper.writeValueAsString(obj1);

        response = client.post("/1/convert1", content1).aggregate().join();
        assertThat(response.headers().status()).isEqualTo(HttpStatus.OK);
        assertThat(response.content().toStringUtf8()).isEqualTo(obj1.toString());

        // The order of converters
        final RequestJsonObj1 obj1a = new RequestJsonObj1(2, "abca");
        response = client.post("/1/convert2", content1).aggregate().join();
        assertThat(response.headers().status()).isEqualTo(HttpStatus.OK);
        assertThat(response.content().toStringUtf8()).isEqualTo(obj1a.toString());

        // Multiple @RequestObject annotated parameters
        response = client.post("/1/convert3", content1).aggregate().join();
        assertThat(response.headers().status()).isEqualTo(HttpStatus.OK);
        assertThat(response.content().toStringUtf8()).isEqualTo(HttpMethod.POST.name());
    }

    @Test
    public void testDefaultRequestConverter_bean1() throws Exception {
        final HttpClient client = HttpClient.of(rule.uri("/"));
        final ObjectMapper mapper = new ObjectMapper();

        AggregatedHttpMessage response;

        // test for RequestBean1
        final RequestBean1 expectedRequestBean = new RequestBean1();
        expectedRequestBean.userName = "john";
        expectedRequestBean.age = 25;
        expectedRequestBean.gender = MALE;
        expectedRequestBean.permissions = Arrays.asList("permission1", "permission2");
        expectedRequestBean.clientName = "TestClient";
        expectedRequestBean.seqNum = 1234L;
        expectedRequestBean.manager = true;

        final String expectedResponseContent = mapper.writeValueAsString(expectedRequestBean);

        // Normal Request: POST + Form Data
        final HttpData formData = HttpData.ofAscii("age=25&manager=true&gender=male");
        HttpHeaders reqHeaders = HttpHeaders.of(HttpMethod.POST, "/2/default/bean1/john/1234")
                                            .set(AsciiString.of("x-user-permission"), "permission1,permission2")
                                            .set(AsciiString.of("x-client-name"), "TestClient")
                                            .contentType(MediaType.FORM_DATA);

        response = client.execute(AggregatedHttpMessage.of(reqHeaders, formData)).aggregate().join();
        assertThat(response.headers().status()).isEqualTo(HttpStatus.OK);
        assertThat(response.content().toStringUtf8()).isEqualTo(expectedResponseContent);

        // Normal Request: GET + Query String
        reqHeaders = HttpHeaders.of(HttpMethod.GET,
                                    "/2/default/bean1/john/1234?age=25&manager=true&gender=MALE")
                                .set(AsciiString.of("x-user-permission"), "permission1,permission2")
                                .set(AsciiString.of("x-client-name"), "TestClient");

        response = client.execute(AggregatedHttpMessage.of(reqHeaders)).aggregate().join();
        assertThat(response.headers().status()).isEqualTo(HttpStatus.OK);
        assertThat(response.content().toStringUtf8()).isEqualTo(expectedResponseContent);

        // Bad Request: age=badParam
        reqHeaders = HttpHeaders.of(HttpMethod.GET,
                                    "/2/default/bean1/john/1234?age=badParam&manager=true&gender=male")
                                .set(AsciiString.of("x-user-permission"), "permission1,permission2")
                                .set(AsciiString.of("x-client-name"), "TestClient");

        response = client.execute(AggregatedHttpMessage.of(reqHeaders)).aggregate().join();
        assertThat(response.headers().status()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Bad Request: seqNum=badParam
        reqHeaders = HttpHeaders.of(HttpMethod.GET,
                                    "/2/default/bean1/john/badParam?age=25&manager=true&gender=MALE")
                                .set(AsciiString.of("x-user-permission"), "permission1,permission2")
                                .set(AsciiString.of("x-client-name"), "TestClient");

        response = client.execute(AggregatedHttpMessage.of(reqHeaders)).aggregate().join();
        assertThat(response.headers().status()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Bad Request: gender=badParam
        reqHeaders = HttpHeaders.of(HttpMethod.GET,
                                    "/2/default/bean1/john/1234?age=25&manager=true&gender=badParam")
                                .set(AsciiString.of("x-user-permission"), "permission1,permission2")
                                .set(AsciiString.of("x-client-name"), "TestClient");

        response = client.execute(AggregatedHttpMessage.of(reqHeaders)).aggregate().join();
        assertThat(response.headers().status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    public void testDefaultRequestConverter_bean2() throws Exception {
        final HttpClient client = HttpClient.of(rule.uri("/"));
        final ObjectMapper mapper = new ObjectMapper();

        AggregatedHttpMessage response;

        // test for RequestBean2
        final RequestBean2 expectedRequestBean = new RequestBean2(98765L, "abcd-efgh");
        expectedRequestBean.userName = "john";
        expectedRequestBean.age = 25;
        expectedRequestBean.gender = MALE;
        expectedRequestBean.permissions = Arrays.asList("permission1", "permission2");
        expectedRequestBean.clientName = "TestClient";

        final String expectedResponseContent = mapper.writeValueAsString(expectedRequestBean);

        // Normal Request: POST + Form Data
        final HttpData formData = HttpData.ofAscii("age=25&gender=male");
        HttpHeaders reqHeaders = HttpHeaders.of(HttpMethod.POST, "/2/default/bean2/john/98765")
                                            .set(AsciiString.of("x-user-permission"), "permission1,permission2")
                                            .set(AsciiString.of("x-client-name"), "TestClient")
                                            .set(AsciiString.of("uid"), "abcd-efgh")
                                            .contentType(MediaType.FORM_DATA);

        response = client.execute(AggregatedHttpMessage.of(reqHeaders, formData)).aggregate().join();
        assertThat(response.headers().status()).isEqualTo(HttpStatus.OK);
        assertThat(response.content().toStringUtf8()).isEqualTo(expectedResponseContent);

        // Normal Request: GET + Query String
        reqHeaders = HttpHeaders.of(HttpMethod.GET,
                                    "/2/default/bean2/john?age=25&gender=MALE&serialNo=98765")
                                .set(AsciiString.of("x-user-permission"), "permission1,permission2")
                                .set(AsciiString.of("x-client-name"), "TestClient")
                                .set(AsciiString.of("uid"), "abcd-efgh");

        response = client.execute(AggregatedHttpMessage.of(reqHeaders)).aggregate().join();
        assertThat(response.headers().status()).isEqualTo(HttpStatus.OK);
        assertThat(response.content().toStringUtf8()).isEqualTo(expectedResponseContent);
    }

    @Test
    public void testDefaultRequestConverter_bean3() throws Exception {
        final HttpClient client = HttpClient.of(rule.uri("/"));
        final ObjectMapper mapper = new ObjectMapper();

        AggregatedHttpMessage response;

        // test for RequestBean3
        final RequestBean3 expectedRequestBean = new RequestBean3(3349);
        expectedRequestBean.userName = "john";
        expectedRequestBean.age = 25;
        expectedRequestBean.gender = MALE;
        expectedRequestBean.permissions = Arrays.asList("permission1", "permission2");
        expectedRequestBean.clientName = "TestClient";

        final String expectedResponseContent = mapper.writeValueAsString(expectedRequestBean);

        // Normal Request: POST + Form Data
        final HttpData formData = HttpData.ofAscii("age=25&gender=male");
        HttpHeaders reqHeaders = HttpHeaders.of(HttpMethod.POST, "/2/default/bean3/john/3349")
                                            .set(AsciiString.of("x-user-permission"), "permission1,permission2")
                                            .set(AsciiString.of("x-client-name"), "TestClient")
                                            .contentType(MediaType.FORM_DATA);

        response = client.execute(AggregatedHttpMessage.of(reqHeaders, formData)).aggregate().join();
        assertThat(response.headers().status()).isEqualTo(HttpStatus.OK);
        assertThat(response.content().toStringUtf8()).isEqualTo(expectedResponseContent);

        // Normal Request: GET + Query String
        reqHeaders = HttpHeaders.of(HttpMethod.GET,
                                    "/2/default/bean3/john?age=25&gender=MALE&departmentNo=3349")
                                .set(AsciiString.of("x-user-permission"), "permission1,permission2")
                                .set(AsciiString.of("x-client-name"), "TestClient");

        response = client.execute(AggregatedHttpMessage.of(reqHeaders)).aggregate().join();
        assertThat(response.headers().status()).isEqualTo(HttpStatus.OK);
        assertThat(response.content().toStringUtf8()).isEqualTo(expectedResponseContent);
    }

    @Test
    public void testDefaultRequestConverter_json() throws Exception {
        final HttpClient client = HttpClient.of(rule.uri("/"));
        final ObjectMapper mapper = new ObjectMapper();

        AggregatedHttpMessage response;

        final RequestJsonObj1 obj1 = new RequestJsonObj1(1, "abc");
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

        final String invalidJson = "{\"foo:\"bar\"}"; // should be \"foo\"
        response = client.execute(AggregatedHttpMessage.of(HttpMethod.POST, "/2/default/invalidJson",
                                                           MediaType.JSON_UTF_8, invalidJson))
                         .aggregate().join();
        assertThat(response.headers().status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    public void testDefaultRequestConverter_binary() throws Exception {
        final HttpClient client = HttpClient.of(rule.uri("/"));

        final AggregatedHttpMessage response;

        final byte[] binary = { 0x00, 0x01, 0x02 };
        response = client.execute(AggregatedHttpMessage.of(HttpMethod.POST, "/2/default/binary",
                                                           MediaType.OCTET_STREAM, binary))
                         .aggregate().join();
        assertThat(response.headers().status()).isEqualTo(HttpStatus.OK);
        assertThat(response.content().array()).isEqualTo(binary);
    }

    @Test
    public void testDefaultRequestConverter_text() throws Exception {
        final HttpClient client = HttpClient.of(rule.uri("/"));

        AggregatedHttpMessage response;

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
    }
}
