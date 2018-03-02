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

package com.linecorp.armeria.server.grpc;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Map;
import java.util.function.Function;

import org.junit.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.Descriptors.ServiceDescriptor;

import com.linecorp.armeria.common.HttpHeaders;
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
import com.linecorp.armeria.protobuf.EmptyProtos.Empty;
import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.VirtualHostBuilder;
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
import com.linecorp.armeria.server.grpc.GrpcDocServicePlugin.ServiceEntry;

import io.netty.util.AsciiString;

public class GrpcDocServicePluginTest {

    private static final ServiceDescriptor TEST_SERVICE_DESCRIPTOR =
            com.linecorp.armeria.grpc.testing.Test.getDescriptor()
                                                  .findServiceByName("TestService");

    private GrpcDocServicePlugin generator = new GrpcDocServicePlugin();

    @Test
    public void services() throws Exception {
        ServiceConfig testService = new ServiceConfig(
                new VirtualHostBuilder().build(),
                PathMapping.ofPrefix("/test"),
                new GrpcServiceBuilder().addService(mock(TestServiceImplBase.class)).build());

        HttpHeaders testExampleHeaders = HttpHeaders.of(AsciiString.of("test"), "service");

        ServiceConfig reconnectService = new ServiceConfig(
                new VirtualHostBuilder().build(),
                PathMapping.ofPrefix("/reconnect"),
                new GrpcServiceBuilder().addService(mock(ReconnectServiceImplBase.class)).build());

        HttpHeaders reconnectExampleHeaders = HttpHeaders.of(AsciiString.of("reconnect"), "never");

        ServiceSpecification specification = generator.generateSpecification(
                ImmutableSet.of(testService, reconnectService));

        Map<String, ServiceInfo> services = specification
                .services()
                .stream()
                .collect(toImmutableMap(ServiceInfo::name, Function.identity()));
        assertThat(services).containsOnlyKeys(TestServiceGrpc.SERVICE_NAME,
                                              ReconnectServiceGrpc.SERVICE_NAME);
    }

    @Test
    public void newEnumInfo() throws Exception {
        EnumInfo enumInfo = generator.newEnumInfo(CompressionType.getDescriptor());
        assertThat(enumInfo).isEqualTo(new EnumInfo(
                "armeria.grpc.testing.CompressionType",
                ImmutableList.of(new EnumValueInfo("NONE"),
                                 new EnumValueInfo("GZIP"),
                                 new EnumValueInfo("DEFLATE"))));
    }

    @Test
    public void newListInfo() throws Exception {
        TypeSignature list = generator.newFieldTypeInfo(
                ReconnectInfo.getDescriptor().findFieldByNumber(ReconnectInfo.BACKOFF_MS_FIELD_NUMBER));
        assertThat(list).isEqualTo(TypeSignature.ofContainer("repeated", GrpcDocServicePlugin.INT32));
    }

    @Test
    public void newMapInfo() throws Exception {
        TypeSignature map = generator.newFieldTypeInfo(
                StreamingOutputCallRequest.getDescriptor().findFieldByNumber(
                        StreamingOutputCallRequest.OPTIONS_FIELD_NUMBER));
        assertThat(map).isEqualTo(TypeSignature.ofMap(GrpcDocServicePlugin.STRING, GrpcDocServicePlugin.INT32));
    }

    @Test
    public void newMethodInfo() throws Exception {
        MethodInfo methodInfo = generator.newMethodInfo(
                TEST_SERVICE_DESCRIPTOR.findMethodByName("UnaryCall"),
                new ServiceEntry(
                        TEST_SERVICE_DESCRIPTOR,
                        ImmutableList.of(
                                new EndpointInfo("*", "/foo/", "",
                                                 GrpcSerializationFormats.PROTO,
                                                 ImmutableSet.of(GrpcSerializationFormats.PROTO)),
                                new EndpointInfo("*", "/debug/foo/", "",
                                                 GrpcSerializationFormats.JSON,
                                                 ImmutableSet.of(GrpcSerializationFormats.JSON)))));
        assertThat(methodInfo.name()).isEqualTo("UnaryCall");
        assertThat(methodInfo.returnTypeSignature().name()).isEqualTo("armeria.grpc.testing.SimpleResponse");
        assertThat(methodInfo.returnTypeSignature().namedTypeDescriptor())
                .contains(SimpleResponse.getDescriptor());
        assertThat(methodInfo.parameters()).hasSize(1);
        assertThat(methodInfo.parameters().get(0).name()).isEqualTo("request");
        assertThat(methodInfo.parameters().get(0).typeSignature().name())
                .isEqualTo("armeria.grpc.testing.SimpleRequest");
        assertThat(methodInfo.parameters().get(0).typeSignature().namedTypeDescriptor())
                .contains(SimpleRequest.getDescriptor());
        assertThat(methodInfo.exceptionTypeSignatures()).isEmpty();
        assertThat(methodInfo.docString()).isNull();
        assertThat(methodInfo.endpoints()).containsExactlyInAnyOrder(
                new EndpointInfo("*", "/foo/UnaryCall", "",
                                 GrpcSerializationFormats.PROTO,
                                 ImmutableSet.of(GrpcSerializationFormats.PROTO)),
                new EndpointInfo("*", "/debug/foo/UnaryCall", "",
                                 GrpcSerializationFormats.JSON,
                                 ImmutableSet.of(GrpcSerializationFormats.JSON)));
    }

    @Test
    public void newServiceInfo() throws Exception {
        ServiceInfo service = generator.newServiceInfo(
                new ServiceEntry(
                        TEST_SERVICE_DESCRIPTOR,
                        ImmutableList.of(
                                new EndpointInfo("*", "/foo", "a",
                                                 GrpcSerializationFormats.PROTO,
                                                 ImmutableSet.of(GrpcSerializationFormats.PROTO)),
                                new EndpointInfo("*", "/debug/foo", "b",
                                                 GrpcSerializationFormats.JSON,
                                                 ImmutableSet.of(GrpcSerializationFormats.JSON)))));

        Map<String, MethodInfo> functions = service
                .methods()
                .stream()
                .collect(toImmutableMap(MethodInfo::name, Function.identity()));
        assertThat(functions).hasSize(8);
        MethodInfo emptyCall = functions.get("EmptyCall");
        assertThat(emptyCall.name()).isEqualTo("EmptyCall");
        assertThat(emptyCall.parameters())
                .containsExactly(
                        new FieldInfo(
                                "request",
                                FieldRequirement.REQUIRED,
                                TypeSignature.ofNamed("armeria.grpc.testing.Empty", Empty.getDescriptor())));
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
    public void newStructInfo() throws Exception {
        StructInfo structInfo = (StructInfo) generator.newStructInfo(TestMessage.getDescriptor());
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
