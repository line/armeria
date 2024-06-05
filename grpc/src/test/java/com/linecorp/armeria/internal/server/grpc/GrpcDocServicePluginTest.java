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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.linecorp.armeria.internal.server.docs.DocServiceUtil.unifyFilter;
import static com.linecorp.armeria.internal.server.grpc.GrpcDocServicePlugin.buildHttpServiceInfos;
import static com.linecorp.armeria.internal.server.grpc.GrpcDocServicePlugin.convertRegexPath;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType;
import com.google.protobuf.Descriptors.ServiceDescriptor;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.internal.server.grpc.GrpcDocServicePlugin.HttpEndpoint;
import com.linecorp.armeria.internal.server.grpc.GrpcDocServicePlugin.ServiceInfosBuilder;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.HttpServiceWithRoutes;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.docs.DescriptionInfo;
import com.linecorp.armeria.server.docs.DescriptiveTypeSignature;
import com.linecorp.armeria.server.docs.DocServiceFilter;
import com.linecorp.armeria.server.docs.EndpointInfo;
import com.linecorp.armeria.server.docs.FieldInfo;
import com.linecorp.armeria.server.docs.FieldLocation;
import com.linecorp.armeria.server.docs.FieldRequirement;
import com.linecorp.armeria.server.docs.MethodInfo;
import com.linecorp.armeria.server.docs.ServiceInfo;
import com.linecorp.armeria.server.docs.ServiceSpecification;
import com.linecorp.armeria.server.docs.TypeSignature;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.protobuf.ProtobufDescriptiveTypeInfoProvider;

import io.grpc.MethodDescriptor;
import testing.grpc.EmptyProtos.Empty;
import testing.grpc.HttpJsonTranscodingTestServiceGrpc;
import testing.grpc.HttpJsonTranscodingTestServiceGrpc.HttpJsonTranscodingTestServiceImplBase;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.ReconnectServiceGrpc;
import testing.grpc.ReconnectServiceGrpc.ReconnectServiceImplBase;
import testing.grpc.TestServiceGrpc;
import testing.grpc.TestServiceGrpc.TestServiceImplBase;
import testing.grpc.UnitTestServiceGrpc;
import testing.grpc.UnitTestServiceGrpc.UnitTestServiceImplBase;

class GrpcDocServicePluginTest {

    private static final ServiceDescriptor TEST_SERVICE_DESCRIPTOR =
            testing.grpc.Test.getDescriptor()
                                                  .findServiceByName("TestService");

    private static final GrpcDocServicePlugin generator = new GrpcDocServicePlugin();

    @Test
    void servicesTest() throws Exception {
        final Map<String, ServiceInfo> services = services((plugin, service, method) -> true,
                                                           (plugin, service, method) -> false);

        assertThat(services).containsOnlyKeys(TestServiceGrpc.SERVICE_NAME,
                                              UnitTestServiceGrpc.SERVICE_NAME,
                                              ReconnectServiceGrpc.SERVICE_NAME,
                                              HttpJsonTranscodingTestServiceGrpc.SERVICE_NAME,
                                              HttpJsonTranscodingTestServiceGrpc.SERVICE_NAME +
                                              GrpcDocServicePlugin.HTTP_SERVICE_SUFFIX);

        services.get(TestServiceGrpc.SERVICE_NAME).methods().forEach(m -> {
            m.endpoints().forEach(e -> {
                assertThat(e.pathMapping()).isEqualTo("/armeria.grpc.testing.TestService/" + m.name());
            });
        });
        services.get(UnitTestServiceGrpc.SERVICE_NAME).methods().forEach(m -> {
            m.endpoints().forEach(e -> {
                assertThat(e.pathMapping()).isEqualTo("/test/armeria.grpc.testing.UnitTestService/" + m.name());
            });
        });
        services.get(ReconnectServiceGrpc.SERVICE_NAME).methods().forEach(m -> {
            m.endpoints().forEach(e -> {
                assertThat(e.pathMapping()).isEqualTo("/reconnect/armeria.grpc.testing.ReconnectService/" +
                                                      m.name());
            });
        });
        services.get(HttpJsonTranscodingTestServiceGrpc.SERVICE_NAME).methods().forEach(m -> {
            m.endpoints().forEach(e -> {
                assertThat(e.pathMapping()).isEqualTo("/armeria.grpc.testing.HttpJsonTranscodingTestService/" +
                                                      m.name());
            });
        });
        services.get(HttpJsonTranscodingTestServiceGrpc.SERVICE_NAME +
                     GrpcDocServicePlugin.HTTP_SERVICE_SUFFIX).methods().forEach(m -> {
            m.endpoints().forEach(e -> {
                if (m.examplePaths().isEmpty()) {
                    return;
                }
                assertThat(e.pathMapping()).satisfiesAnyOf(
                        path -> assertThat(path).endsWith(m.examplePaths().get(0)),
                        path -> assertThat(convertRegexPath(path)).endsWith(m.examplePaths().get(0)));
            });
        });
    }

