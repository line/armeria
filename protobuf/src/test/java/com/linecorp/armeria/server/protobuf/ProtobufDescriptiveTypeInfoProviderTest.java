/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.server.protobuf;

import static com.linecorp.armeria.server.protobuf.ProtobufDescriptiveTypeInfoProvider.BOOL;
import static com.linecorp.armeria.server.protobuf.ProtobufDescriptiveTypeInfoProvider.BYTES;
import static com.linecorp.armeria.server.protobuf.ProtobufDescriptiveTypeInfoProvider.DOUBLE;
import static com.linecorp.armeria.server.protobuf.ProtobufDescriptiveTypeInfoProvider.FIXED32;
import static com.linecorp.armeria.server.protobuf.ProtobufDescriptiveTypeInfoProvider.FIXED64;
import static com.linecorp.armeria.server.protobuf.ProtobufDescriptiveTypeInfoProvider.FLOAT;
import static com.linecorp.armeria.server.protobuf.ProtobufDescriptiveTypeInfoProvider.INT32;
import static com.linecorp.armeria.server.protobuf.ProtobufDescriptiveTypeInfoProvider.INT64;
import static com.linecorp.armeria.server.protobuf.ProtobufDescriptiveTypeInfoProvider.SINT32;
import static com.linecorp.armeria.server.protobuf.ProtobufDescriptiveTypeInfoProvider.SINT64;
import static com.linecorp.armeria.server.protobuf.ProtobufDescriptiveTypeInfoProvider.STRING;
import static com.linecorp.armeria.server.protobuf.ProtobufDescriptiveTypeInfoProvider.UINT32;
import static com.linecorp.armeria.server.protobuf.ProtobufDescriptiveTypeInfoProvider.UINT64;
import static com.linecorp.armeria.server.protobuf.ProtobufDescriptiveTypeInfoProvider.newFieldTypeInfo;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.ConsumesJson;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.docs.ContainerTypeSignature;
import com.linecorp.armeria.server.docs.DescriptiveTypeInfo;
import com.linecorp.armeria.server.docs.DescriptiveTypeInfoProvider;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.docs.EnumInfo;
import com.linecorp.armeria.server.docs.EnumValueInfo;
import com.linecorp.armeria.server.docs.FieldInfo;
import com.linecorp.armeria.server.docs.MapTypeSignature;
import com.linecorp.armeria.server.docs.StructInfo;
import com.linecorp.armeria.server.docs.TypeSignature;
import com.linecorp.armeria.server.docs.TypeSignatureType;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import testing.protobuf.Messages.CompressionType;
import testing.protobuf.Messages.ReconnectInfo;
import testing.protobuf.Messages.SimpleRequest;
import testing.protobuf.Messages.SimpleResponse;
import testing.protobuf.Messages.StreamingOutputCallRequest;
import testing.protobuf.Messages.TestMessage;

class ProtobufDescriptiveTypeInfoProviderTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.annotatedService(new ProtobufService());
            sb.serviceUnder("/docs", new DocService());
        }
    };

    @RegisterExtension
    static ServerExtension customProviderServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.annotatedService(new SimpleService());
            sb.serviceUnder("/docs",
                            DocService.builder()
                                      .descriptiveTypeInfoProvider(new CustomDescriptiveTypeInfoProvider())
                                      .build());
        }
    };

    @Test
    void newStructInfo() throws Exception {
        final StructInfo structInfo =
                ProtobufDescriptiveTypeInfoProvider.newStructInfo(TestMessage.getDescriptor());
        assertThat(structInfo.name()).isEqualTo("armeria.protobuf.testing.TestMessage");
        assertThat(structInfo.fields()).hasSize(19);
        assertThat(structInfo.fields().get(0).name()).isEqualTo("bool");
        assertThat(structInfo.fields().get(0).typeSignature()).isEqualTo(BOOL);
        assertThat(structInfo.fields().get(1).name()).isEqualTo("int32");
        assertThat(structInfo.fields().get(1).typeSignature()).isEqualTo(INT32);
        assertThat(structInfo.fields().get(2).name()).isEqualTo("int64");
        assertThat(structInfo.fields().get(2).typeSignature()).isEqualTo(INT64);
        assertThat(structInfo.fields().get(3).name()).isEqualTo("uint32");
        assertThat(structInfo.fields().get(3).typeSignature()).isEqualTo(UINT32);
        assertThat(structInfo.fields().get(4).name()).isEqualTo("uint64");
        assertThat(structInfo.fields().get(4).typeSignature()).isEqualTo(UINT64);
        assertThat(structInfo.fields().get(5).name()).isEqualTo("sint32");
        assertThat(structInfo.fields().get(5).typeSignature()).isEqualTo(SINT32);
        assertThat(structInfo.fields().get(6).name()).isEqualTo("sint64");
        assertThat(structInfo.fields().get(6).typeSignature()).isEqualTo(SINT64);
        assertThat(structInfo.fields().get(7).name()).isEqualTo("fixed32");
        assertThat(structInfo.fields().get(7).typeSignature()).isEqualTo(FIXED32);
        assertThat(structInfo.fields().get(8).name()).isEqualTo("fixed64");
        assertThat(structInfo.fields().get(8).typeSignature()).isEqualTo(FIXED64);
        assertThat(structInfo.fields().get(9).name()).isEqualTo("float");
        assertThat(structInfo.fields().get(9).typeSignature()).isEqualTo(FLOAT);
        assertThat(structInfo.fields().get(10).name()).isEqualTo("double");
        assertThat(structInfo.fields().get(10).typeSignature()).isEqualTo(DOUBLE);
        assertThat(structInfo.fields().get(11).name()).isEqualTo("string");
        assertThat(structInfo.fields().get(11).typeSignature()).isEqualTo(STRING);
        assertThat(structInfo.fields().get(12).name()).isEqualTo("bytes");
        assertThat(structInfo.fields().get(12).typeSignature()).isEqualTo(BYTES);
        assertThat(structInfo.fields().get(13).name()).isEqualTo("test_enum");
        assertThat(structInfo.fields().get(13).typeSignature().signature())
                .isEqualTo("armeria.protobuf.testing.TestEnum");

        final FieldInfo nested = structInfo.fields().get(14);
        assertThat(nested.name()).isEqualTo("nested");
        assertThat(nested.typeSignature().signature())
                .isEqualTo("armeria.protobuf.testing.TestMessage.Nested");

        assertThat(structInfo.fields().get(15).name()).isEqualTo("strings");
        final TypeSignature repeatedTpeSignature = structInfo.fields().get(15).typeSignature();
        assertThat(repeatedTpeSignature.type()).isSameAs(TypeSignatureType.ITERABLE);
        final List<TypeSignature> typeParameters =
                ((ContainerTypeSignature) repeatedTpeSignature).typeParameters();
        assertThat(typeParameters).hasSize(1);
        assertThat(typeParameters.get(0)).isSameAs(STRING);
        assertThat(structInfo.fields().get(16).name()).isEqualTo("map");
        final TypeSignature mapTypeSignature = structInfo.fields().get(16).typeSignature();
        assertThat(mapTypeSignature.type()).isSameAs(TypeSignatureType.MAP);
        assertThat(((MapTypeSignature) mapTypeSignature).keyTypeSignature()).isSameAs(STRING);
        assertThat(((MapTypeSignature) mapTypeSignature).valueTypeSignature()).isSameAs(INT32);
        final FieldInfo self = structInfo.fields().get(17);
        assertThat(self.name()).isEqualTo("self");
        final FieldInfo emptyNested = structInfo.fields().get(18);
        assertThat(emptyNested.name()).isEqualTo("empty_nested");

        assertThat(self.typeSignature().signature())
                .isEqualTo("armeria.protobuf.testing.TestMessage");
    }

    @Test
    void newEnumInfo() throws Exception {
        final EnumInfo enumInfo =
                ProtobufDescriptiveTypeInfoProvider.newEnumInfo(CompressionType.getDescriptor());
        assertThat(enumInfo).isEqualTo(new EnumInfo(
                "armeria.protobuf.testing.CompressionType",
                ImmutableList.of(new EnumValueInfo("NONE", 0),
                                 new EnumValueInfo("GZIP", 1),
                                 new EnumValueInfo("DEFLATE", 3))));
    }

    @Test
    void newListInfo() throws Exception {
        final TypeSignature list = newFieldTypeInfo(
                ReconnectInfo.getDescriptor().findFieldByNumber(ReconnectInfo.BACKOFF_MS_FIELD_NUMBER));
        assertThat(list).isEqualTo(TypeSignature.ofIterable("repeated", INT32));
    }

    @Test
    void newMapInfo() throws Exception {
        final TypeSignature map = newFieldTypeInfo(
                StreamingOutputCallRequest.getDescriptor().findFieldByNumber(
                        StreamingOutputCallRequest.OPTIONS_FIELD_NUMBER));
        assertThat(map).isEqualTo(
                TypeSignature.ofMap(STRING, INT32));
    }

    @Test
    void specification() throws Exception {
        final BlockingWebClient client = server.blockingWebClient();
        final JsonNode response = client.prepare()
                                        .get("/docs/specification.json")
                                        .asJson(JsonNode.class)
                                        .execute()
                                        .content();
        final InputStream resourceAsStream = ProtobufDescriptiveTypeInfoProviderTest.class.getResourceAsStream(
                "/testing/protobuf/ProtobufDescriptiveTypeInfoProviderTest_specification.json5");
        final JsonMapper json5Mapper = JsonMapper.builder()
                                                 .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS.mappedFeature())
                                                 .enable(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature())
                                                 .build();
        final JsonNode expected = json5Mapper.readTree(resourceAsStream);

        assertThat(response.get("services").get(0).get("name").textValue())
                .isEqualTo(ProtobufService.class.getName());

        assertThatJson(response.get("services").get(0).get("methods"))
                .isEqualTo(expected.get("services").get(0).get("methods"));
        assertThatJson(response.get("structs")).isEqualTo(expected.get("structs"));
    }

    @Test
    void customStructInfo() {
        final BlockingWebClient client = customProviderServer.blockingWebClient();

        final JsonNode response = client.prepare()
                                        .get("/docs/specification.json")
                                        .asJson(JsonNode.class)
                                        .execute()
                                        .content();
        final JsonNode param = response.get("services").get(0).get("methods").get(0)
                                       .get("parameters").get(0);
        assertThat(param.get("name").textValue()).isEqualTo("req");
        assertThat(param.get("typeSignature").textValue()).isEqualTo(
                "testing.protobuf.Messages$SimpleRequest");
        final JsonNode struct = response.get("structs").get(0);
        assertThat(struct.get("name").textValue()).isEqualTo("CustomSimpleRequest");
        final JsonNode fields = struct.get("fields");
        assertThat(fields.size()).isEqualTo(1);
        assertThat(fields.get(0).get("name").textValue()).isEqualTo("foo");
        assertThat(fields.get(0).get("typeSignature").textValue()).isEqualTo("foo");
        // Make sure that the default `DescriptiveTypeInfoProvider`s are set as the fallback of
        // the custom provider.
        assertThat(response.get("structs").get(1).get("name").textValue())
                .isEqualTo("armeria.protobuf.testing.SimpleResponse");
    }

    private static final class ProtobufService {
        @Post("/json")
        @ConsumesJson
        @ProducesJson
        public CompletableFuture<TestMessage> json(TestMessage req) {
            return UnmodifiableFuture.completedFuture(null);
        }
    }

    private static final class SimpleService {
        @Post("/simple")
        @ConsumesJson
        @ProducesJson
        public CompletableFuture<SimpleResponse> json(SimpleRequest req) {
            return UnmodifiableFuture.completedFuture(null);
        }
    }

    private static final class CustomDescriptiveTypeInfoProvider implements DescriptiveTypeInfoProvider {

        @Nullable
        @Override
        public DescriptiveTypeInfo newDescriptiveTypeInfo(Object typeDescriptor) {
            if (SimpleRequest.class.isAssignableFrom((Class<?>) typeDescriptor)) {
                return new StructInfo("CustomSimpleRequest",
                                      ImmutableList.of(FieldInfo.of("foo", TypeSignature.ofBase("foo"))));
            }
            return null;
        }
    }
}
