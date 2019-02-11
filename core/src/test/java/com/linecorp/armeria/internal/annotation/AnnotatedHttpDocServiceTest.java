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

package com.linecorp.armeria.internal.annotation;

import static com.linecorp.armeria.internal.annotation.AnnotatedHttpDocServicePlugin.INT;
import static com.linecorp.armeria.internal.annotation.AnnotatedHttpDocServicePlugin.LONG;
import static com.linecorp.armeria.internal.annotation.AnnotatedHttpDocServicePlugin.STRING;
import static com.linecorp.armeria.internal.annotation.AnnotatedHttpDocServicePlugin.toTypeSignature;
import static com.linecorp.armeria.internal.annotation.AnnotatedHttpDocServicePluginTest.compositeBean;
import static com.linecorp.armeria.server.docs.FieldLocation.PATH;
import static com.linecorp.armeria.server.docs.FieldLocation.QUERY;
import static com.linecorp.armeria.server.docs.FieldRequirement.REQUIRED;
import static net.javacrumbs.jsonunit.core.Option.IGNORING_ARRAY_ORDER;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Array;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import org.junit.ClassRule;
import org.junit.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.internal.annotation.AnnotatedHttpDocServicePluginTest.CompositeBean;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.TestConverters.UnformattedStringConverterFunction;
import com.linecorp.armeria.server.annotation.ConsumesBinary;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.Description;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Head;
import com.linecorp.armeria.server.annotation.Header;
import com.linecorp.armeria.server.annotation.Options;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Patch;
import com.linecorp.armeria.server.annotation.Path;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.Put;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.armeria.server.annotation.Trace;
import com.linecorp.armeria.server.docs.DocServiceBuilder;
import com.linecorp.armeria.server.docs.EndpointInfo;
import com.linecorp.armeria.server.docs.EndpointInfoBuilder;
import com.linecorp.armeria.server.docs.FieldInfo;
import com.linecorp.armeria.server.docs.FieldInfoBuilder;
import com.linecorp.armeria.server.docs.FieldLocation;
import com.linecorp.armeria.server.docs.MethodInfo;
import com.linecorp.armeria.server.docs.TypeSignature;
import com.linecorp.armeria.testing.server.ServerRule;

public class AnnotatedHttpDocServiceTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    private static final HttpHeaders EXAMPLE_HEADERS_ALL = HttpHeaders.of(HttpHeaderNames.of("a"), "b");
    private static final HttpHeaders EXAMPLE_HEADERS_SERVICE = HttpHeaders.of(HttpHeaderNames.of("c"), "d");
    private static final HttpHeaders EXAMPLE_HEADERS_METHOD = HttpHeaders.of(HttpHeaderNames.of("e"), "f");

    private static final boolean DEMO_MODE = false;

    @ClassRule
    public static final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            if (DEMO_MODE) {
                sb.http(8080);
            }
            sb.annotatedService("/service", new MyService());
            sb.serviceUnder("/docs", new DocServiceBuilder()
                    .exampleHttpHeaders(EXAMPLE_HEADERS_ALL)
                    .exampleHttpHeaders(MyService.class, EXAMPLE_HEADERS_SERVICE)
                    .exampleHttpHeaders(MyService.class, "pathParams", EXAMPLE_HEADERS_METHOD)
                    .exampleRequestForMethod(MyService.class, "pathParams",
                                             ImmutableList.of(mapper.readTree(
                                                     "{\"hello\":\"armeria\"}")))
                    .build());
        }
    };

    @Test
    public void jsonSpecification() throws InterruptedException {
        if (DEMO_MODE) {
            Thread.sleep(Long.MAX_VALUE);
        }
        final Map<Class<?>, Set<MethodInfo>> methodInfos = new HashMap<>();
        addFooMethodInfo(methodInfos);
        addAllMethodsMethodInfos(methodInfos);
        addIntsMethodInfo(methodInfos);
        addPathParamsMethodInfo(methodInfos);
        addRegexMethodInfo(methodInfos);
        addPrefixMethodInfo(methodInfos);
        addConsumesMethodInfo(methodInfos);
        addBeanMethodInfo(methodInfos);
        final Map<Class<?>, String> serviceDescription = ImmutableMap.of(MyService.class, "My service class");

        final JsonNode expectedJson = mapper.valueToTree(AnnotatedHttpDocServicePlugin.generate(
                serviceDescription, methodInfos));
        addExamples(expectedJson);

        final HttpClient client = HttpClient.of(server.uri("/"));
        final AggregatedHttpMessage msg = client.get("/docs/specification.json").aggregate().join();
        assertThat(msg.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(msg.contentUtf8()).when(IGNORING_ARRAY_ORDER).isEqualTo(expectedJson);
    }

    private static void addFooMethodInfo(Map<Class<?>, Set<MethodInfo>> methodInfos) {
        final EndpointInfo endpoint = new EndpointInfoBuilder("*", "exact:/service/foo")
                .availableMimeTypes(MediaType.JSON_UTF_8).build();
        final List<FieldInfo> fieldInfos = ImmutableList.of(
                new FieldInfoBuilder("header", INT).requirement(REQUIRED)
                                                   .location(FieldLocation.HEADER)
                                                   .docString("header parameter").build(),
                new FieldInfoBuilder("query", LONG).requirement(REQUIRED)
                                                   .location(QUERY)
                                                   .docString("query parameter").build());
        final MethodInfo methodInfo = new MethodInfo(
                "foo", TypeSignature.ofBase("T"), fieldInfos, ImmutableList.of(),
                ImmutableList.of(endpoint), HttpMethod.GET, "foo method");
        methodInfos.computeIfAbsent(MyService.class, unused -> new HashSet<>()).add(methodInfo);
    }

    private static void addAllMethodsMethodInfos(Map<Class<?>, Set<MethodInfo>> methodInfos) {
        final EndpointInfo endpoint = new EndpointInfoBuilder("*", "exact:/service/allMethods")
                .availableMimeTypes(MediaType.JSON_UTF_8).build();
        Stream.of(HttpMethod.values())
              .filter(httpMethod -> httpMethod != HttpMethod.CONNECT && httpMethod != HttpMethod.UNKNOWN)
              .forEach(httpMethod -> {
                  final MethodInfo methodInfo =
                          new MethodInfo("allMethods",
                                         TypeSignature.ofContainer("CompletableFuture",
                                                                   TypeSignature.ofUnresolved("")),
                                         ImmutableList.of(), ImmutableList.of(), ImmutableList.of(endpoint),
                                         httpMethod, null);
                  methodInfos.computeIfAbsent(MyService.class, unused -> new HashSet<>()).add(methodInfo);
              });
    }

    private static void addIntsMethodInfo(Map<Class<?>, Set<MethodInfo>> methodInfos) {
        final EndpointInfo endpoint = new EndpointInfoBuilder("*", "exact:/service/ints")
                .availableMimeTypes(MediaType.JSON_UTF_8).build();
        final List<FieldInfo> fieldInfos = ImmutableList.of(
                new FieldInfoBuilder("ints", TypeSignature.ofList(INT)).requirement(REQUIRED)
                                                                       .location(QUERY).build());
        final MethodInfo methodInfo = new MethodInfo(
                "ints", TypeSignature.ofList(INT),
                fieldInfos, ImmutableList.of(),
                ImmutableList.of(endpoint), HttpMethod.GET, null);
        methodInfos.computeIfAbsent(MyService.class, unused -> new HashSet<>()).add(methodInfo);
    }

    private static void addPathParamsMethodInfo(Map<Class<?>, Set<MethodInfo>> methodInfos) {
        final EndpointInfo endpoint = new EndpointInfoBuilder("*", "/service/hello1/{hello2}/hello3/{hello4}")
                .availableMimeTypes(MediaType.JSON_UTF_8).build();
        final List<FieldInfo> fieldInfos = ImmutableList.of(
                new FieldInfoBuilder("hello2", STRING).requirement(REQUIRED).location(PATH).build(),
                new FieldInfoBuilder("hello4", STRING).requirement(REQUIRED).location(PATH).build());
        final MethodInfo methodInfo = new MethodInfo(
                "pathParams", STRING, fieldInfos, ImmutableList.of(),
                ImmutableList.of(endpoint), HttpMethod.GET, null);
        methodInfos.computeIfAbsent(MyService.class, unused -> new HashSet<>()).add(methodInfo);
    }

    private static void addRegexMethodInfo(Map<Class<?>, Set<MethodInfo>> methodInfos) {
        final EndpointInfo endpoint = new EndpointInfoBuilder("*", "regex:/(bar|baz)")
                .regexPathPrefix("prefix:/service/").availableMimeTypes(MediaType.JSON_UTF_8).build();
        final List<FieldInfo> fieldInfos = ImmutableList.of(
                new FieldInfoBuilder("myEnum", toTypeSignature(MyEnum.class))
                        .requirement(REQUIRED).location(QUERY).build());
        final MethodInfo methodInfo = new MethodInfo(
                "regex", TypeSignature.ofList(TypeSignature.ofList(STRING)), fieldInfos, ImmutableList.of(),
                ImmutableList.of(endpoint), HttpMethod.GET, null);
        methodInfos.computeIfAbsent(MyService.class, unused -> new HashSet<>()).add(methodInfo);
    }

    private static void addPrefixMethodInfo(Map<Class<?>, Set<MethodInfo>> methodInfos) {
        final EndpointInfo endpoint = new EndpointInfoBuilder("*", "prefix:/service/prefix/")
                .availableMimeTypes(MediaType.JSON_UTF_8).build();
        final MethodInfo methodInfo = new MethodInfo(
                "prefix", STRING, ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(endpoint), HttpMethod.GET, null);
        methodInfos.computeIfAbsent(MyService.class, unused -> new HashSet<>()).add(methodInfo);
    }

    private static void addConsumesMethodInfo(Map<Class<?>, Set<MethodInfo>> methodInfos) {
        final EndpointInfo endpoint = new EndpointInfoBuilder("*", "exact:/service/consumes")
                .availableMimeTypes(MediaType.APPLICATION_BINARY, MediaType.JSON_UTF_8).build();
        final MethodInfo methodInfo = new MethodInfo(
                "consumes", TypeSignature.ofContainer("BiFunction", TypeSignature.ofBase("JsonNode"),
                                                      TypeSignature.ofUnresolved(""), STRING),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(endpoint), HttpMethod.GET, null);
        methodInfos.computeIfAbsent(MyService.class, unused -> new HashSet<>()).add(methodInfo);
    }

    private static void addBeanMethodInfo(Map<Class<?>, Set<MethodInfo>> methodInfos) {
        final EndpointInfo endpoint = new EndpointInfoBuilder("*", "exact:/service/bean")
                .availableMimeTypes(MediaType.JSON_UTF_8).build();
        final List<FieldInfo> fieldInfos = ImmutableList.of(compositeBean());
        final MethodInfo methodInfo = new MethodInfo(
                "bean", TypeSignature.ofBase("HttpResponse"), fieldInfos, ImmutableList.of(),
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
                    "pathParams".equals(methodName)) {
                    exampleHttpHeaders.add(mapper.valueToTree(EXAMPLE_HEADERS_METHOD));
                    final ArrayNode exampleRequests = (ArrayNode) method.get("exampleRequests");
                    exampleRequests.add('{' + System.lineSeparator() +
                                        "  \"hello\" : \"armeria\"" + System.lineSeparator() +
                                        '}');
                }
            });
        });
    }

    @Description("My service class")
    @ResponseConverter(UnformattedStringConverterFunction.class)
    private static class MyService {

        @Get("/foo")
        @Description("foo method")
        public <T> T foo(@Header @Description("header parameter") int header,
                         @Param @Description("query parameter") long query) {
            @SuppressWarnings("unchecked")
            final T result = (T) ("header: " + header + ", query: " + query);
            return result;
        }

        @Options
        @Get
        @Head
        @Post
        @Put
        @Patch
        @Delete
        @Trace
        @Path("/allMethods")
        public CompletableFuture<?> allMethods() {
            return CompletableFuture.completedFuture(HttpResponse.of("allMethods"));
        }

        @Get("/ints")
        public List<Integer> ints(@Param List<Integer> ints) {
            return ints;
        }

        @Get("/hello1/:hello2/hello3/:hello4")
        public String pathParams(@Param String hello2, @Param String hello4) {
            return hello2 + ' ' + hello4;
        }

        @Get("regex:/(bar|baz)")
        public List<String>[] regex(@Param MyEnum myEnum) {
            final MyEnum[] values = MyEnum.values();
            @SuppressWarnings("unchecked")
            final List<String>[] genericArray = (List<String>[]) Array.newInstance(List.class, values.length);
            for (int i = 0; i < genericArray.length; i++) {
                genericArray[i] = ImmutableList.of(values[i].toString());
            }
            return genericArray;
        }

        @Get("prefix:/prefix")
        public String prefix(ServiceRequestContext ctx) {
            return "prefix";
        }

        @Get("/consumes")
        @ConsumesBinary
        public BiFunction<JsonNode, ?, String> consumes() {
            return new BiFunction<JsonNode, Object, String>() {
                @Override
                public String apply(JsonNode jsonNode, Object o) {
                    return null;
                }

                @Override
                public String toString() {
                    return "consumes";
                }
            };
        }

        @Get("/bean")
        public HttpResponse bean(CompositeBean compositeBean) throws JsonProcessingException {
            final ObjectMapper mapper = new ObjectMapper();
            return HttpResponse.of(mapper.writeValueAsString(compositeBean));
        }
    }

    private enum MyEnum {
        A,
        B,
        C
    }
}