    @Test
    void include() {

        // 1. Nothing specified: include all.
        // 2. Exclude specified: include all except the methods which the exclude filter returns true.
        // 3. Include specified: include the methods which the include filter returns true.
        // 4. Include and exclude specified: include the methods which the include filter returns true and
        //    the exclude filter returns false.

        // 1. Nothing specified.
        DocServiceFilter include = (plugin, service, method) -> true;
        DocServiceFilter exclude = (plugin, service, method) -> false;
        Map<String, ServiceInfo> services = services(include, exclude);
        assertThat(services).containsOnlyKeys(TestServiceGrpc.SERVICE_NAME, UnitTestServiceGrpc.SERVICE_NAME,
                                              ReconnectServiceGrpc.SERVICE_NAME,
                                              HttpJsonTranscodingTestServiceGrpc.SERVICE_NAME,
                                              HttpJsonTranscodingTestServiceGrpc.SERVICE_NAME +
                                              GrpcDocServicePlugin.HTTP_SERVICE_SUFFIX);

        // 2. Exclude specified.
        exclude = DocServiceFilter.ofMethodName(TestServiceGrpc.SERVICE_NAME, "EmptyCall").or(
                DocServiceFilter.ofMethodName(TestServiceGrpc.SERVICE_NAME, "HalfDuplexCall"));
        services = services(include, exclude);
        assertThat(services).containsOnlyKeys(TestServiceGrpc.SERVICE_NAME, UnitTestServiceGrpc.SERVICE_NAME,
                                              ReconnectServiceGrpc.SERVICE_NAME,
                                              HttpJsonTranscodingTestServiceGrpc.SERVICE_NAME,
                                              HttpJsonTranscodingTestServiceGrpc.SERVICE_NAME +
                                              GrpcDocServicePlugin.HTTP_SERVICE_SUFFIX);

        List<String> methods = methods(services);
        assertThat(methods).containsExactlyInAnyOrder("FullDuplexCall",
                                                      "StreamingInputCall",
                                                      "StreamingOutputCall",
                                                      "UnaryCall",
                                                      "UnaryCall2",
                                                      "UnaryCallWithAllDifferentParameterTypes",
                                                      "UnimplementedCall");

        // 3-1. Include serviceName specified.
        include = DocServiceFilter.ofServiceName(TestServiceGrpc.SERVICE_NAME);
        // Set the exclude to the default.
        exclude = (plugin, service, method) -> false;
        services = services(include, exclude);
        assertThat(services).containsOnlyKeys(TestServiceGrpc.SERVICE_NAME);

        methods = methods(services);
        assertThat(methods).containsExactlyInAnyOrder("EmptyCall",
                                                      "FullDuplexCall",
                                                      "HalfDuplexCall",
                                                      "StreamingInputCall",
                                                      "StreamingOutputCall",
                                                      "UnaryCall",
                                                      "UnaryCall2",
                                                      "UnaryCallWithAllDifferentParameterTypes",
                                                      "UnimplementedCall");

        // 3-2. Include methodName specified.
        include = DocServiceFilter.ofMethodName(TestServiceGrpc.SERVICE_NAME, "EmptyCall");
        services = services(include, exclude);
        assertThat(services).containsOnlyKeys(TestServiceGrpc.SERVICE_NAME);

        methods = methods(services);
        assertThat(methods).containsOnlyOnce("EmptyCall");

        // 4-1. Include and exclude specified.
        include = DocServiceFilter.ofServiceName(TestServiceGrpc.SERVICE_NAME);
        exclude = DocServiceFilter.ofMethodName(TestServiceGrpc.SERVICE_NAME, "EmptyCall").or(
                DocServiceFilter.ofMethodName(TestServiceGrpc.SERVICE_NAME, "HalfDuplexCall"));
        services = services(include, exclude);
        assertThat(services).containsOnlyKeys(TestServiceGrpc.SERVICE_NAME);

        methods = methods(services);
        assertThat(methods).containsExactlyInAnyOrder("FullDuplexCall",
                                                      "StreamingInputCall",
                                                      "StreamingOutputCall",
                                                      "UnaryCall",
                                                      "UnaryCall2",
                                                      "UnaryCallWithAllDifferentParameterTypes",
                                                      "UnimplementedCall");

        // 4-2. Include and exclude specified.
        include = DocServiceFilter.ofMethodName(TestServiceGrpc.SERVICE_NAME, "EmptyCall");
        exclude = DocServiceFilter.ofServiceName(TestServiceGrpc.SERVICE_NAME);
        services = services(include, exclude);
        assertThat(services.size()).isZero();
    }

