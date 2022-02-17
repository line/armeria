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

package com.linecorp.armeria.internal.server.annotation;

import static com.linecorp.armeria.internal.server.annotation.AnnotatedServiceRequestConverterTest.Gender.MALE;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.lang.reflect.ParameterizedType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.server.annotation.AnnotatedServiceRequestConverterTest.MyService3.CompositeRequestBean1;
import com.linecorp.armeria.internal.server.annotation.AnnotatedServiceRequestConverterTest.MyService3.CompositeRequestBean2;
import com.linecorp.armeria.internal.server.annotation.AnnotatedServiceRequestConverterTest.MyService3.CompositeRequestBean3;
import com.linecorp.armeria.internal.server.annotation.AnnotatedServiceRequestConverterTest.MyService3.CompositeRequestBean4;
import com.linecorp.armeria.internal.server.annotation.AnnotatedServiceRequestConverterTest.MyService3.CompositeRequestBean5;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
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
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class AnnotatedServiceRequestConverterTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService("/1", new MyService1(), LoggingService.newDecorator());
            sb.annotatedService("/2", new MyService2(), LoggingService.newDecorator());
            sb.annotatedService("/3", new MyService3(), LoggingService.newDecorator());
        }
    };

    @ResponseConverter(UnformattedStringConverterFunction.class)
    @RequestConverter(TestRequestConverter1.class)
    public static class MyService1 {

        @Post("/convert1")
        public String convert1(RequestJsonObj1 obj1) {
            assertThat(obj1).isNotNull();
            return obj1.toString();
        }

        @Post("/convert2")
        @RequestConverter(TestRequestConverter2.class)
        @RequestConverter(TestRequestConverter1A.class)
        @RequestConverter(TestRequestConverter1.class)
        public String convert2(RequestJsonObj1 obj1) {
            assertThat(obj1).isNotNull();
            return obj1.toString();
        }

        @Post("/convert3")
        public String convert3(@RequestConverter(TestRequestConverter1.class) Optional<RequestJsonObj1> obj1,
                               @RequestConverter(TestRequestConverter2.class) Optional<RequestJsonObj2> obj2) {
            assertThat(obj1.isPresent()).isTrue();
            assertThat(obj2.isPresent()).isTrue();
            return obj2.get().strVal();
        }

        @Post("/convert4")
        @RequestConverter(NullReturningConverter.class)
        public void convert4(Optional<String> optional, @Nullable String nullable) {
            assertThat(optional).isEmpty();
            assertThat(nullable).isNull();
        }

        @Post("/convert5")
        @RequestConverter(NullReturningConverter.class)
        public void convert5(String nonnull) {
            fail("Should not reach here");
        }
    }

    @ResponseConverter(ByteArrayConverterFunction.class)
    @ResponseConverter(UnformattedStringConverterFunction.class)
    public static class MyService2 {
        private final ObjectMapper mapper = new ObjectMapper();

        @Post("/default/bean1/{userName}/{seqNum}")
        public String defaultBean1ForPost(RequestBean1 bean1)
                throws JsonProcessingException {
            assertThat(bean1).isNotNull();
            bean1.validate();
            return mapper.writeValueAsString(bean1);
        }

        @Get("/default/bean1/{userName}/{seqNum}")
        public String defaultBean1ForGet(RequestBean1 bean1)
                throws JsonProcessingException {
            assertThat(bean1).isNotNull();
            bean1.validate();
            return mapper.writeValueAsString(bean1);
        }

        @Post("/default/bean2/{userName}/{serialNo}")
        public String defaultBean2ForPost(RequestBean2 bean2)
                throws JsonProcessingException {
            assertThat(bean2).isNotNull();
            bean2.validate();
            return mapper.writeValueAsString(bean2);
        }

        @Get("/default/bean2/{userName}")
        public String defaultBean2ForGet(RequestBean2 bean2)
                throws JsonProcessingException {
            assertThat(bean2).isNotNull();
            bean2.validate();
            return mapper.writeValueAsString(bean2);
        }

        @Post("/default/bean3/{userName}/{departmentNo}")
        public String defaultBean3ForPost(RequestBean3 bean3)
                throws JsonProcessingException {
            assertThat(bean3).isNotNull();
            bean3.validate();
            return mapper.writeValueAsString(bean3);
        }

        @Get("/default/bean3/{userName}")
        public String defaultBean3ForGet(RequestBean3 bean3)
                throws JsonProcessingException {
            assertThat(bean3).isNotNull();
            bean3.validate();
            return mapper.writeValueAsString(bean3);
        }

        @Get("/default/bean4")
        public String defaultBean4(RequestBean4 bean4)
                throws JsonProcessingException {
            assertThat(bean4).isNotNull();
            return mapper.writeValueAsString(bean4);
        }

        @Post("/default/json")
        public String defaultJson(RequestJsonObj1 obj1,
                                  RequestJsonObj2 obj2) {
            assertThat(obj1).isNotNull();
            assertThat(obj2).isNotNull();
            return obj2.strVal();
        }

        @Post("/default/invalidJson")
        public String invalidJson(JsonNode node) {
            // Should never reach here because we are sending invalid JSON.
            throw new Error();
        }

        @Post("/default/binary")
        public byte[] defaultBinary(HttpData obj1,
                                    byte[] obj2) {
            assertThat(obj1).isNotNull();
            assertThat(obj2).isNotNull();
            // Actually they have the same byte array.
            assertThat(obj1.array()).isSameAs(obj2);
            return obj2;
        }

        @Post("/default/text")
        public String defaultText(String obj1) {
            assertThat(obj1).isNotNull();
            return obj1;
        }

        @Get("/default/uuid/:uuid")
        public UUID defaultUUID(@Param UUID uuid) {
            assertThat(uuid).isNotNull();
            return uuid;
        }

        @Post("/default/time")
        public String defaultTimeBean(TimeBean timeBean) {
            assertThat(timeBean).isNotNull();
            timeBean.validate();
            return timeBean.toString();
        }
    }

    static class MyService3 {
        @Get("/composite1/:age")
        public String composite1(CompositeRequestBean1 bean) {
            return bean.getClass().getSimpleName() + ':' + bean.alice.age + ':' + bean.bob.age;
        }

        static class CompositeRequestBean1 {
            private final Alice alice;
            private Bob bob;

            @RequestConverter(AliceRequestConverter.class)
            CompositeRequestBean1(Alice alice) {
                this.alice = alice;
            }

            @RequestConverter(BobRequestConverter.class)
            void setBob(Bob bob) {
                this.bob = bob;
            }
        }

        @Get("/composite2/:age")
        public String composite2(CompositeRequestBean2 bean) {
            return bean.getClass().getSimpleName() + ':' + bean.alice.age + ':' + bean.bob.age;
        }

        @RequestConverter(AliceRequestConverter.class)
        static class CompositeRequestBean2 {
            private Alice alice;
            private Bob bob;

            void initialize(@RequestObject Alice alice, @RequestConverter(BobRequestConverter.class) Bob bob) {
                this.alice = alice;
                this.bob = bob;
            }
        }

        @Get("/composite3/:age")
        public String composite3(CompositeRequestBean3 bean) {
            return bean.getClass().getSimpleName() + ':' + bean.alice.age + ':' + bean.bob.age;
        }

        @SuppressWarnings("checkstyle:all")
        @RequestConverter(AliceRequestConverter.class)
        @RequestConverter(BobRequestConverter.class)
        static class CompositeRequestBean3 {
            @RequestObject
            protected Alice alice;
            @RequestObject
            protected Bob bob;
        }

        @Get("/composite4/:age")
        public String composite4(CompositeRequestBean4 bean) {
            return bean.getClass().getSimpleName() + ':' + bean.alice.age + ':' + bean.bob.age;
        }

        static class CompositeRequestBean4 {
            @RequestConverter(AliceRequestConverter.class)
            private Alice alice;
            @RequestConverter(BobRequestConverter.class)
            private Bob bob;
        }

        @Post("/composite5/:age")
        public String composite5(CompositeRequestBean5 bean) {
            return bean.getClass().getSimpleName() + ':' + bean.alice.age + ':' + bean.bob.age + ':' +
                   bean.json1.strVal + ':' + bean.json1.intVal;
        }

        @RequestConverter(AliceRequestConverter.class)
        @RequestConverter(BobRequestConverter.class)
        static class CompositeRequestBean5 extends CompositeRequestBean3 {
            @RequestObject
            private RequestJsonObj1 json1;
        }

        static class Alice {
            private final int age;

            Alice(int age) {
                this.age = age;
            }
        }

        static class Bob {
            private final int age;

            Bob(int age) {
                this.age = age;
            }
        }

        static class AliceRequestConverter implements RequestConverterFunction {
            @Nullable
            @Override
            public Object convertRequest(
                    ServiceRequestContext ctx, AggregatedHttpRequest request, Class<?> expectedResultType,
                    @Nullable ParameterizedType expectedParameterizedResultType) throws Exception {

                if (expectedResultType == Alice.class) {
                    final String age = ctx.pathParam("age");
                    assert age != null;
                    return new Alice(Integer.parseInt(age));
                }
                return RequestConverterFunction.fallthrough();
            }
        }

        static class BobRequestConverter implements RequestConverterFunction {
            @Nullable
            @Override
            public Object convertRequest(
                    ServiceRequestContext ctx, AggregatedHttpRequest request, Class<?> expectedResultType,
                    @Nullable ParameterizedType expectedParameterizedResultType) throws Exception {

                if (expectedResultType == Bob.class) {
                    final String age = ctx.pathParam("age");
                    assert age != null;
                    return new Bob(Integer.parseInt(age) * 2);
                }
                return RequestConverterFunction.fallthrough();
            }
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

    static class RequestBean4 {
        private final int foo;

        // This field is not set because the foo param is already used in the constructor.
        @Param("foo")
        private int foo1;

        private int foo2;

        private int foo3;

        RequestBean4(@Param("foo") int foo) {
            this.foo = foo;
        }

        // @Param("foo") is used redundantly,
        // but the foo2 will be injected because otherwise we cannot inject the foo3.
        public void setFoo(@Param("foo") int foo2, @Header("foo") int foo3) {
            this.foo2 = foo2;
            this.foo3 = foo3;
        }

        @JsonProperty
        public int getFoo() {
            return foo;
        }

        @JsonProperty
        public int getFoo1() {
            return foo1;
        }

        @JsonProperty
        public int getFoo2() {
            return foo2;
        }

        @JsonProperty
        public int getFoo3() {
            return foo3;
        }
    }

    static class TimeBean {
        @Param
        Duration duration;
        @Param
        Instant instant;
        @Param
        LocalDate localDate;
        @Param
        LocalDateTime localDateTime;
        @Param
        LocalTime localTime;
        @Param
        OffsetDateTime offsetDateTime;
        @Param
        OffsetTime offsetTime;
        @Param
        Period period;
        @Param
        ZonedDateTime zonedDateTime;
        @Param
        ZoneId zoneId;
        @Param
        ZoneOffset zoneOffset;

        public void validate() {
            assertThat(duration).isNotNull();
            assertThat(instant).isNotNull();
            assertThat(localDate).isNotNull();
            assertThat(localDateTime).isNotNull();
            assertThat(offsetDateTime).isNotNull();
            assertThat(offsetTime).isNotNull();
            assertThat(period).isNotNull();
            assertThat(zonedDateTime).isNotNull();
            assertThat(zoneId).isNotNull();
            assertThat(zoneOffset).isNotNull();
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("duration", duration)
                              .add("instant", instant)
                              .add("localDate", localDate)
                              .add("localDateTime", localDateTime)
                              .add("offsetDateTime", offsetDateTime)
                              .add("offsetTime", offsetTime)
                              .add("period", period)
                              .add("zonedDateTime", zonedDateTime)
                              .add("zoneId", zoneId)
                              .add("zoneOffset", zoneOffset)
                              .toString();
        }
    }

    public static class TestRequestConverter1 implements RequestConverterFunction {
        private final ObjectMapper mapper = new ObjectMapper();

        @Override
        public RequestJsonObj1 convertRequest(
                ServiceRequestContext ctx, AggregatedHttpRequest request, Class<?> expectedResultType,
                @Nullable ParameterizedType expectedParameterizedResultType) throws Exception {

            if (expectedResultType.isAssignableFrom(RequestJsonObj1.class)) {
                return mapper.readValue(request.contentUtf8(), RequestJsonObj1.class);
            }
            return RequestConverterFunction.fallthrough();
        }
    }

    public static class TestRequestConverter1A implements RequestConverterFunction {
        private final ObjectMapper mapper = new ObjectMapper();

        @Override
        public RequestJsonObj1 convertRequest(
                ServiceRequestContext ctx, AggregatedHttpRequest request, Class<?> expectedResultType,
                @Nullable ParameterizedType expectedParameterizedResultType) throws Exception {

            if (expectedResultType.isAssignableFrom(RequestJsonObj1.class)) {
                final RequestJsonObj1 obj1 = mapper.readValue(request.contentUtf8(),
                                                              RequestJsonObj1.class);
                return new RequestJsonObj1(obj1.intVal() + 1, obj1.strVal() + 'a');
            }

            return RequestConverterFunction.fallthrough();
        }
    }

    public static class TestRequestConverter2 implements RequestConverterFunction {
        @Override
        public RequestJsonObj2 convertRequest(
                ServiceRequestContext ctx, AggregatedHttpRequest request, Class<?> expectedResultType,
                @Nullable ParameterizedType expectedParameterizedResultType) throws Exception {

            if (expectedResultType.isAssignableFrom(RequestJsonObj2.class)) {
                return new RequestJsonObj2(request.headers().method().name());
            }

            return RequestConverterFunction.fallthrough();
        }
    }

    public static class NullReturningConverter implements RequestConverterFunction {
        @Nullable
        @Override
        public Object convertRequest(
                ServiceRequestContext ctx, AggregatedHttpRequest request, Class<?> expectedResultType,
                @Nullable ParameterizedType expectedParameterizedResultType) throws Exception {
            return null;
        }
    }

    @Test
    void testRequestConverter() throws Exception {
        final BlockingWebClient client = BlockingWebClient.of(server.httpUri());
        final ObjectMapper mapper = new ObjectMapper();

        AggregatedHttpResponse response;

        final RequestJsonObj1 obj1 = new RequestJsonObj1(1, "abc");
        final String content1 = mapper.writeValueAsString(obj1);

        response = client.post("/1/convert1", content1);
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo(obj1.toString());

        // The order of converters
        final RequestJsonObj1 obj1a = new RequestJsonObj1(2, "abca");
        response = client.post("/1/convert2", content1);
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo(obj1a.toString());

        // Multiple @RequestConverter annotated parameters
        response = client.post("/1/convert3", content1);
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo(HttpMethod.POST.name());

        // Conversion to null
        response = client.post("/1/convert4", content1);
        assertThat(response.status()).isEqualTo(HttpStatus.OK);

        // Conversion to null that will fail with 400 Bad Request,
        // because the injection target is not annotated with @Nullable.
        response = client.post("/1/convert5", content1);
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void testDefaultRequestConverter_bean1() throws Exception {
        final BlockingWebClient client = BlockingWebClient.of(server.httpUri());
        final ObjectMapper mapper = new ObjectMapper();

        AggregatedHttpResponse response;

        // test for RequestBean1
        final RequestBean1 expectedRequestBean = new RequestBean1();
        expectedRequestBean.userName = "john";
        expectedRequestBean.age = 25;
        expectedRequestBean.gender = MALE;
        expectedRequestBean.permissions = Arrays.asList("perm1", "perm2");
        expectedRequestBean.clientName = "TestClient";
        expectedRequestBean.seqNum = 1234L;
        expectedRequestBean.manager = true;

        final String expectedResponseContent = mapper.writeValueAsString(expectedRequestBean);

        // Normal Request: POST + Form Data
        final HttpData formData = HttpData.ofAscii("age=25&manager=true&gender=male");
        RequestHeaders reqHeaders = RequestHeaders.of(HttpMethod.POST, "/2/default/bean1/john/1234",
                                                      HttpHeaderNames.of("x-user-permission"), "perm1,perm2",
                                                      HttpHeaderNames.of("x-client-name"), "TestClient",
                                                      HttpHeaderNames.CONTENT_TYPE, MediaType.FORM_DATA);

        response = client.execute(AggregatedHttpRequest.of(reqHeaders, formData));
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo(expectedResponseContent);

        // Normal Request: GET + Query String
        reqHeaders = RequestHeaders.of(HttpMethod.GET,
                                       "/2/default/bean1/john/1234?age=25&manager=true&gender=MALE",
                                       HttpHeaderNames.of("x-user-permission"), "perm1,perm2",
                                       HttpHeaderNames.of("x-client-name"), "TestClient");

        response = client.execute(AggregatedHttpRequest.of(reqHeaders));
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo(expectedResponseContent);

        // Bad Request: age=badParam
        reqHeaders = RequestHeaders.of(HttpMethod.GET,
                                       "/2/default/bean1/john/1234?age=badParam&manager=true&gender=male",
                                       HttpHeaderNames.of("x-user-permission"), "perm1,perm2",
                                       HttpHeaderNames.of("x-client-name"), "TestClient");

        response = client.execute(AggregatedHttpRequest.of(reqHeaders));
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Bad Request: seqNum=badParam
        reqHeaders = RequestHeaders.of(HttpMethod.GET,
                                       "/2/default/bean1/john/badParam?age=25&manager=true&gender=MALE",
                                       HttpHeaderNames.of("x-user-permission"), "perm1,perm2",
                                       HttpHeaderNames.of("x-client-name"), "TestClient");

        response = client.execute(AggregatedHttpRequest.of(reqHeaders));
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Bad Request: gender=badParam
        reqHeaders = RequestHeaders.of(HttpMethod.GET,
                                       "/2/default/bean1/john/1234?age=25&manager=true&gender=badParam",
                                       HttpHeaderNames.of("x-user-permission"), "perm1,perm2",
                                       HttpHeaderNames.of("x-client-name"), "TestClient");

        response = client.execute(AggregatedHttpRequest.of(reqHeaders));
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void testDefaultRequestConverter_bean2() throws Exception {
        final BlockingWebClient client = BlockingWebClient.of(server.httpUri());
        final ObjectMapper mapper = new ObjectMapper();

        AggregatedHttpResponse response;

        // test for RequestBean2
        final RequestBean2 expectedRequestBean = new RequestBean2(98765L, "abcd-efgh");
        expectedRequestBean.userName = "john";
        expectedRequestBean.age = 25;
        expectedRequestBean.gender = MALE;
        expectedRequestBean.permissions = Arrays.asList("perm1", "perm2");
        expectedRequestBean.clientName = "TestClient";

        final String expectedResponseContent = mapper.writeValueAsString(expectedRequestBean);

        // Normal Request: POST + Form Data
        final HttpData formData = HttpData.ofAscii("age=25&gender=male");
        RequestHeaders reqHeaders = RequestHeaders.of(HttpMethod.POST, "/2/default/bean2/john/98765",
                                                      HttpHeaderNames.of("x-user-permission"), "perm1,perm2",
                                                      HttpHeaderNames.of("x-client-name"), "TestClient",
                                                      HttpHeaderNames.of("uid"), "abcd-efgh",
                                                      HttpHeaderNames.CONTENT_TYPE, MediaType.FORM_DATA);

        response = client.execute(AggregatedHttpRequest.of(reqHeaders, formData));
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo(expectedResponseContent);

        // Normal Request: GET + Query String
        reqHeaders = RequestHeaders.of(HttpMethod.GET,
                                       "/2/default/bean2/john?age=25&gender=MALE&serialNo=98765",
                                       HttpHeaderNames.of("x-user-permission"), "perm1,perm2",
                                       HttpHeaderNames.of("x-client-name"), "TestClient",
                                       HttpHeaderNames.of("uid"), "abcd-efgh");

        response = client.execute(AggregatedHttpRequest.of(reqHeaders));
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo(expectedResponseContent);
    }

    @Test
    void testDefaultRequestConverter_bean3() throws Exception {
        final BlockingWebClient client = BlockingWebClient.of(server.httpUri());
        final ObjectMapper mapper = new ObjectMapper();

        AggregatedHttpResponse response;

        // test for RequestBean3
        final RequestBean3 expectedRequestBean = new RequestBean3(3349);
        expectedRequestBean.userName = "john";
        expectedRequestBean.age = 25;
        expectedRequestBean.gender = MALE;
        expectedRequestBean.permissions = Arrays.asList("perm1", "perm2");
        expectedRequestBean.clientName = "TestClient";

        final String expectedResponseContent = mapper.writeValueAsString(expectedRequestBean);

        // Normal Request: POST + Form Data
        final HttpData formData = HttpData.ofAscii("age=25&gender=male");
        RequestHeaders reqHeaders = RequestHeaders.of(HttpMethod.POST, "/2/default/bean3/john/3349",
                                                      HttpHeaderNames.of("x-user-permission"), "perm1,perm2",
                                                      HttpHeaderNames.of("x-client-name"), "TestClient",
                                                      HttpHeaderNames.CONTENT_TYPE, MediaType.FORM_DATA);

        response = client.execute(AggregatedHttpRequest.of(reqHeaders, formData));
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo(expectedResponseContent);

        // Normal Request: GET + Query String
        reqHeaders = RequestHeaders.of(HttpMethod.GET,
                                       "/2/default/bean3/john?age=25&gender=MALE&departmentNo=3349",
                                       HttpHeaderNames.of("x-user-permission"), "perm1,perm2",
                                       HttpHeaderNames.of("x-client-name"), "TestClient");

        response = client.execute(AggregatedHttpRequest.of(reqHeaders));
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo(expectedResponseContent);
    }

    @Test
    void testDefaultRequestConverter_bean4() throws Exception {
        final BlockingWebClient client = BlockingWebClient.of(server.httpUri() + "/3");
        final ObjectMapper mapper = new ObjectMapper();

        AggregatedHttpResponse response;

        response = client.get("/composite1/10");
        assertThat(response.contentUtf8())
                .isEqualTo(CompositeRequestBean1.class.getSimpleName() + ":10:20");

        response = client.get("/composite2/10");
        assertThat(response.contentUtf8())
                .isEqualTo(CompositeRequestBean2.class.getSimpleName() + ":10:20");

        response = client.get("/composite3/10");
        assertThat(response.contentUtf8())
                .isEqualTo(CompositeRequestBean3.class.getSimpleName() + ":10:20");

        response = client.get("/composite4/10");
        assertThat(response.contentUtf8())
                .isEqualTo(CompositeRequestBean4.class.getSimpleName() + ":10:20");

        final RequestJsonObj1 obj1 = new RequestJsonObj1(1, "abc");
        final String content1 = mapper.writeValueAsString(obj1);

        response = client.execute(AggregatedHttpRequest.of(
                HttpMethod.POST, "/composite5/10", MediaType.JSON_UTF_8, content1));
        assertThat(response.contentUtf8())
                .isEqualTo(CompositeRequestBean5.class.getSimpleName() + ":10:20:" +
                           obj1.strVal() + ':' + obj1.intVal());
    }

    @Test
    void testDefaultRequestConverter_json() throws Exception {
        final BlockingWebClient client = BlockingWebClient.of(server.httpUri());
        final ObjectMapper mapper = new ObjectMapper();

        AggregatedHttpResponse response;

        final RequestJsonObj1 obj1 = new RequestJsonObj1(1, "abc");
        final String content1 = mapper.writeValueAsString(obj1);

        // MediaType.JSON_UTF_8
        response = client.execute(AggregatedHttpRequest.of(HttpMethod.POST, "/2/default/json",
                                                           MediaType.JSON_UTF_8, content1));
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("abc");

        // MediaType.JSON_PATCH
        // obj1 is not a json-patch+json format, but just check if it's converted by
        // DefaultRequestConverter when it is valid JSON format
        response = client.execute(AggregatedHttpRequest.of(HttpMethod.POST, "/2/default/json",
                                                           MediaType.JSON_PATCH, content1));
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("abc");

        // "application/vnd.api+json"
        response = client.execute(AggregatedHttpRequest.of(HttpMethod.POST, "/2/default/json",
                                                           MediaType.create("application", "vnd.api+json"),
                                                           content1));
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("abc");

        final String invalidJson = "{\"foo:\"bar\"}"; // should be \"foo\"
        response = client.execute(AggregatedHttpRequest.of(HttpMethod.POST, "/2/default/invalidJson",
                                                           MediaType.JSON_UTF_8, invalidJson));
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void testDefaultRequestConverter_binary() throws Exception {
        final BlockingWebClient client = BlockingWebClient.of(server.httpUri());

        final AggregatedHttpResponse response;

        final byte[] binary = { 0x00, 0x01, 0x02 };
        response = client.execute(AggregatedHttpRequest.of(HttpMethod.POST, "/2/default/binary",
                                                           MediaType.OCTET_STREAM, binary));
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.content().array()).isEqualTo(binary);
    }

    @Test
    void testDefaultRequestConverter_text() throws Exception {
        final BlockingWebClient client = BlockingWebClient.of(server.httpUri());

        AggregatedHttpResponse response;

        final byte[] utf8 = "¥".getBytes(StandardCharsets.UTF_8);
        response = client.execute(AggregatedHttpRequest.of(HttpMethod.POST, "/2/default/text",
                                                           MediaType.PLAIN_TEXT_UTF_8, utf8));
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.content().array()).isEqualTo(utf8);

        final MediaType textPlain = MediaType.create("text", "plain");
        response = client.execute(AggregatedHttpRequest.of(HttpMethod.POST, "/2/default/text",
                                                           textPlain, utf8));
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        // Response is encoded as UTF-8.
        assertThat(response.content().array()).isEqualTo(utf8);
    }

    @Test
    void testDefaultRequestConverter_uuid() {
        final BlockingWebClient client = BlockingWebClient.of(server.httpUri());
        final UUID uuid = UUID.randomUUID();
        final AggregatedHttpResponse response = client.get("/2/default/uuid/" + uuid);
        assertThat(response.contentUtf8()).isEqualTo(uuid.toString());
    }

    @Test
    void testDefaultRequestConverter_timeBean() {
        final WebClient client = WebClient.of(server.httpUri());

        final TimeBean timeBean = new TimeBean();
        timeBean.duration = Duration.ofDays(1);
        timeBean.instant = Instant.now();
        timeBean.localDate = LocalDate.now();
        timeBean.localDateTime = LocalDateTime.now();
        timeBean.localTime = LocalTime.now();
        timeBean.offsetDateTime = OffsetDateTime.now();
        timeBean.offsetTime = OffsetTime.now();
        timeBean.period = Period.of(1, 2, 3);
        timeBean.zonedDateTime = ZonedDateTime.now();
        timeBean.zoneId = ZoneId.systemDefault();
        timeBean.zoneOffset = ZoneOffset.ofHours(1);

        final QueryParams queryParams = QueryParams.builder()
                                                   .add("duration", timeBean.duration.toString())
                                                   .add("instant", timeBean.instant.toString())
                                                   .add("localDate", timeBean.localDate.toString())
                                                   .add("localDateTime", timeBean.localDateTime.toString())
                                                   .add("localTime", timeBean.localTime.toString())
                                                   .add("offsetDateTime", timeBean.offsetDateTime.toString())
                                                   .add("offsetTime", timeBean.offsetTime.toString())
                                                   .add("period", timeBean.period.toString())
                                                   .add("zonedDateTime", timeBean.zonedDateTime.toString())
                                                   .add("zoneId", timeBean.zoneId.toString())
                                                   .add("zoneOffset", timeBean.zoneOffset.toString())
                                                   .build();

        final AggregatedHttpResponse response = client.execute(HttpRequest.of(HttpMethod.POST,
                                                                              "/2/default/time",
                                                                              MediaType.FORM_DATA,
                                                                              queryParams.toQueryString()))
                                                      .aggregate()
                                                      .join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo(timeBean.toString());
    }

    @Test
    void testRedundantlyUsedParameters() throws Exception {
        final BlockingWebClient client = BlockingWebClient.of(server.httpUri());
        final ObjectMapper mapper = new ObjectMapper();
        final RequestBean4 expectedRequestBean = new RequestBean4(100);
        expectedRequestBean.foo2 = 100;
        expectedRequestBean.foo3 = 200;
        final String expectedResponseContent = mapper.writeValueAsString(expectedRequestBean);

        final RequestHeaders reqHeaders = RequestHeaders.of(HttpMethod.GET, "/2/default/bean4?foo=100",
                                                            HttpHeaderNames.of("foo"), 200);

        final AggregatedHttpResponse response = client.execute(reqHeaders);
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo(expectedResponseContent);
    }
}
