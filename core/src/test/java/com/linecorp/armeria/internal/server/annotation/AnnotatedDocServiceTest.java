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

package com.linecorp.armeria.internal.server.annotation;

import static com.linecorp.armeria.internal.server.annotation.AnnotatedDocServicePlugin.INT;
import static com.linecorp.armeria.internal.server.annotation.AnnotatedDocServicePlugin.LONG;
import static com.linecorp.armeria.internal.server.annotation.AnnotatedDocServicePlugin.STRING;
import static com.linecorp.armeria.internal.server.annotation.AnnotatedDocServicePlugin.toTypeSignature;
import static com.linecorp.armeria.internal.server.annotation.AnnotatedDocServicePluginTest.compositeBean;
import static com.linecorp.armeria.server.docs.FieldLocation.PATH;
import static com.linecorp.armeria.server.docs.FieldLocation.QUERY;
import static com.linecorp.armeria.server.docs.FieldRequirement.REQUIRED;
import static net.javacrumbs.jsonunit.core.Option.IGNORING_ARRAY_ORDER;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Array;
import java.time.Period;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.MediaTypeNames;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.server.annotation.AnnotatedDocServicePluginTest.CompositeBean;
import com.linecorp.armeria.internal.testing.TestUtil;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.TestConverters.UnformattedStringConverterFunction;
import com.linecorp.armeria.server.annotation.Consumes;
import com.linecorp.armeria.server.annotation.ConsumesBinary;
import com.linecorp.armeria.server.annotation.ConsumesOctetStream;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.Description;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Head;
import com.linecorp.armeria.server.annotation.Header;
import com.linecorp.armeria.server.annotation.Options;
import com.linecorp.armeria.server.annotation.Order;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Patch;
import com.linecorp.armeria.server.annotation.Path;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProducesOctetStream;
import com.linecorp.armeria.server.annotation.Put;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.armeria.server.annotation.Trace;
import com.linecorp.armeria.server.docs.DescriptionInfo;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.docs.DocServiceFilter;
import com.linecorp.armeria.server.docs.EndpointInfo;
import com.linecorp.armeria.server.docs.FieldInfo;
import com.linecorp.armeria.server.docs.FieldLocation;
import com.linecorp.armeria.server.docs.Markup;
import com.linecorp.armeria.server.docs.MethodInfo;
import com.linecorp.armeria.server.docs.ServiceSpecification;
import com.linecorp.armeria.server.docs.TypeSignature;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class AnnotatedDocServiceTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    private static final HttpHeaders EXAMPLE_HEADERS_ALL = HttpHeaders.of(HttpHeaderNames.of("a"), "b");
    private static final HttpHeaders EXAMPLE_HEADERS_SERVICE = HttpHeaders.of(HttpHeaderNames.of("c"), "d");
    private static final HttpHeaders EXAMPLE_HEADERS_METHOD = HttpHeaders.of(HttpHeaderNames.of("e"), "f");
    private static final HttpHeaders JSON_LINE_HEADERS =
            HttpHeaders.of(HttpHeaderNames.CONTENT_TYPE, MediaTypeNames.JSON_LINES);

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            if (TestUtil.isDocServiceDemoMode()) {
                sb.http(8080);
            }
            sb.annotatedService("/service", new MyService());
            sb.serviceUnder("/proxy", new ProxyService("/proxy"));
            sb.serviceUnder("/proxy2/nested", new ProxyService("/proxy2/nested"));
            sb.serviceUnder("/docs",
                            DocService.builder()
                                      .exampleHeaders(EXAMPLE_HEADERS_ALL)
                                      .exampleHeaders(MyService.class, EXAMPLE_HEADERS_SERVICE)
                                      .exampleHeaders(MyService.class, "pathParams", EXAMPLE_HEADERS_METHOD)
                                      .exampleHeaders(MyService.class, "jsonLines", JSON_LINE_HEADERS)
                                      .examplePaths(MyService.class, "pathParams",
                                                    "/service/hello1/foo/hello3/bar")
                                      .exampleQueries(MyService.class, "foo", "query=10", "query=20")
                                      .exampleRequests(MyService.class, "pathParams",
                                                       ImmutableList.of(
                                                               mapper.readTree("{\"hello\":\"armeria\"}"),
                                                               mapper.readTree("{\"hello\":\"armeria2\"}")))
                                      .examplePaths(MyService.class, "pathParamsWithQueries",
                                                    "/service/hello1/foo", "/service/hello1/bar")
                                      .exampleQueries(MyService.class, "pathParamsWithQueries", "hello3=hello4")
                                      .exclude(DocServiceFilter.ofMethodName(MyService.class.getName(),
                                                                             "exclude1").or(
                                              DocServiceFilter.ofMethodName(MyService.class.getName(),
                                                                            "exclude2")))
                                      .build());
            sb.serviceUnder("/excludeAll/", DocService.builder()
                                                      .exclude(DocServiceFilter.ofAnnotated())
                                                      .build());
        }
    };

    @Test
    void jsonSpecification() throws Exception {
        if (TestUtil.isDocServiceDemoMode()) {
            Thread.sleep(Long.MAX_VALUE);
        }
        final Map<Class<?>, Set<MethodInfo>> methodInfos = new HashMap<>();
        addFooMethodInfo(methodInfos);
        addAllMethodsMethodInfos(methodInfos);
        addIntsMethodInfo(methodInfos);
        addPathParamsMethodInfo(methodInfos);
        addPathParamsWithQueriesMethodInfo(methodInfos);
        addRegexMethodInfo(methodInfos);
        addPrefixMethodInfo(methodInfos);
        addConsumesMethodInfo(methodInfos);
        addBeanMethodInfo(methodInfos);
        addMultiMethodInfo(methodInfos);
        addJsonMethodInfo(methodInfos);
        addOverloadMethodInfo(methodInfos);
        addOverload2MethodInfo(methodInfos);
        addMarkdownDescriptionMethodInfo(methodInfos);
        addMermaidDescriptionMethodInfo(methodInfos);
        addImplicitRequestObjectMethodInfo(methodInfos);
        addJsonLinesMethodInfo(methodInfos);

        final Map<Class<?>, DescriptionInfo> serviceDescription = ImmutableMap.of(
                MyService.class, DescriptionInfo.of("My service class"));

        final JsonNode expectedJson = mapper.valueToTree(AnnotatedDocServicePlugin.generate(
                serviceDescription, methodInfos, typeDescriptor -> null));
        addExamples(expectedJson);

        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.get("/docs/specification.json").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL))
                .isEqualTo("no-cache, max-age=0, must-revalidate");
        assertThatJson(res.contentUtf8()).when(IGNORING_ARRAY_ORDER)
                                         .whenIgnoringPaths("structs", "docServiceRoute")
                                         .isEqualTo(expectedJson);
    }

    private static void addFooMethodInfo(Map<Class<?>, Set<MethodInfo>> methodInfos) {
        final EndpointInfo endpoint = EndpointInfo.builder("*", "exact:/service/foo")
                                                  .availableMimeTypes(MediaType.JSON_UTF_8)
                                                  .build();
        final List<FieldInfo> fieldInfos = ImmutableList.of(
                FieldInfo.builder("header", INT).requirement(REQUIRED)
                         .location(FieldLocation.HEADER)
                         .descriptionInfo(DescriptionInfo.of("header parameter")).build(),
                FieldInfo.builder("query", LONG).requirement(REQUIRED)
                         .location(QUERY)
                         .descriptionInfo(DescriptionInfo.of("query parameter")).build());
        final MethodInfo methodInfo = new MethodInfo(
                MyService.class.getName(), "foo", 0, TypeSignature.ofBase("T"), fieldInfos, ImmutableList.of(),
                ImmutableList.of(endpoint), HttpMethod.GET, DescriptionInfo.of("foo method"));
        methodInfos.computeIfAbsent(MyService.class, unused -> new HashSet<>()).add(methodInfo);
    }

    private static void addAllMethodsMethodInfos(Map<Class<?>, Set<MethodInfo>> methodInfos) {
        final EndpointInfo endpoint = EndpointInfo.builder("*", "exact:/service/allMethods")
                                                  .availableMimeTypes(MediaType.JSON_UTF_8)
                                                  .build();
        Stream.of(HttpMethod.values())
              .filter(httpMethod -> httpMethod != HttpMethod.CONNECT && httpMethod != HttpMethod.UNKNOWN)
              .forEach(httpMethod -> {
                  final MethodInfo methodInfo =
                          new MethodInfo(MyService.class.getName(), "allMethods",
                                         0, TypeSignature.ofContainer("CompletableFuture",
                                                                      TypeSignature.ofUnresolved("")),
                                         ImmutableList.of(), ImmutableList.of(), ImmutableList.of(endpoint),
                                         httpMethod, DescriptionInfo.empty());
                  methodInfos.computeIfAbsent(MyService.class, unused -> new HashSet<>()).add(methodInfo);
              });
    }

    private static void addIntsMethodInfo(Map<Class<?>, Set<MethodInfo>> methodInfos) {
        final EndpointInfo endpoint = EndpointInfo.builder("*", "exact:/service/ints")
                                                  .availableMimeTypes(MediaType.JSON_UTF_8)
                                                  .build();
        final List<FieldInfo> fieldInfos = ImmutableList.of(
                FieldInfo.builder("ints", TypeSignature.ofList(INT)).requirement(REQUIRED)
                         .location(QUERY).build());
        final MethodInfo methodInfo = new MethodInfo(
                MyService.class.getName(), "ints", 0, TypeSignature.ofList(INT),
                fieldInfos, ImmutableList.of(),
                ImmutableList.of(endpoint), HttpMethod.GET, DescriptionInfo.empty());
        methodInfos.computeIfAbsent(MyService.class, unused -> new HashSet<>()).add(methodInfo);
    }

    private static void addPathParamsMethodInfo(Map<Class<?>, Set<MethodInfo>> methodInfos) {
        final EndpointInfo endpoint = EndpointInfo.builder("*", "/service/hello1/:hello2/hello3/:hello4")
                                                  .availableMimeTypes(MediaType.JSON_UTF_8)
                                                  .build();
        final List<FieldInfo> fieldInfos = ImmutableList.of(
                FieldInfo.builder("hello2", STRING).requirement(REQUIRED).location(PATH).build(),
                FieldInfo.builder("hello4", STRING).requirement(REQUIRED).location(PATH).build());
        final MethodInfo methodInfo = new MethodInfo(
                MyService.class.getName(), "pathParams", 0, STRING, fieldInfos, ImmutableList.of(),
                ImmutableList.of(endpoint), HttpMethod.GET, DescriptionInfo.empty());
        methodInfos.computeIfAbsent(MyService.class, unused -> new HashSet<>()).add(methodInfo);
    }

    private static void addPathParamsWithQueriesMethodInfo(Map<Class<?>, Set<MethodInfo>> methodInfos) {
        final EndpointInfo endpoint = EndpointInfo.builder("*", "/service/hello1/:hello2")
                                                  .availableMimeTypes(MediaType.JSON_UTF_8)
                                                  .build();
        final List<FieldInfo> fieldInfos = ImmutableList.of(
                FieldInfo.builder("hello2", STRING).requirement(REQUIRED).location(PATH).build(),
                FieldInfo.builder("hello3", STRING).requirement(REQUIRED).location(QUERY).build());
        final MethodInfo methodInfo = new MethodInfo(
                MyService.class.getName(), "pathParamsWithQueries", 0, STRING, fieldInfos, ImmutableList.of(),
                ImmutableList.of(endpoint), HttpMethod.GET, DescriptionInfo.empty());
        methodInfos.computeIfAbsent(MyService.class, unused -> new HashSet<>()).add(methodInfo);
    }

    private static void addRegexMethodInfo(Map<Class<?>, Set<MethodInfo>> methodInfos) {
        final EndpointInfo endpoint = EndpointInfo.builder("*", "regex:/(bar|baz)")
                                                  .regexPathPrefix("prefix:/service/")
                                                  .availableMimeTypes(MediaType.JSON_UTF_8)
                                                  .build();
        final List<FieldInfo> fieldInfos = ImmutableList.of(
                FieldInfo.builder("myEnum", toTypeSignature(MyEnum.class))
                         .requirement(REQUIRED)
                         .location(QUERY)
                         .build());
        final MethodInfo methodInfo = new MethodInfo(
                MyService.class.getName(), "regex", 0, TypeSignature.ofList(TypeSignature.ofList(STRING)),
                fieldInfos, ImmutableList.of(),
                ImmutableList.of(endpoint), HttpMethod.GET, DescriptionInfo.empty());
        methodInfos.computeIfAbsent(MyService.class, unused -> new HashSet<>()).add(methodInfo);
    }

    private static void addPrefixMethodInfo(Map<Class<?>, Set<MethodInfo>> methodInfos) {
        final EndpointInfo endpoint = EndpointInfo.builder("*", "prefix:/service/prefix/")
                                                  .availableMimeTypes(MediaType.JSON_UTF_8)
                                                  .build();
        final MethodInfo methodInfo = new MethodInfo(
                MyService.class.getName(), "prefix", 0, STRING, ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(endpoint), HttpMethod.GET, DescriptionInfo.empty());
        methodInfos.computeIfAbsent(MyService.class, unused -> new HashSet<>()).add(methodInfo);
    }

    private static void addConsumesMethodInfo(Map<Class<?>, Set<MethodInfo>> methodInfos) {
        final EndpointInfo endpoint = EndpointInfo.builder("*", "exact:/service/consumes")
                                                  .availableMimeTypes(MediaType.APPLICATION_BINARY,
                                                                      MediaType.JSON_UTF_8)
                                                  .build();
        final MethodInfo methodInfo = new MethodInfo(
                MyService.class.getName(), "consumes", 0,
                TypeSignature.ofContainer("BiFunction", TypeSignature.ofStruct(JsonNode.class),
                                          TypeSignature.ofUnresolved(""), STRING),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(endpoint), HttpMethod.GET,
                DescriptionInfo.empty());
        methodInfos.computeIfAbsent(MyService.class, unused -> new HashSet<>()).add(methodInfo);
    }

    private static void addBeanMethodInfo(Map<Class<?>, Set<MethodInfo>> methodInfos) {
        final EndpointInfo endpoint = EndpointInfo.builder("*", "exact:/service/bean")
                                                  .availableMimeTypes(MediaType.JSON_UTF_8)
                                                  .build();
        final List<FieldInfo> fieldInfos = ImmutableList.of(compositeBean());
        final MethodInfo methodInfo = new MethodInfo(
                MyService.class.getName(), "bean", 0,
                TypeSignature.ofStruct(HttpResponse.class), fieldInfos,
                ImmutableList.of(), ImmutableList.of(endpoint), HttpMethod.GET, DescriptionInfo.empty());
        methodInfos.computeIfAbsent(MyService.class, unused -> new HashSet<>()).add(methodInfo);
    }

    private static void addMultiMethodInfo(Map<Class<?>, Set<MethodInfo>> methodInfos) {
        final EndpointInfo endpoint1 = EndpointInfo.builder("*", "exact:/service/multi")
                                                   .availableMimeTypes(MediaType.JSON_UTF_8)
                                                   .build();
        final EndpointInfo endpoint2 = EndpointInfo.builder("*", "prefix:/service/multi2/")
                                                   .availableMimeTypes(MediaType.JSON_UTF_8)
                                                   .build();
        final MethodInfo methodInfo = new MethodInfo(
                MyService.class.getName(), "multi", 0, TypeSignature.ofStruct(HttpResponse.class),
                ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(endpoint1, endpoint2), HttpMethod.GET, DescriptionInfo.empty());
        methodInfos.computeIfAbsent(MyService.class, unused -> new HashSet<>()).add(methodInfo);
    }

    private static void addJsonMethodInfo(Map<Class<?>, Set<MethodInfo>> methodInfos) {
        final EndpointInfo endpoint1 = EndpointInfo.builder("*", "exact:/service/json")
                                                   .availableMimeTypes(MediaType.JSON_UTF_8)
                                                   .build();
        final FieldInfo jsonRequest =
                FieldInfo.builder("request", TypeSignature.ofStruct(JsonRequest.class))
                         .requirement(REQUIRED)
                         .build();
        final MethodInfo methodInfo1 = new MethodInfo(
                MyService.class.getName(), "json", 0, STRING, ImmutableList.of(jsonRequest), ImmutableList.of(),
                ImmutableList.of(endpoint1), HttpMethod.POST, DescriptionInfo.empty());
        final MethodInfo methodInfo2 = new MethodInfo(
                MyService.class.getName(), "json", 0, STRING, ImmutableList.of(jsonRequest), ImmutableList.of(),
                ImmutableList.of(endpoint1), HttpMethod.PUT, DescriptionInfo.empty());
        final Set<MethodInfo> methods = methodInfos.computeIfAbsent(MyService.class, unused -> new HashSet<>());
        methods.add(methodInfo1);
        methods.add(methodInfo2);
    }

    private static void addOverloadMethodInfo(Map<Class<?>, Set<MethodInfo>> methodInfos) {
        final EndpointInfo endpoint = EndpointInfo.builder("*", "exact:/service/overload")
                                                  .availableMimeTypes(MediaType.JSON_UTF_8)
                                                  .build();
        final List<FieldInfo> fieldInfos = ImmutableList.of(
                FieldInfo.builder("period", TypeSignature.ofStruct(Period.class))
                         .requirement(REQUIRED).location(QUERY).build());
        final MethodInfo methodInfo = new MethodInfo(
                MyService.class.getName(), "overload", 0,
                TypeSignature.ofStruct(HttpResponse.class), fieldInfos,
                ImmutableList.of(), ImmutableList.of(endpoint), HttpMethod.GET, DescriptionInfo.empty());
        methodInfos.computeIfAbsent(MyService.class, unused -> new HashSet<>()).add(methodInfo);
    }

    private static void addOverload2MethodInfo(Map<Class<?>, Set<MethodInfo>> methodInfos) {
        final EndpointInfo endpoint = EndpointInfo.builder("*", "exact:/service/overload1")
                                                  .availableMimeTypes(MediaType.JSON_UTF_8)
                                                  .build();
        final List<FieldInfo> fieldInfos = ImmutableList.of(
                FieldInfo.builder("period", TypeSignature.ofStruct(Period.class))
                         .requirement(REQUIRED).location(QUERY).build(),
                FieldInfo.builder("myEnum", toTypeSignature(MyEnum.class))
                         .requirement(REQUIRED).location(QUERY).build());
        final MethodInfo methodInfo = new MethodInfo(
                MyService.class.getName(), "overload", 1,
                TypeSignature.ofStruct(HttpResponse.class), fieldInfos,
                ImmutableList.of(), ImmutableList.of(endpoint), HttpMethod.GET, DescriptionInfo.empty());
        methodInfos.computeIfAbsent(MyService.class, unused -> new HashSet<>()).add(methodInfo);
    }

    private static void addMarkdownDescriptionMethodInfo(Map<Class<?>, Set<MethodInfo>> methodInfos) {
        final EndpointInfo endpoint = EndpointInfo.builder("*", "exact:/service/markdown")
                                                  .availableMimeTypes(MediaType.JSON_UTF_8)
                                                  .build();
        final List<FieldInfo> fieldInfos = ImmutableList.of(
                FieldInfo.builder("descriptionEnum", toTypeSignature(DescriptionEnum.class))
                         .requirement(REQUIRED)
                         .location(QUERY)
                         .descriptionInfo(DescriptionInfo.of("DESCRIPTION `PARAM`", Markup.MARKDOWN))
                         .build());
        final MethodInfo methodInfo = new MethodInfo(
                MyService.class.getName(), "description", 0, STRING, fieldInfos, ImmutableList.of(),
                ImmutableList.of(endpoint), HttpMethod.GET,
                DescriptionInfo.of("## Description method with markdown", Markup.MARKDOWN));
        methodInfos.computeIfAbsent(MyService.class, unused -> new HashSet<>()).add(methodInfo);
    }

    private static void addMermaidDescriptionMethodInfo(Map<Class<?>, Set<MethodInfo>> methodInfos) {
        final EndpointInfo endpoint = EndpointInfo.builder("*", "exact:/service/mermaid")
                                                  .availableMimeTypes(MediaType.JSON_UTF_8)
                                                  .build();
        final List<FieldInfo> fieldInfos = ImmutableList.of();
        final String mermaidDescription = "graph TD;\n" +
                                          " A-->B;\n" +
                                          " A-->C;\n" +
                                          " B-->D;\n" +
                                          " C-->D;";
        final MethodInfo methodInfo = new MethodInfo(
                MyService.class.getName(), "mermaid", 0,
                TypeSignature.ofStruct(HttpResponse.class), fieldInfos,
                ImmutableList.of(), ImmutableList.of(endpoint), HttpMethod.GET,
                DescriptionInfo.of(mermaidDescription, Markup.MERMAID));
        methodInfos.computeIfAbsent(MyService.class, unused -> new HashSet<>()).add(methodInfo);
    }

    private static void addImplicitRequestObjectMethodInfo(Map<Class<?>, Set<MethodInfo>> methodInfos) {
        final EndpointInfo endpoint = EndpointInfo.builder("*", "exact:/service/implicit/request/object")
                                                  .availableMimeTypes(MediaType.OCTET_STREAM,
                                                                      MediaType.JSON_UTF_8)
                                                  .build();
        final List<FieldInfo> fieldInfos = ImmutableList.of(
                FieldInfo.builder("body", toTypeSignature(byte[].class))
                         .requirement(REQUIRED).build());
        final MethodInfo methodInfo = new MethodInfo(
                MyService.class.getName(), "implicitRequestObject", 0,
                TypeSignature.ofStruct(HttpResponse.class), fieldInfos,
                ImmutableList.of(), ImmutableList.of(endpoint), HttpMethod.POST, DescriptionInfo.empty());
        methodInfos.computeIfAbsent(MyService.class, unused -> new HashSet<>()).add(methodInfo);
    }

    private static void addExamples(JsonNode json) {
        // Add the global example.
        ((ArrayNode) json.get("exampleHeaders")).add(mapper.valueToTree(EXAMPLE_HEADERS_ALL));

        json.get("services").forEach(service -> {
            // Add the service-wide examples.
            final String serviceName = service.get("name").textValue();
            final ArrayNode serviceExampleHeaders = (ArrayNode) service.get("exampleHeaders");
            if (MyService.class.getName().equals(serviceName)) {
                serviceExampleHeaders.add(mapper.valueToTree(EXAMPLE_HEADERS_SERVICE));
            }

            // Add the method-specific examples.
            service.get("methods").forEach(method -> {
                final String methodName = method.get("name").textValue();
                final ArrayNode exampleHeaders = (ArrayNode) method.get("exampleHeaders");
                if (MyService.class.getName().equals(serviceName) && "pathParams".equals(methodName)) {
                    exampleHeaders.add(mapper.valueToTree(EXAMPLE_HEADERS_METHOD));
                    final ArrayNode exampleRequests = (ArrayNode) method.get("exampleRequests");
                    exampleRequests.add('{' + System.lineSeparator() +
                                        "  \"hello\" : \"armeria\"" + System.lineSeparator() +
                                        '}');
                    exampleRequests.add('{' + System.lineSeparator() +
                                        "  \"hello\" : \"armeria2\"" + System.lineSeparator() +
                                        '}');
                    final ArrayNode examplePaths = (ArrayNode) method.get("examplePaths");
                    examplePaths.add(TextNode.valueOf("/service/hello1/foo/hello3/bar"));
                }

                if (MyService.class.getName().equals(serviceName) && "foo".equals(methodName)) {
                    final ArrayNode exampleQueries = (ArrayNode) method.get("exampleQueries");
                    exampleQueries.add(TextNode.valueOf("query=10"));
                    exampleQueries.add(TextNode.valueOf("query=20"));
                }

                if (MyService.class.getName().equals(serviceName) &&
                    "pathParamsWithQueries".equals(methodName)) {
                    final ArrayNode examplePaths = (ArrayNode) method.get("examplePaths");
                    examplePaths.add(TextNode.valueOf("/service/hello1/foo"));
                    examplePaths.add(TextNode.valueOf("/service/hello1/bar"));
                    final ArrayNode exampleQueries = (ArrayNode) method.get("exampleQueries");
                    exampleQueries.add(TextNode.valueOf("hello3=hello4"));
                }

                if (MyService.class.getName().equals(serviceName) && "jsonLines".equals(methodName)) {
                    exampleHeaders.add(mapper.valueToTree(JSON_LINE_HEADERS));
                }
            });
        });
    }

    private static void addJsonLinesMethodInfo(Map<Class<?>, Set<MethodInfo>> methodInfos) {
        final EndpointInfo endpoint = EndpointInfo.builder("*", "exact:/service/json-lines")
                                                  .availableMimeTypes(MediaType.JSON_LINES,
                                                                      MediaType.JSON_UTF_8)
                                                  .build();
        final List<FieldInfo> fieldInfos = ImmutableList.of();
        final MethodInfo methodInfo = new MethodInfo(
                MyService.class.getName(), "jsonLines", 0,
                TypeSignature.ofStruct(HttpResponse.class), fieldInfos,
                ImmutableList.of(), ImmutableList.of(endpoint), HttpMethod.POST, DescriptionInfo.empty());
        methodInfos.computeIfAbsent(MyService.class, unused -> new HashSet<>()).add(methodInfo);
    }

    @Test
    void excludeAllServices() throws IOException {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.get("/excludeAll/specification.json").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        final JsonNode actualJson = mapper.readTree(res.contentUtf8());
        final Route docServiceRoute = Route.builder().pathPrefix("/excludeAll").build();
        final ServiceSpecification emptySpecification = new ServiceSpecification(ImmutableList.of(),
                                                                                 ImmutableList.of(),
                                                                                 ImmutableList.of(),
                                                                                 ImmutableList.of(),
                                                                                 ImmutableList.of(),
                                                                                 docServiceRoute);
        final JsonNode expectedJson = mapper.valueToTree(emptySpecification);
        assertThatJson(actualJson).isEqualTo(expectedJson);
    }

    @ParameterizedTest
    @CsvSource({ "/proxy", "/proxy2/nested" })
    void rightPathBehindProxy(String proxyPath) throws IOException {
        final String specification = BlockingWebClient.of(server.httpUri())
                                                      .get(proxyPath + "/docs/specification.json")
                                                      .contentUtf8();
        final JsonNode specificationJson = mapper.readTree(specification);
        assertThatJson(specificationJson).node("docServiceRoute.pathType").isEqualTo("PREFIX");
        assertThatJson(specificationJson).node("docServiceRoute.patternString").isEqualTo("/docs/*");
    }

    private static final class ProxyService implements HttpService {
        private final String proxyPath;

        private ProxyService(String proxyPath) {
            this.proxyPath = proxyPath;
        }

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
            final String origPath = req.path();
            assertThat(origPath).startsWith(proxyPath);
            final String newPath = req.path().substring(proxyPath.length());
            final HttpRequest newReq = req.withHeaders(
                    req.headers().toBuilder().path(newPath).build()
            );
            return server.webClient().execute(newReq);
        }
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
            return UnmodifiableFuture.completedFuture(HttpResponse.of("allMethods"));
        }

        @Get("/ints")
        public List<Integer> ints(@Param List<Integer> ints) {
            return ints;
        }

        @Get("/hello1/:hello2/hello3/:hello4")
        public String pathParams(@Param String hello2, @Param String hello4) {
            return hello2 + ' ' + hello4;
        }

        @Get("/hello1/:hello2")
        public String pathParamsWithQueries(@Param String hello2, @Param String hello3) {
            return hello2 + ' ' + hello3;
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
        public String prefix(ServiceRequestContext ctx) throws InterruptedException {
            // Added to check delayed response in browser.
            Thread.sleep(500);
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
            return HttpResponse.ofJson(compositeBean);
        }

        @Get("/exclude1")
        public HttpResponse exclude1() {
            return HttpResponse.of(200);
        }

        @Get("/exclude2")
        public HttpResponse exclude2() {
            return HttpResponse.of(200);
        }

        @Get
        @Path("/multi")
        @Path("prefix:/multi2")
        public HttpResponse multi() {
            return HttpResponse.of(200);
        }

        @Path("/json")
        @Post
        @Put
        public String json(JsonRequest request) {
            return request.bar;
        }

        @Path("/json-lines")
        @Post
        @Consumes(MediaTypeNames.JSON_LINES)
        public HttpResponse jsonLines(HttpRequest req) {
            return HttpResponse.of("json-lines is working");
        }

        @Order(1) // Use Order to create the MethodInfo in order.
        @Get("/overload")
        public HttpResponse overload(@Param Period period) {
            return HttpResponse.of(200);
        }

        @Order(2) // Use Order to create the MethodInfo in order.
        @Get("/overload1")
        public HttpResponse overload(@Param Period period, @Param MyEnum myEnum) {
            return HttpResponse.of(200);
        }

        @Description(value = "## Description method with markdown", markup = Markup.MARKDOWN)
        @Get("/markdown")
        public String description(@Param @Description(value = "DESCRIPTION `PARAM`", markup = Markup.MARKDOWN)
                                  DescriptionEnum descriptionEnum) {
            return descriptionEnum.name();
        }

        @Description(value = "graph TD;\n" +
                             " A-->B;\n" +
                             " A-->C;\n" +
                             " B-->D;\n" +
                             " C-->D;",
                markup = Markup.MERMAID)
        @Get("/mermaid")
        public HttpResponse mermaid() {
            return HttpResponse.of(200);
        }

        @Post("/implicit/request/object")
        @ConsumesOctetStream
        @ProducesOctetStream
        public HttpResponse implicitRequestObject(byte[] body) {
            return HttpResponse.of(HttpStatus.OK, MediaType.OCTET_STREAM, body);
        }
    }

    private enum MyEnum {
        A,
        B,
        C
    }

    @Description("DESCRIPTION ENUM")
    private enum DescriptionEnum {
        @Description(value = "MARKDOWN DESCRIPTION `A`", markup = Markup.MARKDOWN)
        DESCRIPTION_A,
        @Description("NONE MARKDOWN DESCRIPTION B\nMultiline")
        DESCRIPTION_B,
        @Description("NONE MARKDOWN DESCRIPTION C")
        DESCRIPTION_C
    }

    private static class JsonRequest {
        @JsonProperty
        private int foo;
        @JsonProperty
        private String bar;
    }
}