    private static Map<String, ServiceInfo> services(DocServiceFilter include, DocServiceFilter exclude) {
        final ServerBuilder serverBuilder = Server.builder();

        // The case where a GrpcService is added to ServerBuilder without a prefix.
        final HttpServiceWithRoutes prefixlessService =
                GrpcService.builder()
                           .addService(mock(TestServiceImplBase.class))
                           .build();
        serverBuilder.service(prefixlessService);

        // The case where a GrpcService is added to ServerBuilder with a prefix.
        serverBuilder.service(
                Route.builder().pathPrefix("/test").build(),
                GrpcService.builder().addService(mock(UnitTestServiceImplBase.class)).build());

        // Another GrpcService with a different prefix.
        serverBuilder.service(
                Route.builder().pathPrefix("/reconnect").build(),
                GrpcService.builder().addService(mock(ReconnectServiceImplBase.class)).build());

        // The case where HTTP JSON transcoding is enabled and GrpcService is wrapped.
        serverBuilder.service(
                GrpcService.builder()
                           .addService(mock(HttpJsonTranscodingTestServiceImplBase.class),
                                       ImmutableList.of(
                                               delegate -> delegate.decorate(
                                                       mock(DecoratingHttpServiceFunction.class))
                                       ))
                           .enableHttpJsonTranscoding(true)
                           .build());

        // Make sure all services and their endpoints exist in the specification.
        final ServiceSpecification specification = generator.generateSpecification(
                ImmutableSet.copyOf(serverBuilder.build().serviceConfigs()),
                unifyFilter(include, exclude), new ProtobufDescriptiveTypeInfoProvider());
        return specification
                .services()
                .stream()
                .collect(toImmutableMap(ServiceInfo::name, Function.identity()));
    }

    private static List<String> methods(Map<String, ServiceInfo> services) {
        return services.get(TestServiceGrpc.SERVICE_NAME).methods()
                       .stream()
                       .map(MethodInfo::name)
                       .collect(toImmutableList());
    }

