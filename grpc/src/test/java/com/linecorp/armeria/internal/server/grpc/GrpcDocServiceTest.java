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

package com.linecorp.armeria.internal.server.grpc;

import static com.linecorp.armeria.internal.server.docs.DocServiceUtil.unifyFilter;
import static com.linecorp.armeria.internal.server.grpc.GrpcMethodUtil.extractMethodName;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.ServiceDescriptor;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.grpc.testing.Messages.Payload;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.grpc.testing.ReconnectServiceGrpc.ReconnectServiceImplBase;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceImplBase;
import com.linecorp.armeria.internal.server.grpc.GrpcDocServicePlugin.ServiceInfosBuilder;
import com.linecorp.armeria.internal.testing.TestUtil;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.docs.DocServiceFilter;
import com.linecorp.armeria.server.docs.EndpointInfo;
import com.linecorp.armeria.server.docs.ServiceInfo;
import com.linecorp.armeria.server.docs.ServiceSpecification;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.MethodDescriptor;
import io.grpc.stub.StreamObserver;

class GrpcDocServiceTest {

    private static final ServiceDescriptor TEST_SERVICE_DESCRIPTOR =
            com.linecorp.armeria.grpc.testing.Test.getDescriptor()
                                                  .findServiceByName("TestService");

    private static final ServiceDescriptor RECONNECT_SERVICE_DESCRIPTOR =
            com.linecorp.armeria.grpc.testing.Test.getDescriptor()
                                                  .findServiceByName("ReconnectService");

    private static final String INJECTED_HEADER_PROVIDER1 =
            "armeria.registerHeaderProvider(function() { return Promise.resolve({ 'foo': 'bar' }); });";

    private static final String INJECTED_HEADER_PROVIDER2 =
            "armeria.registerHeaderProvider(function() { return Promise.resolve({ 'cat': 'dog' }); });";

    private static final String INJECTED_HEADER_PROVIDER3 =
            "armeria.registerHeaderProvider(function() { return Promise.resolve({ 'moo': 'cow' }); });";

    private static final ObjectMapper mapper = new ObjectMapper();

