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
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.Descriptors.ServiceDescriptor;

import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.grpc.testing.Messages.CompressionType;
import com.linecorp.armeria.grpc.testing.Messages.ReconnectInfo;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.grpc.testing.Messages.StreamingOutputCallRequest;
import com.linecorp.armeria.grpc.testing.Messages.TestMessage;
import com.linecorp.armeria.grpc.testing.ReconnectServiceGrpc;
import com.linecorp.armeria.grpc.testing.ReconnectServiceGrpc.ReconnectServiceImplBase;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceImplBase;
import com.linecorp.armeria.grpc.testing.UnitTestServiceGrpc;
import com.linecorp.armeria.grpc.testing.UnitTestServiceGrpc.UnitTestServiceImplBase;
import com.linecorp.armeria.internal.server.grpc.GrpcDocServicePlugin.ServiceInfosBuilder;
import com.linecorp.armeria.protobuf.EmptyProtos.Empty;
import com.linecorp.armeria.server.HttpServiceWithRoutes;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.docs.DocServiceFilter;
import com.linecorp.armeria.server.docs.EndpointInfo;
import com.linecorp.armeria.server.docs.EnumInfo;
import com.linecorp.armeria.server.docs.EnumValueInfo;
import com.linecorp.armeria.server.docs.FieldInfo;
import com.linecorp.armeria.server.docs.FieldRequirement;
import com.linecorp.armeria.server.docs.MethodInfo;
import com.linecorp.armeria.server.docs.ServiceInfo;
import com.linecorp.armeria.server.docs.ServiceSpecification;
import com.linecorp.armeria.server.docs.StructInfo;
import com.linecorp.armeria.server.docs.TypeSignature;
import com.linecorp.armeria.server.grpc.GrpcService;

import io.grpc.MethodDescriptor;

class GrpcDocServicePluginTest {

    private static final ServiceDescriptor TEST_SERVICE_DESCRIPTOR =
            com.linecorp.armeria.grpc.testing.Test.getDescriptor()
                                                  .findServiceByName("TestService");

    private static final GrpcDocServicePlugin generator = new GrpcDocServicePlugin();

    @Test
    void servicesTest() throws Exception {
        final Map<String, ServiceInfo> services = services((plugin, service, method) -> true,
                                                           (plugin, service, method) -> false);

        assertThat(services).containsOnlyKeys(TestServiceGrpc.SERVICE_NAME,
                                              UnitTestServiceGrpc.SERVICE_NAME,
                                              ReconnectServiceGrpc.SERVICE_NAME);

        services.get(TestServiceGrpc.SERVICE_NAME).methods().forEach(m -> m.endpoints().forEach(e -> {
            assertThat(e.pathMapping()).isEqualTo("/armeria.grpc.testing.TestService/" + m.name());
        }));
        services.get(UnitTestServiceGrpc.SERVICE_NAME).methods().forEach(m -> m.endpoints().forEach(e -> {
            assertThat(e.pathMapping()).isEqualTo("/test/armeria.grpc.testing.UnitTestService/" + m.name());
        }));
        services.get(ReconnectServiceGrpc.SERVICE_NAME).methods().forEach(m -> m.endpoints().forEach(e -> {
            assertThat(e.pathMapping()).isEqualTo("/reconnect/armeria.grpc.testing.ReconnectService/" +
                                                  m.name());
        }));
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
                                              ReconnectServiceGrpc.SERVICE_NAME);

        // 2. Exclude specified.
        exclude = DocServiceFilter.ofMethodName(TestServiceGrpc.SERVICE_NAME, "EmptyCall").or(
                DocServiceFilter.ofMethodName(TestServiceGrpc.SERVICE_NAME, "HalfDuplexCall"));
        services = services(include, exclude);
        assertThat(services).containsOnlyKeys(TestServiceGrpc.SERVICE_NAME, UnitTestServiceGrpc.SERVICE_NAME,
                                              ReconnectServiceGrpc.SERVICE_NAME);