    @Test
    void newMethodInfo() throws Exception {
        final MethodInfo methodInfo = GrpcDocServicePlugin.newMethodInfo(
                TEST_SERVICE_DESCRIPTOR.getFullName(), TEST_SERVICE_DESCRIPTOR.findMethodByName("UnaryCall"),
                ImmutableSet.of(
                        EndpointInfo.builder("*", "/foo")
                                    .availableFormats(GrpcSerializationFormats.PROTO)
                                    .build(),
                        EndpointInfo.builder("*", "/debug/foo")
                                    .availableFormats(GrpcSerializationFormats.JSON)
                                    .build()));
        assertThat(methodInfo.name()).isEqualTo("UnaryCall");
        assertThat(methodInfo.returnTypeSignature().name()).isEqualTo("armeria.grpc.testing.SimpleResponse");
        assertThat(((DescriptiveTypeSignature) methodInfo.returnTypeSignature()).descriptor())
                .isEqualTo(SimpleResponse.getDescriptor());
        assertThat(methodInfo.parameters()).hasSize(1);
        assertThat(methodInfo.parameters().get(0).name()).isEqualTo("request");
        assertThat(methodInfo.parameters().get(0).typeSignature().name())
                .isEqualTo("armeria.grpc.testing.SimpleRequest");
        assertThat(((DescriptiveTypeSignature) methodInfo.parameters()
                                                         .get(0)
                                                         .typeSignature()).descriptor())
                .isEqualTo(SimpleRequest.getDescriptor());
        assertThat(methodInfo.useParameterAsRoot()).isTrue();
        assertThat(methodInfo.exceptionTypeSignatures()).isEmpty();
        assertThat(methodInfo.descriptionInfo()).isSameAs(DescriptionInfo.empty());
        assertThat(methodInfo.endpoints()).containsExactlyInAnyOrder(
                EndpointInfo.builder("*", "/foo")
                            .availableFormats(GrpcSerializationFormats.PROTO)
                            .build(),
                EndpointInfo.builder("*", "/debug/foo")
                            .availableFormats(GrpcSerializationFormats.JSON)
                            .build());
    }

    @Test
    void newServiceInfo() throws Exception {
        final ServiceInfosBuilder builder = new ServiceInfosBuilder();
        final TestServiceImplBase testService = new TestServiceImplBase() {};
        builder.addService(TEST_SERVICE_DESCRIPTOR);
        testService.bindService().getMethods().forEach(method -> {
            final MethodDescriptor<?, ?> methodDescriptor = method.getMethodDescriptor();
            builder.addEndpoint(methodDescriptor, EndpointInfo.builder("*", "/foo")
                                                              .fragment("a")
                                                              .availableFormats(GrpcSerializationFormats.PROTO)
                                                              .build());
        });
        final List<ServiceInfo> serviceInfos = builder.build((pluginName, serviceName, methodName) -> true);
        assertThat(serviceInfos).hasSize(1);
        final ServiceInfo service = serviceInfos.get(0);

        final Map<String, MethodInfo> functions = service.methods()
                                                         .stream()
                                                         .collect(toImmutableMap(MethodInfo::name,
                                                                                 Function.identity()));
        assertThat(functions).hasSize(9);
        final MethodInfo emptyCall = functions.get("EmptyCall");
        assertThat(emptyCall.name()).isEqualTo("EmptyCall");
        assertThat(emptyCall.parameters())
                .containsExactly(FieldInfo.builder("request",
                                                   TypeSignature.ofStruct("armeria.grpc.testing.Empty",
                                                                          Empty.getDescriptor()))
                                          .requirement(FieldRequirement.REQUIRED)
                                          .build());
        assertThat(emptyCall.useParameterAsRoot()).isTrue();
        assertThat(emptyCall.returnTypeSignature())
                .isEqualTo(TypeSignature.ofStruct("armeria.grpc.testing.Empty", Empty.getDescriptor()));

        // Just sanity check that all methods are present, function conversion is more thoroughly tested in
        // newMethodInfo()
        assertThat(functions.get("UnaryCallWithAllDifferentParameterTypes").name()).isEqualTo(
                "UnaryCallWithAllDifferentParameterTypes");
        assertThat(functions.get("UnaryCall").name()).isEqualTo("UnaryCall");
        assertThat(functions.get("UnaryCall2").name()).isEqualTo("UnaryCall2");
        assertThat(functions.get("StreamingOutputCall").name()).isEqualTo("StreamingOutputCall");
        assertThat(functions.get("StreamingInputCall").name()).isEqualTo("StreamingInputCall");
        assertThat(functions.get("FullDuplexCall").name()).isEqualTo("FullDuplexCall");
        assertThat(functions.get("HalfDuplexCall").name()).isEqualTo("HalfDuplexCall");
        assertThat(functions.get("UnimplementedCall").name()).isEqualTo("UnimplementedCall");
    }

