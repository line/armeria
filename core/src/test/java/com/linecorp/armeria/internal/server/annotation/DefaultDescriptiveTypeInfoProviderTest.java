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

package com.linecorp.armeria.internal.server.annotation;

import static com.linecorp.armeria.internal.server.annotation.AnnotatedDocServicePlugin.INT;
import static com.linecorp.armeria.internal.server.annotation.AnnotatedDocServicePlugin.STRING;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.ConsumesJson;
import com.linecorp.armeria.server.annotation.Description;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.docs.DescriptionInfo;
import com.linecorp.armeria.server.docs.DescriptiveTypeInfo;
import com.linecorp.armeria.server.docs.DescriptiveTypeInfoProvider;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.docs.FieldInfo;
import com.linecorp.armeria.server.docs.FieldRequirement;
import com.linecorp.armeria.server.docs.Markup;
import com.linecorp.armeria.server.docs.StructInfo;
import com.linecorp.armeria.server.docs.TypeSignature;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class DefaultDescriptiveTypeInfoProviderTest {

    private static final String RESOURCE_PATH_PREFIX =
            "/testing/core/" + DefaultDescriptiveTypeInfoProviderTest.class.getSimpleName() + '/';

    private static final JsonMapper json5Mapper =
            JsonMapper.builder()
                      .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS.mappedFeature())
                      .enable(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature())
                      .build();

    @RegisterExtension
    static ServerExtension jsonServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.annotatedService(new JsonService());

            sb.serviceUnder("/docs", new DocService());
            sb.serviceUnder("/docs-custom",
                            DocService.builder()
                                      .descriptiveTypeInfoProvider(new CustomDescriptiveTypeInfoProvider())
                                      .build());
            sb.decorator(LoggingService.newDecorator());
        }
    };

    @RegisterExtension
    static ServerExtension paramServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.annotatedService(new ParamService());
            sb.serviceUnder("/docs", new DocService());
        }
    };

    static final DefaultDescriptiveTypeInfoProvider REQUEST_STRUCT_INFO_PROVIDER =
            new DefaultDescriptiveTypeInfoProvider(true);
    static final DefaultDescriptiveTypeInfoProvider RESPONSE_STRUCT_INFO_PROVIDER =
            new DefaultDescriptiveTypeInfoProvider(false);

    @Test
    void requestObject() {
        final StructInfo structInfo =
                (StructInfo) REQUEST_STRUCT_INFO_PROVIDER.newDescriptiveTypeInfo(FooRequest.class);
        assertThat(structInfo.name()).isEqualTo(FooRequest.class.getName());
        assertThat(structInfo.fields()).containsExactlyInAnyOrder(
                newRequiredField("intField", INT),
                newRequiredField("stringField", STRING),
                newRequiredField("sameName", TypeSignature.ofStruct(Inner.class)),
                newRequiredField("rename", TypeSignature.ofStruct(Inner.class)),
                newRequiredField("collection", TypeSignature.ofList(TypeSignature.ofStruct(Inner.class))),
                newOptionalField("nullableField", STRING),
                newOptionalField("optionalField", TypeSignature.ofStruct(Inner.class)),
                newRequiredField("circular", TypeSignature.ofStruct(FooRequest.class)));
    }

    @Test
    void responseObject() {
        final StructInfo structInfo =
                (StructInfo) RESPONSE_STRUCT_INFO_PROVIDER.newDescriptiveTypeInfo(BarResponse.class);
        assertThat(structInfo.name()).isEqualTo(BarResponse.class.getName());
        assertThat(structInfo.fields()).containsExactlyInAnyOrder(
                newRequiredField("intField", INT),
                newRequiredField("getterField", STRING),
                newOptionalField("getterNullableField", STRING),
                newRequiredField("sameName", TypeSignature.ofStruct(Inner.class)),
                newRequiredField("rename", TypeSignature.ofStruct(Inner.class)),
                newRequiredField("collection", TypeSignature.ofList(TypeSignature.ofStruct(Inner.class))),
                newOptionalField("nullableField", STRING),
                newOptionalField("optionalField", STRING),
                newRequiredField("circular", TypeSignature.ofStruct(BarResponse.class)));
    }

    @ArgumentsSource(UndescriptiveTypeProvider.class)
    @ParameterizedTest
    void undescriptiveTypes(Class<?> clazz) {
        StructInfo structInfo = (StructInfo) REQUEST_STRUCT_INFO_PROVIDER.newDescriptiveTypeInfo(clazz);
        assertThat(structInfo.name()).isEqualTo(clazz.getName());
        assertThat(structInfo.fields()).isEmpty();

        structInfo = (StructInfo) RESPONSE_STRUCT_INFO_PROVIDER.newDescriptiveTypeInfo(clazz);
        assertThat(structInfo.name()).isEqualTo(clazz.getName());
        assertThat(structInfo.fields()).isEmpty();
    }

    @Test
    void requestDescriptor() {
        StructInfo structInfo =
                (StructInfo) REQUEST_STRUCT_INFO_PROVIDER.newDescriptiveTypeInfo(RequestConstructor.class);
        assertThat(structInfo.fields()).containsExactlyInAnyOrder(
                FieldInfo.builder("constructorField1", STRING)
                         .requirement(FieldRequirement.REQUIRED)
                         .descriptionInfo(DescriptionInfo.of("constructor description1", Markup.NONE))
                         .build(),
                FieldInfo.builder("constructorField2", STRING)
                         .requirement(FieldRequirement.OPTIONAL)
                         .descriptionInfo(DescriptionInfo.of("constructor description2", Markup.NONE))
                         .build());

        structInfo = (StructInfo) REQUEST_STRUCT_INFO_PROVIDER.newDescriptiveTypeInfo(RequestSetters.class);
        assertThat(structInfo.fields()).containsExactlyInAnyOrder(
                FieldInfo.builder("setterField1", STRING)
                         .requirement(FieldRequirement.REQUIRED)
                         .descriptionInfo(DescriptionInfo.of("setter description1", Markup.NONE))
                         .build(),
                FieldInfo.builder("setterField2", STRING)
                         .requirement(FieldRequirement.OPTIONAL)
                         .descriptionInfo(DescriptionInfo.of("setter description2", Markup.NONE))
                         .build());

        structInfo = (StructInfo) REQUEST_STRUCT_INFO_PROVIDER.newDescriptiveTypeInfo(
                RequestWithSettersNonPrefix.class);
        assertThat(structInfo.fields()).containsExactlyInAnyOrder(
                FieldInfo.builder("nonPrefixSetterField1", STRING)
                         .requirement(FieldRequirement.REQUIRED)
                         .descriptionInfo(DescriptionInfo.of("non prefix setter description1", Markup.NONE))
                         .build(),
                FieldInfo.builder("nonPrefixSetterField2", STRING)
                         .requirement(FieldRequirement.OPTIONAL)
                         .descriptionInfo(DescriptionInfo.of("non prefix setter description2", Markup.NONE))
                         .build());
    }

    @Test
    void responseDescriptor() {
        StructInfo structInfo =
                (StructInfo) RESPONSE_STRUCT_INFO_PROVIDER.newDescriptiveTypeInfo(ResponseGetters.class);
        assertThat(structInfo.fields()).containsExactly(
                FieldInfo.builder("getterField1", STRING)
                         .requirement(FieldRequirement.REQUIRED)
                         .descriptionInfo(DescriptionInfo.of("getter description1", Markup.NONE))
                         .build(),
                FieldInfo.builder("getterField2", STRING)
                         .requirement(FieldRequirement.OPTIONAL)
                         .descriptionInfo(DescriptionInfo.of("getter description2", Markup.NONE))
                         .build());

        structInfo =
                (StructInfo) RESPONSE_STRUCT_INFO_PROVIDER.newDescriptiveTypeInfo(
                        ResponseGettersWithNonPrefix.class);
        assertThat(structInfo.fields()).containsExactly(
                FieldInfo.builder("nonPrefixGetterField1", STRING)
                         .requirement(FieldRequirement.REQUIRED)
                         .descriptionInfo(DescriptionInfo.of("non prefix getter description1", Markup.NONE))
                         .build(),
                FieldInfo.builder("nonPrefixGetterField2", STRING)
                         .requirement(FieldRequirement.REQUIRED)
                         .descriptionInfo(DescriptionInfo.of("non prefix getter description2", Markup.NONE))
                         .build());
    }

    @Test
    void reflectiveStructInfo() {
        for (DescriptiveTypeInfoProvider provider : ImmutableList.of(REQUEST_STRUCT_INFO_PROVIDER,
                                                                     RESPONSE_STRUCT_INFO_PROVIDER)) {
            StructInfo structInfo = (StructInfo) provider.newDescriptiveTypeInfo(ParamId.class);
            assertThat(structInfo.name()).isEqualTo(ParamId.class.getName());
            assertThat(structInfo.descriptionInfo()).isEqualTo(DescriptionInfo.of("ParamId class"));
            assertThat(structInfo.fields()).containsExactly(
                    FieldInfo.builder("id", INT)
                             .requirement(FieldRequirement.REQUIRED)
                             .descriptionInfo(DescriptionInfo.of("param id"))
                             .build());

            structInfo = (StructInfo) provider.newDescriptiveTypeInfo(ParamQuery.class);
            assertThat(structInfo.name()).isEqualTo(ParamQuery.class.getName());
            assertThat(structInfo.descriptionInfo()).isEqualTo(DescriptionInfo.of("ParamQuery class"));
            assertThat(structInfo.fields())
                    .containsExactly(
                            FieldInfo.builder("query", STRING)
                                     .requirement(FieldRequirement.OPTIONAL)
                                     .descriptionInfo(DescriptionInfo.of("param query"))
                                     .build());
        }
    }

    @Test
    void paramSpecification() throws IOException {
        final BlockingWebClient client = paramServer.blockingWebClient();
        final JsonNode response = client.prepare()
                                        .get("/docs/specification.json")
                                        .asJson(JsonNode.class)
                                        .execute()
                                        .content();

        final InputStream resourceAsStream = DefaultDescriptiveTypeInfoProviderTest.class.getResourceAsStream(
                RESOURCE_PATH_PREFIX + "ReflectiveDescriptiveTypeInfoProviderTest_specification.json5");
        final JsonNode expected = json5Mapper.readTree(resourceAsStream);

        assertThat(response.get("services").get(0).get("name").textValue())
                .isEqualTo(ParamService.class.getName());

        assertThatJson(response.get("services").get(0).get("methods"))
                .isEqualTo(expected.get("services").get(0).get("methods"));
        assertThatJson(response.get("structs")).isEqualTo(expected.get("structs"));
    }

    @Test
    void jsonSpecification() throws Exception {
        final BlockingWebClient client = jsonServer.blockingWebClient();
        final JsonNode response = client.prepare()
                                        .get("/docs/specification.json")
                                        .asJson(JsonNode.class)
                                        .execute()
                                        .content();
        final InputStream resourceAsStream = DefaultDescriptiveTypeInfoProviderTest.class.getResourceAsStream(
                RESOURCE_PATH_PREFIX + "JsonDescriptiveTypeInfoProviderTest_specification.json5");
        final JsonNode expected = json5Mapper.readTree(resourceAsStream);

        assertThat(response.get("services").get(0).get("name").textValue())
                .isEqualTo(JsonService.class.getName());

        assertThatJson(response.get("services").get(0).get("methods"))
                .isEqualTo(expected.get("services").get(0).get("methods"));
        assertThatJson(response.get("structs")).isEqualTo(expected.get("structs"));
    }

    @Test
    void customStructInfo() throws JsonProcessingException {
        final BlockingWebClient client = jsonServer.blockingWebClient();

        final JsonNode response = client.prepare()
                                        .get("/docs-custom/specification.json")
                                        .asJson(JsonNode.class)
                                        .execute()
                                        .content();

        final JsonNode param = response.get("services").get(0).get("methods").get(0)
                                       .get("parameters").get(0);
        assertThat(param.get("name").textValue()).isEqualTo("req");
        assertThat(param.get("typeSignature").textValue()).isEqualTo(
                "com.linecorp.armeria.internal.server.annotation.DefaultDescriptiveTypeInfoProviderTest$" +
                "FooRequest");
        final JsonNode struct = response.get("structs").get(1);
        assertThat(struct.get("name").textValue()).isEqualTo("CustomFooRequestInfo");
        final JsonNode fieldInfos = struct.get("fields");
        assertThat(fieldInfos.size()).isEqualTo(1);
        assertThat(fieldInfos.get(0).get("name").textValue()).isEqualTo("foo");
        assertThat(fieldInfos.get(0).get("typeSignature").textValue()).isEqualTo("foo");
        assertThat(response.get("structs").get(0).get("name").textValue()).isEqualTo("CustomBarRequestInfo");
    }

    private static FieldInfo newRequiredField(String name, TypeSignature signature) {
        return newField(name, signature, FieldRequirement.REQUIRED);
    }

    private static FieldInfo newOptionalField(String name, TypeSignature signature) {
        return newField(name, signature, FieldRequirement.OPTIONAL);
    }

    private static FieldInfo newField(String name, TypeSignature signature, FieldRequirement fieldRequirement) {
        return FieldInfo.builder(name, signature).requirement(fieldRequirement).build();
    }

    private static final class CustomDescriptiveTypeInfoProvider implements DescriptiveTypeInfoProvider {

        @Nullable
        @Override
        public DescriptiveTypeInfo newDescriptiveTypeInfo(Object typeDescriptor) {
            final Class<?> clazz = (Class<?>) typeDescriptor;
            if (FooRequest.class.isAssignableFrom(clazz)) {
                return new StructInfo("CustomFooRequestInfo",
                                      ImmutableList.of(FieldInfo.of("foo", TypeSignature.ofBase("foo"))));
            } else if (BarResponse.class.isAssignableFrom(clazz)) {
                return new StructInfo("CustomBarRequestInfo",
                                      ImmutableList.of(FieldInfo.of("bar", TypeSignature.ofBase("bar"))));
            } else {
                return null;
            }
        }
    }

    private static final class UndescriptiveTypeProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            return Stream.of(HttpResponse.class, JsonNode.class)
                         .map(Arguments::of);
        }
    }

    private static final class JsonService {
        @Post("/json")
        @ConsumesJson
        @ProducesJson
        public CompletableFuture<BarResponse> json(FooRequest req) {
            return UnmodifiableFuture.completedFuture(null);
        }
    }

    private static final class ParamService {
        @Get("/param/{id}")
        public CompletableFuture<String> param(@Param ParamId id, @Param @Nullable ParamQuery query) {
            return UnmodifiableFuture.completedFuture(null);
        }
    }

    @Description("ParamId class")
    private static final class ParamId {
        @Description("param id")
        private final int id;

        @SuppressWarnings("RedundantModifier")
        public ParamId(String id) {
            this.id = Integer.parseInt(id);
        }
    }

    @Description("ParamQuery class")
    private static final class ParamQuery {

        public static ParamQuery of(String query) {
            return new ParamQuery(query);
        }

        @Description("param query")
        @Nullable
        private final String query;

        private ParamQuery(@Nullable String query) {
            this.query = query;
        }
    }

    private static final class FooRequest {
        private final int intField;
        private final String stringField;
        private final Inner sameName;
        private final Inner inner;
        private final List<Inner> collection;
        @Nullable
        private final String nullableField;
        private final Optional<Inner> optionalField;
        private final FooRequest circular;

        @JsonCreator
        FooRequest(@JsonProperty("intField") int intField, @JsonProperty("stringField") String stringField,
                   @JsonProperty("sameName") Inner sameName, @JsonProperty("rename") Inner inner,
                   @JsonProperty("collection") List<Inner> collection,
                   @JsonProperty("nullableField") @Nullable String nullableField,
                   @JsonProperty("optionalField") Optional<Inner> optionalField,
                   @JsonProperty("circular") FooRequest circular) {
            this.intField = intField;
            this.stringField = stringField;
            this.sameName = sameName;
            this.inner = inner;
            this.collection = collection;
            this.nullableField = nullableField;
            this.optionalField = optionalField;
            this.circular = circular;
        }
    }

    private static final class BarResponse {
        private int intField;
        private String getterField;
        private Inner sameName;
        private Inner inner;
        private List<Inner> collection;
        @Nullable
        private String nullableField;
        private Optional<String> optionalField;
        private BarResponse circular;

        @JsonProperty("intField")
        public int intField() {
            return intField;
        }

        public String getGetterField() {
            return getterField;
        }

        // Extract `FieldInfo` from the method
        @Nullable
        public String getGetterNullableField() {
            return null;
        }

        @JsonProperty("sameName")
        public Inner sameName() {
            return sameName;
        }

        @JsonProperty("rename")
        public Inner inner() {
            return inner;
        }

        @JsonProperty("collection")
        public List<Inner> collection() {
            return collection;
        }

        @JsonProperty("nullableField")
        @Nullable
        public String nullableField() {
            return nullableField;
        }

        @JsonProperty("optionalField")
        public Optional<String> optionalField() {
            return optionalField;
        }

        @JsonProperty("circular")
        public BarResponse circular() {
            return circular;
        }
    }

    private static final class Inner {
        private final boolean innerValue;

        @JsonCreator
        Inner(@JsonProperty("innerValue") boolean innerValue) {
            this.innerValue = innerValue;
        }

        @JsonProperty("innerValue")
        public boolean innerValue() {
            return innerValue;
        }
    }

    private static final class RequestConstructor {
        private final String constructorField1;
        private final String constructorField2;

        @JsonCreator
        RequestConstructor(@JsonProperty("constructorField1")
                           @Description("constructor description1") String constructorField1,
                           @JsonProperty("constructorField2")
                           @Description("constructor description2") @Nullable String constructorField2) {
            this.constructorField1 = constructorField1;
            this.constructorField2 = constructorField2;
        }
    }

    private static final class RequestSetters {
        private String setterField1;
        private String setterField2;

        @Description(value = "setter description1")
        public void setSetterField1(String setterField1) {
            this.setterField1 = setterField1;
        }

        @Description(value = "setter description2")
        public void setSetterField2(@Nullable String setterField2) {
            this.setterField2 = setterField2;
        }
    }

    private static final class RequestWithSettersNonPrefix {
        @Description("non prefix setter description1")
        private String nonPrefixSetterField1;
        private String nonPrefixSetterField2;

        @JsonProperty("nonPrefixSetterField1")
        public void nonPrefixSetterField1(String nonPrefixSetterField1) {
            this.nonPrefixSetterField1 = nonPrefixSetterField1;
        }

        @Description("non prefix setter description2")
        @JsonProperty("nonPrefixSetterField2")
        public void nonPrefixSetterField2(@Nullable String nonPrefixSetterField2) {
            this.nonPrefixSetterField2 = nonPrefixSetterField2;
        }
    }

    private static final class ResponseGetters {
        private String getterField1;
        @Description("getter description2")
        @Nullable
        private String getterField2;

        @Description("getter description1")
        public String getGetterField1() {
            return getterField1;
        }

        @Nullable
        public String getGetterField2() {
            return getterField2;
        }
    }

    private static final class ResponseGettersWithNonPrefix {
        @Nullable
        @Description("non prefix getter description1")
        private String nonPrefixGetterField1;
        private String nonPrefixGetterField2;

        @JsonProperty("nonPrefixGetterField1")
        public String nonPrefixGetterField1() {
            return nonPrefixGetterField1;
        }

        @Description("non prefix getter description2")
        @JsonProperty("nonPrefixGetterField2")
        public String nonPrefixGetterField2() {
            return nonPrefixGetterField2;
        }
    }
}