        List<String> methods = methods(services);
        assertThat(methods).containsExactlyInAnyOrder("FullDuplexCall",
                                                      "StreamingInputCall",
                                                      "StreamingOutputCall",
                                                      "UnaryCall",
                                                      "UnaryCall2",
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

        // Make sure all services and their endpoints exist in the specification.
        final ServiceSpecification specification = generator.generateSpecification(
                ImmutableSet.copyOf(serverBuilder.build().serviceConfigs()),
                unifyFilter(include, exclude));
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
    void newEnumInfo() throws Exception {
        final EnumInfo enumInfo = generator.newEnumInfo(CompressionType.getDescriptor());
        assertThat(enumInfo).isEqualTo(new EnumInfo(
                "armeria.grpc.testing.CompressionType",
                ImmutableList.of(new EnumValueInfo("NONE", 0),
                                 new EnumValueInfo("GZIP", 1),
                                 new EnumValueInfo("DEFLATE", 3))));
    }

    @Test
    void newListInfo() throws Exception {
        final TypeSignature list = GrpcDocServicePlugin.newFieldTypeInfo(
                ReconnectInfo.getDescriptor().findFieldByNumber(ReconnectInfo.BACKOFF_MS_FIELD_NUMBER));
        assertThat(list).isEqualTo(TypeSignature.ofContainer("repeated", GrpcDocServicePlugin.INT32));
    }

    @Test
    void newMapInfo() throws Exception {
        final TypeSignature map = GrpcDocServicePlugin.newFieldTypeInfo(
                StreamingOutputCallRequest.getDescriptor().findFieldByNumber(
                        StreamingOutputCallRequest.OPTIONS_FIELD_NUMBER));
        assertThat(map).isEqualTo(TypeSignature.ofMap(GrpcDocServicePlugin.STRING, GrpcDocServicePlugin.INT32));
    }

    @Test
    void newMethodInfo() throws Exception {
        final MethodInfo methodInfo = GrpcDocServicePlugin.newMethodInfo(
                TEST_SERVICE_DESCRIPTOR.findMethodByName("UnaryCall"),
                ImmutableSet.of(
                        EndpointInfo.builder("*", "/foo")
                                    .availableFormats(GrpcSerializationFormats.PROTO)
                                    .build(),
                        EndpointInfo.builder("*", "/debug/foo")
                                    .availableFormats(GrpcSerializationFormats.JSON)
                                    .build()));
        assertThat(methodInfo.name()).isEqualTo("UnaryCall");
        assertThat(methodInfo.returnTypeSignature().name()).isEqualTo("armeria.grpc.testing.SimpleResponse");
        assertThat(methodInfo.returnTypeSignature().namedTypeDescriptor())
                .isEqualTo(SimpleResponse.getDescriptor());
        assertThat(methodInfo.parameters()).hasSize(1);
        assertThat(methodInfo.parameters().get(0).name()).isEqualTo("request");
        assertThat(methodInfo.parameters().get(0).typeSignature().name())
                .isEqualTo("armeria.grpc.testing.SimpleRequest");
        assertThat(methodInfo.parameters().get(0).typeSignature().namedTypeDescriptor())
                .isEqualTo(SimpleRequest.getDescriptor());
        assertThat(methodInfo.exceptionTypeSignatures()).isEmpty();
        assertThat(methodInfo.docString()).isNull();
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
        assertThat(functions).hasSize(8);
        final MethodInfo emptyCall = functions.get("EmptyCall");
        assertThat(emptyCall.name()).isEqualTo("EmptyCall");
        assertThat(emptyCall.parameters())
                .containsExactly(FieldInfo.builder("request",
                                                   TypeSignature.ofNamed("armeria.grpc.testing.Empty",
                                                                         Empty.getDescriptor()))
                                          .requirement(FieldRequirement.REQUIRED)
                                          .build());
        assertThat(emptyCall.returnTypeSignature())
                .isEqualTo(TypeSignature.ofNamed("armeria.grpc.testing.Empty", Empty.getDescriptor()));

        // Just sanity check that all methods are present, function conversion is more thoroughly tested in
        // newMethodInfo()
        assertThat(functions.get("UnaryCall").name()).isEqualTo("UnaryCall");
        assertThat(functions.get("UnaryCall2").name()).isEqualTo("UnaryCall2");
        assertThat(functions.get("StreamingOutputCall").name()).isEqualTo("StreamingOutputCall");
        assertThat(functions.get("StreamingInputCall").name()).isEqualTo("StreamingInputCall");
        assertThat(functions.get("FullDuplexCall").name()).isEqualTo("FullDuplexCall");
        assertThat(functions.get("HalfDuplexCall").name()).isEqualTo("HalfDuplexCall");
        assertThat(functions.get("UnimplementedCall").name()).isEqualTo("UnimplementedCall");
    }

    @Test
    void newStructInfo() throws Exception {
        final StructInfo structInfo = generator.newStructInfo(TestMessage.getDescriptor());
        assertThat(structInfo.name()).isEqualTo("armeria.grpc.testing.TestMessage");
        assertThat(structInfo.fields()).hasSize(18);
        assertThat(structInfo.fields().get(0).name()).isEqualTo("bool");
        assertThat(structInfo.fields().get(0).typeSignature()).isEqualTo(GrpcDocServicePlugin.BOOL);
        assertThat(structInfo.fields().get(1).name()).isEqualTo("int32");
        assertThat(structInfo.fields().get(1).typeSignature()).isEqualTo(GrpcDocServicePlugin.INT32);
        assertThat(structInfo.fields().get(2).name()).isEqualTo("int64");
        assertThat(structInfo.fields().get(2).typeSignature()).isEqualTo(GrpcDocServicePlugin.INT64);
        assertThat(structInfo.fields().get(3).name()).isEqualTo("uint32");
        assertThat(structInfo.fields().get(3).typeSignature()).isEqualTo(GrpcDocServicePlugin.UINT32);
        assertThat(structInfo.fields().get(4).name()).isEqualTo("uint64");
        assertThat(structInfo.fields().get(4).typeSignature()).isEqualTo(GrpcDocServicePlugin.UINT64);
        assertThat(structInfo.fields().get(5).name()).isEqualTo("sint32");
        assertThat(structInfo.fields().get(5).typeSignature()).isEqualTo(GrpcDocServicePlugin.SINT32);
        assertThat(structInfo.fields().get(6).name()).isEqualTo("sint64");
        assertThat(structInfo.fields().get(6).typeSignature()).isEqualTo(GrpcDocServicePlugin.SINT64);
        assertThat(structInfo.fields().get(7).name()).isEqualTo("fixed32");
        assertThat(structInfo.fields().get(7).typeSignature()).isEqualTo(GrpcDocServicePlugin.FIXED32);
        assertThat(structInfo.fields().get(8).name()).isEqualTo("fixed64");
        assertThat(structInfo.fields().get(8).typeSignature()).isEqualTo(GrpcDocServicePlugin.FIXED64);
        assertThat(structInfo.fields().get(9).name()).isEqualTo("float");
        assertThat(structInfo.fields().get(9).typeSignature()).isEqualTo(GrpcDocServicePlugin.FLOAT);
        assertThat(structInfo.fields().get(10).name()).isEqualTo("double");
        assertThat(structInfo.fields().get(10).typeSignature()).isEqualTo(GrpcDocServicePlugin.DOUBLE);
        assertThat(structInfo.fields().get(11).name()).isEqualTo("string");
        assertThat(structInfo.fields().get(11).typeSignature()).isEqualTo(GrpcDocServicePlugin.STRING);
        assertThat(structInfo.fields().get(12).name()).isEqualTo("bytes");
        assertThat(structInfo.fields().get(12).typeSignature()).isEqualTo(GrpcDocServicePlugin.BYTES);
        assertThat(structInfo.fields().get(13).name()).isEqualTo("test_enum");
        assertThat(structInfo.fields().get(13).typeSignature().signature())
                .isEqualTo("armeria.grpc.testing.TestEnum");
        assertThat(structInfo.fields().get(14).name()).isEqualTo("nested");
        assertThat(structInfo.fields().get(14).typeSignature().signature())
                .isEqualTo("armeria.grpc.testing.TestMessage.Nested");
        assertThat(structInfo.fields().get(15).name()).isEqualTo("strings");
        assertThat(structInfo.fields().get(15).typeSignature().typeParameters())
                .containsExactly(GrpcDocServicePlugin.STRING);
        assertThat(structInfo.fields().get(16).name()).isEqualTo("map");
        assertThat(structInfo.fields().get(16).typeSignature().typeParameters())
                .containsExactly(GrpcDocServicePlugin.STRING, GrpcDocServicePlugin.INT32);
        assertThat(structInfo.fields().get(17).name()).isEqualTo("self");
        assertThat(structInfo.fields().get(17).typeSignature().signature())
                .isEqualTo("armeria.grpc.testing.TestMessage");
    }
}