    @Test
    void httpEndpoint() {
        final GrpcService grpcService =
                GrpcService.builder().addService(mock(HttpJsonTranscodingTestServiceImplBase.class))
                           .enableHttpJsonTranscoding(true).build();
        final HttpEndpointSupport httpEndpointSupport = grpcService.as(HttpEndpointSupport.class);
        assertThat(httpEndpointSupport).isNotNull();

        // Expected generated routes. See 'transcoding.proto' file.
        final List<Route> routes = ImmutableList.of(
                Route.builder().methods(HttpMethod.GET).path("/v1/messages/:p0").build(),
                Route.builder().methods(HttpMethod.GET).path("/v2/messages/{message_id}").build(),
                Route.builder().methods(HttpMethod.GET).path("/v3/messages/{message_id}").build(),
                Route.builder().methods(HttpMethod.PATCH).path("/v1/messages/{message_id}").build(),
                Route.builder().methods(HttpMethod.PATCH).path("/v2/messages/{message_id}").build());
        final List<HttpEndpointSpecification> specs =
                routes.stream().map(route -> httpEndpointSupport.httpEndpointSpecification(route))
                      .collect(toImmutableList());
        assertThat(specs.size()).isEqualTo(routes.size());

        // Get ServiceConfig to build HTTP endpoints.
        final ServiceConfig serviceConfig = Server.builder().service(grpcService).build()
                                                  .serviceConfigs().get(0);
        final List<HttpEndpoint> httpEndpoints =
                specs.stream().map(spec -> new HttpEndpoint(serviceConfig, spec)).collect(toImmutableList());
        final List<ServiceInfo> serviceInfos = buildHttpServiceInfos(httpEndpoints);

        // The endpoints are specified in the same service.
        assertThat(serviceInfos.size()).isOne();
        final ServiceInfo serviceInfo = serviceInfos.get(0);
        assertThat(serviceInfo.name()).isEqualTo(HttpJsonTranscodingTestServiceGrpc.SERVICE_NAME +
                                                 GrpcDocServicePlugin.HTTP_SERVICE_SUFFIX);

        final String virtualHostNamePattern = serviceConfig.virtualHost().hostnamePattern();

        // Check HTTP GET method.
        final MethodInfo getMessageV1 = serviceInfo.methods().stream()
                                                   .filter(m -> m.name().equals("GetMessageV1"))
                                                   .findFirst().get();
        assertThat(getMessageV1.httpMethod()).isEqualTo(HttpMethod.GET);
        assertThat(getMessageV1.endpoints()).containsAll(ImmutableSet.of(
                EndpointInfo.builder(virtualHostNamePattern, "/v1/messages/:p0")
                            .availableMimeTypes(MediaType.JSON_UTF_8).build()));
        assertThat(getMessageV1.parameters()).containsAll(ImmutableList.of(
                FieldInfo.builder("name", TypeSignature.ofBase(JavaType.STRING.name()))
                         .location(FieldLocation.PATH).requirement(FieldRequirement.REQUIRED).build()));
        assertThat(getMessageV1.useParameterAsRoot()).isFalse();

        final MethodInfo getMessageV2 = serviceInfo.methods().stream()
                                                   .filter(m -> m.name().equals("GetMessageV2"))
                                                   .findFirst().get();
        assertThat(getMessageV2.httpMethod()).isEqualTo(HttpMethod.GET);
        assertThat(getMessageV2.endpoints()).containsAll(ImmutableSet.of(
                EndpointInfo.builder(virtualHostNamePattern, "/v2/messages/:message_id")
                            .availableMimeTypes(MediaType.JSON_UTF_8).build()));
        assertThat(getMessageV2.parameters()).containsAll(ImmutableList.of(
                FieldInfo.builder("message_id", TypeSignature.ofBase(JavaType.STRING.name()))
                         .location(FieldLocation.PATH).requirement(FieldRequirement.REQUIRED).build(),
                FieldInfo.builder("revision", TypeSignature.ofBase(JavaType.LONG.name()))
                         .location(FieldLocation.QUERY).requirement(FieldRequirement.REQUIRED).build(),
                FieldInfo.builder("sub.subfield", TypeSignature.ofBase(JavaType.STRING.name()))
                         .location(FieldLocation.QUERY).requirement(FieldRequirement.REQUIRED).build(),
                FieldInfo.builder("type", TypeSignature.ofBase(JavaType.ENUM.name()))
                         .location(FieldLocation.QUERY).requirement(FieldRequirement.REQUIRED).build()));
        assertThat(getMessageV2.useParameterAsRoot()).isFalse();

        final MethodInfo getMessageV3 = serviceInfo.methods().stream()
                                                   .filter(m -> m.name().equals("GetMessageV3"))
                                                   .findFirst().get();
        assertThat(getMessageV3.httpMethod()).isEqualTo(HttpMethod.GET);
        assertThat(getMessageV3.endpoints()).containsAll(ImmutableSet.of(
                EndpointInfo.builder(virtualHostNamePattern, "/v3/messages/:message_id")
                            .availableMimeTypes(MediaType.JSON_UTF_8).build()));
        assertThat(getMessageV3.parameters()).containsAll(ImmutableList.of(
                FieldInfo.builder("message_id", TypeSignature.ofBase(JavaType.STRING.name()))
                         .location(FieldLocation.PATH).requirement(FieldRequirement.REQUIRED).build(),
                FieldInfo.builder("revision",
                                  TypeSignature.ofList(TypeSignature.ofBase(JavaType.LONG.name())))
                         .location(FieldLocation.QUERY).requirement(FieldRequirement.REQUIRED).build()));
        assertThat(getMessageV3.useParameterAsRoot()).isFalse();

        // Check HTTP PATCH method.
        final MethodInfo updateMessageV1 = serviceInfo.methods().stream()
                                                      .filter(m -> m.name().equals("UpdateMessageV1"))
                                                      .findFirst().get();
        assertThat(updateMessageV1.httpMethod()).isEqualTo(HttpMethod.PATCH);
        assertThat(updateMessageV1.endpoints()).containsAll(ImmutableSet.of(
                EndpointInfo.builder(virtualHostNamePattern, "/v1/messages/:message_id")
                            .availableMimeTypes(MediaType.JSON_UTF_8).build()));
        assertThat(updateMessageV1.parameters()).containsAll(ImmutableList.of(
                FieldInfo.builder("message_id", TypeSignature.ofBase(JavaType.STRING.name()))
                         .location(FieldLocation.PATH).requirement(FieldRequirement.REQUIRED).build(),
                FieldInfo.builder("text", TypeSignature.ofBase(JavaType.STRING.name()))
                         .location(FieldLocation.BODY).requirement(FieldRequirement.REQUIRED).build()));
        assertThat(updateMessageV1.useParameterAsRoot()).isFalse();

        final MethodInfo updateMessageV2 = serviceInfo.methods().stream()
                                                      .filter(m -> m.name().equals("UpdateMessageV2"))
                                                      .findFirst().get();
        assertThat(updateMessageV2.httpMethod()).isEqualTo(HttpMethod.PATCH);
        assertThat(updateMessageV2.endpoints()).containsAll(ImmutableSet.of(
                EndpointInfo.builder(virtualHostNamePattern, "/v2/messages/:message_id")
                            .availableMimeTypes(MediaType.JSON_UTF_8).build()));
        assertThat(updateMessageV2.parameters()).containsAll(ImmutableList.of(
                FieldInfo.builder("message_id", TypeSignature.ofBase(JavaType.STRING.name()))
                         .location(FieldLocation.PATH).requirement(FieldRequirement.REQUIRED).build(),
                FieldInfo.builder("text", TypeSignature.ofBase(JavaType.STRING.name()))
                         .location(FieldLocation.BODY).requirement(FieldRequirement.REQUIRED).build()));
        assertThat(updateMessageV2.useParameterAsRoot()).isFalse();
    }

    @Test
    void pathParamRegexIsConvertedCorrectly() {
        assertThat(convertRegexPath("/a/(?<p0>[^/]+):get"))
                .isEqualTo("/a/p0:get");
    }
}