    private static class TestService extends TestServiceImplBase {

        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            final ByteString body = ByteString.copyFromUtf8(
                    "hello " + request.getPayload().getBody().toStringUtf8());
            responseObserver.onNext(
                    SimpleResponse.newBuilder()
                                  .setPayload(Payload.newBuilder().setBody(body))
                                  .build());
            responseObserver.onCompleted();
        }
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            if (TestUtil.isDocServiceDemoMode()) {
                sb.http(8080);
            }
            sb.serviceUnder("/test",
                            GrpcService.builder()
                                       .addService(new TestService())
                                       .supportedSerializationFormats(GrpcSerializationFormats.values())
                                       .enableUnframedRequests(true)
                                       .build());
            sb.serviceUnder("/docs/",
                            DocService.builder()
                                      .exampleRequests(
                                            TestServiceGrpc.SERVICE_NAME,
                                            "UnaryCall",
                                            SimpleRequest.newBuilder()
                                                         .setPayload(
                                                             Payload.newBuilder()
                                                                    .setBody(ByteString.copyFromUtf8("world")))
                                                         .build())
                                      .injectedScripts(INJECTED_HEADER_PROVIDER1, INJECTED_HEADER_PROVIDER2)
                                      .injectedScriptSupplier((ctx, req) -> INJECTED_HEADER_PROVIDER3)
                                      .exclude(DocServiceFilter.ofMethodName(
                                                        TestServiceGrpc.SERVICE_NAME,
                                                        "EmptyCall"))
                                      .build()
                                      .decorate(LoggingService.newDecorator()));
            sb.serviceUnder("/excludeAll/",
                            DocService.builder()
                                      .exclude(DocServiceFilter.ofGrpc())
                                      .build());
            sb.serviceUnder("/",
                            GrpcService.builder()
                                       .addService(mock(ReconnectServiceImplBase.class))
                                       .build());
        }
    };

    @Test
    void testOk() throws Exception {
        if (TestUtil.isDocServiceDemoMode()) {
            Thread.sleep(Long.MAX_VALUE);
        }
        final ServiceInfosBuilder serviceInfosBuilder = new ServiceInfosBuilder();

        serviceInfosBuilder.addService(TEST_SERVICE_DESCRIPTOR);
        final TestService testService = new TestService();
        testService.bindService().getMethods().forEach(method -> {
            final MethodDescriptor<?, ?> methodDescriptor = method.getMethodDescriptor();
            serviceInfosBuilder.addEndpoint(
                    methodDescriptor,
                    EndpointInfo
                            .builder("*", "/test/armeria.grpc.testing.TestService/" +
                                          extractMethodName(methodDescriptor.getFullMethodName()))
                            .availableMimeTypes(GrpcSerializationFormats.PROTO.mediaType(),
                                                GrpcSerializationFormats.JSON.mediaType(),
                                                GrpcSerializationFormats.PROTO_WEB.mediaType(),
                                                GrpcSerializationFormats.JSON_WEB.mediaType(),
                                                GrpcSerializationFormats.PROTO_WEB_TEXT.mediaType(),
                                                MediaType.PROTOBUF.withParameter("protocol", "gRPC"),
                                                MediaType.JSON_UTF_8.withParameter("protocol", "gRPC"))
                            .build());
        });

        serviceInfosBuilder.addService(RECONNECT_SERVICE_DESCRIPTOR);
        final ReconnectServiceImplBase reconnectService = new ReconnectServiceImplBase() {};
        reconnectService.bindService().getMethods().forEach(method -> {
            final MethodDescriptor<?, ?> methodDescriptor = method.getMethodDescriptor();
            serviceInfosBuilder.addEndpoint(
                    methodDescriptor,
                    EndpointInfo.builder("*", "/armeria.grpc.testing.ReconnectService/" +
                                              extractMethodName(methodDescriptor.getFullMethodName()))
                                .availableFormats(GrpcSerializationFormats.values())
                                .build());
        });

        final List<ServiceInfo> serviceInfos =
                serviceInfosBuilder.build(unifyFilter(
                        (plugin, service, method) -> true,
                        DocServiceFilter.ofMethodName(TestServiceGrpc.SERVICE_NAME, "EmptyCall")));

        final JsonNode expectedJson = mapper.valueToTree(new GrpcDocServicePlugin().generate(serviceInfos));

        // The specification generated by GrpcDocServicePlugin does not include the examples specified
        // when building a DocService, so we add them manually here.
        addExamples(expectedJson);

        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.get("/docs/specification.json").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);

        final JsonNode actualJson = mapper.readTree(res.contentUtf8());

        // The specification generated by GrpcDocServicePlugin does not include the docstrings
        // because it's injected by the DocService, so we remove them here for easier comparison.
        removeDocStrings(actualJson);
        assertThatJson(actualJson).isEqualTo(expectedJson);

        final AggregatedHttpResponse injected = client.get("/docs/injected.js").aggregate().join();

        assertThat(injected.status()).isSameAs(HttpStatus.OK);
        assertThat(injected.contentUtf8()).isEqualTo(INJECTED_HEADER_PROVIDER1 + '\n' +
                                                     INJECTED_HEADER_PROVIDER2 + '\n' +
                                                     INJECTED_HEADER_PROVIDER3);
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

    @Test
    void testMethodNotAllowed() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpPost req = new HttpPost(server.httpUri() + "/docs/specification.json");

            try (CloseableHttpResponse res = hc.execute(req)) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 405 Method Not Allowed");
            }
        }
    }

    private static void addExamples(JsonNode json) {
        final Map<String, Multimap<String, String>> examplesToAdd =
                ImmutableMap.<String, Multimap<String, String>>builder()
                        .put(TestServiceGrpc.SERVICE_NAME,
                             ImmutableMultimap.<String, String>builder()
                                     .put("UnaryCall", "{\n" +
                                                       "  \"responseType\": \"COMPRESSABLE\",\n" +
                                                       "  \"responseSize\": 0,\n" +
                                                       "  \"payload\": {\n" +
                                                       "    \"type\": \"COMPRESSABLE\",\n" +
                                                       "    \"body\": \"d29ybGQ=\"\n" +
                                                       "  },\n" +
                                                       "  \"fillUsername\": false,\n" +
                                                       "  \"fillOauthScope\": false,\n" +
                                                       "  \"responseCompression\": \"NONE\"\n" +
                                                       '}')
                                     .build())
                        .build();

        json.get("services").forEach(service -> {
            final String serviceName = service.get("name").textValue();
            // Prepend the method-specific examples.
            service.get("methods").forEach(method -> {
                final String methodName = method.get("name").textValue();
                final ArrayNode exampleRequests = (ArrayNode) method.get("exampleRequests");

                int i = 0;
                for (String str : examplesToAdd.getOrDefault(serviceName, ImmutableMultimap.of())
                                               .get(methodName)) {
                    exampleRequests.insert(i++, str);
                }
            });
        });
    }

    private static void removeDocStrings(JsonNode json) {
        if (json.isObject()) {
            ((ObjectNode) json).remove("docString");
        }

        if (json.isObject() || json.isArray()) {
            json.forEach(GrpcDocServiceTest::removeDocStrings);
        }
    }
}
