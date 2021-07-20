/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.internal.server;

import static net.javacrumbs.jsonunit.core.Option.IGNORING_ARRAY_ORDER;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.internal.testing.TestUtil;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.docs.DocServiceFilter;
import com.linecorp.armeria.server.docs.EndpointInfo;
import com.linecorp.armeria.server.docs.MethodInfo;
import com.linecorp.armeria.server.docs.ServiceSpecification;
import com.linecorp.armeria.server.docs.TypeSignature;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class HttpDocServiceTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    private static final HttpHeaders EXAMPLE_HEADERS_ALL = HttpHeaders.of(HttpHeaderNames.of("a"), "b");
    private static final HttpHeaders EXAMPLE_HEADERS_SERVICE = HttpHeaders.of(HttpHeaderNames.of("c"), "d");
    private static final HttpHeaders EXAMPLE_HEADERS_METHOD = HttpHeaders.of(HttpHeaderNames.of("e"), "f");

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            if (TestUtil.isDocServiceDemoMode()) {
                sb.http(8080);
            }
            sb.service("/my", new MyService());
            sb.service("/param/{name}", new ParamService());
            sb.serviceUnder("/under", new UnderService());
            sb.serviceUnder("/docs",
                    DocService.builder()
                              .exampleHeaders(EXAMPLE_HEADERS_ALL)
                              .exampleHeaders(MyService.class, EXAMPLE_HEADERS_SERVICE)
                              .exampleHeaders(MyService.class, "doGet", EXAMPLE_HEADERS_METHOD)
                              .examplePaths(ParamService.class, "doGet",
                                            "/param/armeria")
                              .exampleQueries(MyService.class, "doGet", "query=10")
                              .exampleRequests(MyService.class, "doPost",
                                               ImmutableList
                                                       .of(mapper.readTree("{\"hello\":\"armeria\"}")))
                              .examplePaths(UnderService.class, "doGet",
                                            "/under/hello1/foo", "/under/hello1/bar")
                              .exampleQueries(MyService.class, "doGet", "hello3=hello4")
                              .exclude(DocServiceFilter.ofMethodName(MyService.class.getName(), "doDelete").or(
                                       DocServiceFilter.ofMethodName(MyService.class.getName(), "doPut")))
                                      .build());
            sb.serviceUnder("/excludeAll/", DocService.builder()
                                                      .exclude(DocServiceFilter.ofHttp())
                                                      .build());
        }
    };

    @Test
    void jsonSpecification() throws InterruptedException {
        if (TestUtil.isDocServiceDemoMode()) {
            Thread.sleep(Long.MAX_VALUE);
        }

        final Map<Class<?>, Set<MethodInfo>> methodInfos = new HashMap<>();
        addMyServiceInfo(methodInfos);
        addParamServiceInfo(methodInfos);
        addUnderServiceInfo(methodInfos);

        final JsonNode expectedJson = mapper.valueToTree(HttpDocServicePlugin.generate(ImmutableMap.of(),
                                                                                       methodInfos));
        addExamples(expectedJson);

        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.get("/docs/specification.json").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL)).isEqualTo("no-cache, must-revalidate");
        System.out.println(res.contentUtf8());
        System.out.println(expectedJson);
        assertThatJson(res.contentUtf8()).when(IGNORING_ARRAY_ORDER).isEqualTo(expectedJson);
    }

    private static void addMyServiceInfo(Map<Class<?>, Set<MethodInfo>> methodInfos) {
        final EndpointInfo endpoint = EndpointInfo.builder("*", "exact:/my")
                                                  .availableMimeTypes(MediaType.JSON_UTF_8)
                                                  .build();
        HttpDocServicePlugin.METHOD_NAMES.entrySet().stream()
                                         .filter(entry -> !ImmutableList.of(HttpMethod.DELETE, HttpMethod.PUT)
                                                                        .contains(entry.getKey()))
                                         .forEach(entry -> {
            final MethodInfo methodInfo = new MethodInfo(
                    entry.getValue(), TypeSignature.ofBase(HttpResponse.class.getSimpleName()),
                    ImmutableList.of(), ImmutableList.of(), ImmutableList.of(endpoint), entry.getKey(), null);
            methodInfos.computeIfAbsent(MyService.class, unused -> new HashSet<>()).add(methodInfo);
        });
    }

    private static void addParamServiceInfo(Map<Class<?>, Set<MethodInfo>> methodInfos) {
        final EndpointInfo endpoint = EndpointInfo.builder("*", "/param/{name}")
                                                  .availableMimeTypes(MediaType.JSON_UTF_8)
                                                  .build();
        HttpDocServicePlugin.METHOD_NAMES.forEach((httpMethod, methodName) -> {
            final MethodInfo methodInfo = new MethodInfo(
                    methodName, TypeSignature.ofBase(HttpResponse.class.getSimpleName()), ImmutableList.of(),
                    ImmutableList.of(), ImmutableList.of(endpoint), httpMethod, null);
            methodInfos.computeIfAbsent(ParamService.class, unused -> new HashSet<>()).add(methodInfo);
        });
    }

    private static void addUnderServiceInfo(Map<Class<?>, Set<MethodInfo>> methodInfos) {
        final EndpointInfo endpoint = EndpointInfo.builder("*", "prefix:/under/")
                                                  .availableMimeTypes(MediaType.JSON_UTF_8)
                                                  .build();
        HttpDocServicePlugin.METHOD_NAMES.forEach((httpMethod, methodName) -> {
            final MethodInfo methodInfo = new MethodInfo(
                    methodName, TypeSignature.ofBase(HttpResponse.class.getSimpleName()), ImmutableList.of(),
                    ImmutableList.of(), ImmutableList.of(endpoint), httpMethod, null);
            methodInfos.computeIfAbsent(UnderService.class, unused -> new HashSet<>()).add(methodInfo);
        });
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
                if (MyService.class.getName().equals(serviceName) && "doGet".equals(methodName)) {
                    exampleHeaders.add(mapper.valueToTree(EXAMPLE_HEADERS_METHOD));
                }

                if (MyService.class.getName().equals(serviceName) && "doPost".equals(methodName)) {
                    final ArrayNode exampleRequests = (ArrayNode) method.get("exampleRequests");
                    exampleRequests.add('{' + System.lineSeparator() +
                                        "  \"hello\" : \"armeria\"" + System.lineSeparator() +
                                        '}');
                }

                if (ParamService.class.getName().equals(serviceName) && "doGet".equals(methodName)) {
                    final ArrayNode examplePaths = (ArrayNode) method.get("examplePaths");
                    examplePaths.add(TextNode.valueOf("/param/armeria"));
                }

                if (MyService.class.getName().equals(serviceName) && "doGet".equals(methodName)) {
                    final ArrayNode exampleQueries = (ArrayNode) method.get("exampleQueries");
                    exampleQueries.add(TextNode.valueOf("query=10"));
                }

                if (UnderService.class.getName().equals(serviceName) && "doGet".equals(methodName)) {
                    final ArrayNode examplePaths = (ArrayNode) method.get("examplePaths");
                    examplePaths.add(TextNode.valueOf("/under/hello1/foo"));
                    examplePaths.add(TextNode.valueOf("/under/hello1/bar"));
                }

                if (MyService.class.getName().equals(serviceName) && "doGet".equals(methodName)) {
                    final ArrayNode exampleQueries = (ArrayNode) method.get("exampleQueries");
                    exampleQueries.add(TextNode.valueOf("hello3=hello4"));
                }
            });
        });
    }

    @Test
    void excludeAllServices() throws IOException {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.get("/excludeAll/specification.json").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        final JsonNode actualJson = mapper.readTree(res.contentUtf8());
        final JsonNode expectedJson = mapper.valueToTree(new ServiceSpecification(ImmutableList.of(),
                                                                                  ImmutableList.of(),
                                                                                  ImmutableList.of(),
                                                                                  ImmutableList.of(),
                                                                                  ImmutableList.of()));
        assertThatJson(actualJson).isEqualTo(expectedJson);
    }

    private static class MyService extends AbstractHttpService {
        @Override
        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            return HttpResponse.of(HttpStatus.OK);
        }

        @Override
        protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            return HttpResponse.of(HttpStatus.OK);
        }
    }

    private static class ParamService extends AbstractHttpService {
        @Override
        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            return HttpResponse.of(HttpStatus.OK);
        }

        @Override
        protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            return HttpResponse.of(HttpStatus.OK);
        }
    }

    private static class UnderService extends AbstractHttpService {
        @Override
        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            return HttpResponse.of(HttpStatus.OK);
        }
    }
}
