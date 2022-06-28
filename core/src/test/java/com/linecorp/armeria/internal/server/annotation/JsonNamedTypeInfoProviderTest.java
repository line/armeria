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

import static com.linecorp.armeria.internal.server.annotation.AnnotatedDocServicePlugin.BOOLEAN;
import static com.linecorp.armeria.internal.server.annotation.AnnotatedDocServicePlugin.INT;
import static com.linecorp.armeria.internal.server.annotation.AnnotatedDocServicePlugin.STRING;
import static com.linecorp.armeria.server.docs.FieldRequirement.UNSPECIFIED;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

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
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.docs.FieldInfo;
import com.linecorp.armeria.server.docs.FieldInfoBuilder;
import com.linecorp.armeria.server.docs.FieldRequirement;
import com.linecorp.armeria.server.docs.NamedTypeInfo;
import com.linecorp.armeria.server.docs.NamedTypeInfoProvider;
import com.linecorp.armeria.server.docs.StructInfo;
import com.linecorp.armeria.server.docs.TypeSignature;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class JsonNamedTypeInfoProviderTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.annotatedService(new JsonService());

            sb.serviceUnder("/docs", new DocService());
            sb.serviceUnder("/docs-custom", DocService.builder()
                                                      .namedTypeInfoProvider(new CustomNamedTypeInfoProvider())
                                                      .build());
            sb.decorator(LoggingService.newDecorator());
        }
    };

    @RegisterExtension
    static ServerExtension serverWithCustomProvider = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.annotatedService(new JsonService());

            sb.decorator(LoggingService.newDecorator());
        }
    };

    final JsonNamedTypeInfoProvider requestStructInfoProvider = new JsonNamedTypeInfoProvider(true);
    final JsonNamedTypeInfoProvider responseStructInfoProvider = new JsonNamedTypeInfoProvider(false);

    @Test
    void requestObject() {
        final StructInfo structInfo = (StructInfo) requestStructInfoProvider.newNamedTypeInfo(FooRequest.class);
        assertThat(structInfo.name()).isEqualTo(FooRequest.class.getName());
        assertThat(structInfo.fields()).containsExactlyInAnyOrder(
                newRequiredField("intField", INT),
                newRequiredField("stringField", STRING),
                newRequiredField("sameName", TypeSignature.ofNamed(Inner.class),
                                 newRequiredField("innerValue", BOOLEAN)),
                // Can't get a full field information from a renamed JSON property.
                newUnspecifiedField("rename", TypeSignature.ofNamed(Inner.class),
                                    newRequiredField("innerValue", BOOLEAN)),
                newRequiredField("collection", TypeSignature.ofList(Inner.class)),
                newOptionalField("nullableField", STRING),
                newOptionalField("optionalField", TypeSignature.ofNamed(Inner.class),
                                 newRequiredField("innerValue", BOOLEAN)),
                newRequiredField("circular", TypeSignature.ofNamed(FooRequest.class)));
    }

    @Test
    void responseObject() {
        final StructInfo structInfo =
                (StructInfo) responseStructInfoProvider.newNamedTypeInfo(BarResponse.class);
        assertThat(structInfo.name()).isEqualTo(BarResponse.class.getName());
        assertThat(structInfo.fields()).containsExactlyInAnyOrder(
                newRequiredField("intField", INT),
                newRequiredField("getterField", STRING),
                newOptionalField("getterNullableField", STRING),
                newRequiredField("sameName", TypeSignature.ofNamed(Inner.class),
                                 newRequiredField("innerValue", BOOLEAN)),
                // Can't get a full field information from a renamed JSON property.
                newUnspecifiedField("rename", TypeSignature.ofNamed(Inner.class),
                                    newRequiredField("innerValue", BOOLEAN)),
                newRequiredField("collection", TypeSignature.ofList(Inner.class)),
                newOptionalField("nullableField", STRING),
                newOptionalField("optionalField", STRING),
                newRequiredField("circular", TypeSignature.ofNamed(BarResponse.class)));
    }

    @ArgumentsSource(UnnamedTypeProvider.class)
    @ParameterizedTest
    void unnamedTypes(Class<?> clazz) {
        StructInfo structInfo = (StructInfo) responseStructInfoProvider.newNamedTypeInfo(clazz);
        assertThat(structInfo.name()).isEqualTo(clazz.getName());
        assertThat(structInfo.fields()).isEmpty();

        structInfo = (StructInfo) responseStructInfoProvider.newNamedTypeInfo(clazz);
        assertThat(structInfo.name()).isEqualTo(clazz.getName());
        assertThat(structInfo.fields()).isEmpty();
    }

    @Test
    void specification() throws Exception {
        final BlockingWebClient client = server.blockingWebClient();

        final JsonNode response = client.prepare()
                                        .get("/docs/specification.json")
                                        .asJson(JsonNode.class)
                                        .execute()
                                        .content();
        final InputStream resourceAsStream = JsonNamedTypeInfoProviderTest.class.getResourceAsStream(
                "JsonNamedTypeInfoProviderTest_specification.json5");
        final JsonMapper json5Mapper = JsonMapper.builder()
                                                 .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS.mappedFeature())
                                                 .enable(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature())
                                                 .build();
        final JsonNode expected = json5Mapper.readTree(resourceAsStream);

        assertThat(response.get("services").get(0).get("name").textValue())
                .isEqualTo(JsonService.class.getName());

        assertThatJson(response.get("services").get(0).get("methods"))
                .isEqualTo(expected.get("services").get(0).get("methods"));
        assertThatJson(response.get("structs")).isEqualTo(expected.get("structs"));
    }

    @Test
    void customStructInfo() {
        final BlockingWebClient client = server.blockingWebClient();

        final JsonNode response = client.prepare()
                                      .get("/docs-custom/specification.json")
                                      .asJson(JsonNode.class)
                                      .execute()
                                      .content();
        System.out.println(response);
        final JsonNode param = response.get("services").get(0).get("methods").get(0)
                                       .get("parameters").get(0);
        assertThat(param.get("name").textValue()).isEqualTo("CustomFooRequestInfo");
        final JsonNode fieldInfos = param.get("childFieldInfos");
        assertThat(fieldInfos.size()).isEqualTo(1);
        assertThat(fieldInfos.get(0).get("name").textValue()).isEqualTo("foo");
        assertThat(fieldInfos.get(0).get("typeSignature").textValue()).isEqualTo("foo");
        assertThat(response.get("structs").get(0).get("name").textValue()).isEqualTo("CustomBarRequestInfo");
    }

    private static FieldInfo newRequiredField(String name, TypeSignature signature, FieldInfo... childFields) {
        return newField(name, signature, FieldRequirement.REQUIRED, childFields);
    }

    private static FieldInfo newOptionalField(String name, TypeSignature signature, FieldInfo... childFields) {
        return newField(name, signature, FieldRequirement.OPTIONAL, childFields);
    }

    private static FieldInfo newUnspecifiedField(String name, TypeSignature signature,
                                                 FieldInfo... childFields) {
        return newField(name, signature, UNSPECIFIED, childFields);
    }

    private static FieldInfo newField(String name, TypeSignature signature, FieldRequirement fieldRequirement,
                                      FieldInfo... childFields) {
        final FieldInfoBuilder builder;
        if (childFields.length == 0) {
            builder = FieldInfo.builder(name, signature);
        } else {
            builder = FieldInfo.builder(name, signature, childFields);
        }
        return builder.requirement(fieldRequirement)
                      .build();
    }

    private static final class CustomNamedTypeInfoProvider implements NamedTypeInfoProvider {

        @Nullable
        @Override
        public NamedTypeInfo newNamedTypeInfo(Object typeDescriptor) {
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

    private static final class UnnamedTypeProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            return Stream.of(HttpResponse.class, JsonNode.class)
                         .map(Arguments::of);
        }
    }

    private static class JsonService {
        @Post("/json")
        @ConsumesJson
        @ProducesJson
        public CompletableFuture<BarResponse> json(FooRequest req) {
            return UnmodifiableFuture.completedFuture(null);
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
}
