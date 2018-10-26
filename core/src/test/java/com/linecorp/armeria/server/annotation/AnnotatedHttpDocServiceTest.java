/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.server.annotation;

import static com.linecorp.armeria.server.annotation.AnnotatedHttpDocServicePlugin.toTypeSignature;
import static com.linecorp.armeria.server.docs.FieldRequirement.REQUIRED;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.ClassRule;
import org.junit.Test;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.TestConverters.UnformattedStringConverterFunction;
import com.linecorp.armeria.server.docs.DocServiceBuilder;
import com.linecorp.armeria.server.docs.EndpointInfo;
import com.linecorp.armeria.server.docs.EndpointInfoBuilder;
import com.linecorp.armeria.server.docs.FieldInfo;
import com.linecorp.armeria.server.docs.MethodInfo;
import com.linecorp.armeria.testing.server.ServerRule;

import io.netty.util.AsciiString;

public class AnnotatedHttpDocServiceTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    private static final HttpHeaders EXAMPLE_HEADERS_ALL = HttpHeaders.of(AsciiString.of("a"), "b");
    private static final HttpHeaders EXAMPLE_HEADERS_SERVICE = HttpHeaders.of(AsciiString.of("c"), "d");
    private static final HttpHeaders EXAMPLE_HEADERS_METHOD = HttpHeaders.of(AsciiString.of("e"), "f");

    @ClassRule
    public static final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService("/service", new MyService());
            sb.serviceUnder("/docs", new DocServiceBuilder()
                    .exampleHttpHeaders(EXAMPLE_HEADERS_ALL)
                    .exampleHttpHeaders(MyService.class, EXAMPLE_HEADERS_SERVICE)
                    .exampleHttpHeaders(MyService.class, "pathParam", EXAMPLE_HEADERS_METHOD)
                    .exampleRequestForMethod(MyService.class, "pathParam",
                                             ImmutableList.of(mapper.readTree(
                                                     "{\"hello2\":\"arm\", \"hello4\":\"eria\"}")))
                    .build());
        }
    };

    @Test
    public void jsonSpecification() {
        final Map<Class<?>, Set<MethodInfo>> methodInfos = new HashMap<>();
        addFooMethodInfo(methodInfos);
        addFooAllMethodInfos(methodInfos);
        addPathParamMethodInfo(methodInfos);
        addRegexMethodInfo(methodInfos);
        addPrefixMethodInfo(methodInfos);
        addConsumesMethodInfo(methodInfos);

        final JsonNode expectedJson = mapper.valueToTree(AnnotatedHttpDocServicePlugin.generate(methodInfos));
        addExamples(expectedJson);

        final HttpClient client = HttpClient.of(server.uri("/"));
        final AggregatedHttpMessage msg = client.get("/docs/specification.json").aggregate().join();
        assertThat(msg.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(msg.content().toStringUtf8()).isEqualTo(expectedJson);
    }

    private static void addFooMethodInfo(Map<Class<?>, Set<MethodInfo>> methodInfos) {
        final EndpointInfo endpoint = new EndpointInfoBuilder("*", "/service/foo")
                .defaultMimeType(MediaType.JSON_UTF_8).build();
        final List<FieldInfo> fieldInfos = ImmutableList.of(
                new FieldInfo("header", REQUIRED, toTypeSignature(int.class)),
                new FieldInfo("query", REQUIRED, toTypeSignature(long.class)));
        final MethodInfo methodInfo = new MethodInfo(
                "foo", toTypeSignature(String.class), fieldInfos, ImmutableList.of(),
                ImmutableList.of(endpoint), HttpMethod.GET, null);
        methodInfos.computeIfAbsent(MyService.class, unused -> new HashSet<>()).add(methodInfo);
    }

    private static void addFooAllMethodInfos(Map<Class<?>, Set<MethodInfo>> methodInfos) {
        final EndpointInfo endpoint = new EndpointInfoBuilder("*", "/service/fooAll")
                .defaultMimeType(MediaType.JSON_UTF_8).build();
        Stream.of(HttpMethod.values())
              .filter(httpMethod -> httpMethod != HttpMethod.CONNECT && httpMethod != HttpMethod.UNKNOWN)
              .forEach(httpMethod -> {
                  final MethodInfo methodInfo =
                          new MethodInfo("fooAll", toTypeSignature(String.class), ImmutableList.of(),
                                         ImmutableList.of(), ImmutableList.of(endpoint), httpMethod, null);
                  methodInfos.computeIfAbsent(MyService.class, unused -> new HashSet<>()).add(methodInfo);
              });
    }

    private static void addPathParamMethodInfo(Map<Class<?>, Set<MethodInfo>> methodInfos) {
        final EndpointInfo endpoint = new EndpointInfoBuilder("*", "/service/hello1/{hello2}/hello3/{hello4}")
                .defaultMimeType(MediaType.JSON_UTF_8).build();
        final List<FieldInfo> fieldInfos = ImmutableList.of(
                new FieldInfo("hello2", REQUIRED, toTypeSignature(String.class)),
                new FieldInfo("hello4", REQUIRED, toTypeSignature(String.class)));
        final MethodInfo methodInfo = new MethodInfo(
                "pathParam", toTypeSignature(String.class), fieldInfos, ImmutableList.of(),
                ImmutableList.of(endpoint), HttpMethod.GET, null);
        methodInfos.computeIfAbsent(MyService.class, unused -> new HashSet<>()).add(methodInfo);
    }

    private void addRegexMethodInfo(Map<Class<?>, Set<MethodInfo>> methodInfos) {
        final EndpointInfo endpoint = new EndpointInfoBuilder("*", "regex:/(bar|baz)")
                .regexPathPrefix("/service/").defaultMimeType(MediaType.JSON_UTF_8).build();
        final List<FieldInfo> fieldInfos = ImmutableList.of(
                new FieldInfo("myEnum", REQUIRED, toTypeSignature(MyEnum.class)));
        final MethodInfo methodInfo = new MethodInfo(
                "regex", toTypeSignature(String.class), fieldInfos, ImmutableList.of(),
                ImmutableList.of(endpoint), HttpMethod.GET, null);
        methodInfos.computeIfAbsent(MyService.class, unused -> new HashSet<>()).add(methodInfo);
    }

    private static void addPrefixMethodInfo(Map<Class<?>, Set<MethodInfo>> methodInfos) {
        final EndpointInfo endpoint = new EndpointInfoBuilder("*", "prefix:/service/prefix/")
                .defaultMimeType(MediaType.JSON_UTF_8).build();
        final MethodInfo methodInfo = new MethodInfo(
                "prefix", toTypeSignature(String.class), ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(endpoint), HttpMethod.GET, null);
        methodInfos.computeIfAbsent(MyService.class, unused -> new HashSet<>()).add(methodInfo);
    }

    private static void addConsumesMethodInfo(Map<Class<?>, Set<MethodInfo>> methodInfos) {
        final EndpointInfo endpoint = new EndpointInfoBuilder("*", "/service/consumes")
                .defaultMimeType(MediaType.JSON_UTF_8).availableMimeTypes(MediaType.APPLICATION_BINARY).build();
        final MethodInfo methodInfo = new MethodInfo(
                "consumes", toTypeSignature(String.class), ImmutableList.of(),
                ImmutableList.of(toTypeSignature(MyException.class)),
                ImmutableList.of(endpoint), HttpMethod.GET, null);
        methodInfos.computeIfAbsent(MyService.class, unused -> new HashSet<>()).add(methodInfo);
    }

    private static void addExamples(JsonNode json) {
        // Add the global example.
        ((ArrayNode) json.get("exampleHttpHeaders")).add(mapper.valueToTree(EXAMPLE_HEADERS_ALL));

        json.get("services").forEach(service -> {
            // Add the service-wide examples.
            final String serviceName = service.get("name").textValue();
            final ArrayNode serviceExampleHttpHeaders = (ArrayNode) service.get("exampleHttpHeaders");
            if (MyService.class.getName().equals(serviceName)) {
                serviceExampleHttpHeaders.add(mapper.valueToTree(EXAMPLE_HEADERS_SERVICE));
            }

            // Add the method-specific examples.
            service.get("methods").forEach(method -> {
                final String methodName = method.get("name").textValue();
                final ArrayNode exampleHttpHeaders = (ArrayNode) method.get("exampleHttpHeaders");
                if (MyService.class.getName().equals(serviceName) &&
                    "pathParam".equals(methodName)) {
                    exampleHttpHeaders.add(mapper.valueToTree(EXAMPLE_HEADERS_METHOD));
                    final ArrayNode exampleRequests = (ArrayNode) method.get("exampleRequests");
                    exampleRequests.add('{' + System.lineSeparator() +
                                        "  \"hello2\" : \"arm\"," + System.lineSeparator() +
                                        "  \"hello4\" : \"eria\"" + System.lineSeparator() +
                                        '}');
                }
            });
        });
    }

    @ResponseConverter(UnformattedStringConverterFunction.class)
    private static class MyService {

        @Get("/foo")
        public String foo(@Header("header") int header, @Param("query") long query) {
            return "foo";
        }

        @Options
        @Get
        @Head
        @Post
        @Put
        @Patch
        @Delete
        @Trace
        @Path("/fooAll")
        public String fooAll() {
            return "fooAll";
        }

        @Get("/hello1/:hello2/hello3/:hello4")
        public String pathParam(@Param("hello2") String hello2, @Param("hello4") String hello4) {
            return "pathParam";
        }

        @Get("regex:/(bar|baz)")
        public String regex(@Param("myEnum") MyEnum myEnum) {
            return myEnum.toString();
        }

        @Get("prefix:/prefix")
        public String prefix() {
            return "prefix";
        }

        @Get("/consumes")
        @ConsumesBinary
        public String consumes() throws MyException {
            throw new MyException();
        }
    }

    private enum MyEnum {
        A,
        B,
        C
    }

    private static class MyException extends Exception {
        static final long serialVersionUID = -3387516993124229948L;
        boolean myExceptionField;
        RequestJsonObj1 jsonObj1;
    }

    private static class RequestJsonObj1 {
        private final int intVal;
        private final String strVal;

        @JsonCreator
        RequestJsonObj1(@JsonProperty("intVal") int intVal,
                        @JsonProperty("strVal") String strVal) {
            this.intVal = intVal;
            this.strVal = strVal;
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
}
