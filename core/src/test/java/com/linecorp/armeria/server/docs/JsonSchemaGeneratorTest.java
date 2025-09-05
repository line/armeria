/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.server.docs;

import static java.util.Objects.requireNonNull;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.internal.server.annotation.DocServiceTestUtil;

class JsonSchemaGeneratorTest {
    private static final String methodName = "test-method";

    private static ServiceSpecification newServiceSpecificationWithRequestStruct(StructInfo... structInfos) {

        final MethodInfo methodInfo = new MethodInfo("test-service", methodName, 0, // overloadId
                                                     TypeSignature.ofBase("void"), // returnType
                                                     ImmutableList.of(FieldInfo.of("request",
                                                                                   TypeSignature.ofStruct(
                                                                                           methodName,
                                                                                           new Object()))),
                                                     ImmutableList.of(), // exampleHeaders
                                                     ImmutableList.of(), // endpoints
                                                     HttpMethod.POST, DescriptionInfo.empty());

        return new ServiceSpecification(
                ImmutableList.of(new ServiceInfo("test-service", ImmutableList.of(methodInfo))),
                ImmutableList.of(), Arrays.stream(structInfos).collect(Collectors.toList()),
                ImmutableList.of());
    }

    @Test
    void testGenerateSimpleMethodWithoutParameters() {
        final StructInfo structInfo = new StructInfo(methodName, ImmutableList.of());
        final ServiceSpecification spec = newServiceSpecificationWithRequestStruct(structInfo);
        final JsonNode methodSchema = JsonSchemaGenerator.generate(spec).get(0);

        assertThatJson(methodSchema).node("title").isEqualTo(methodName);
        assertThatJson(methodSchema).node("properties.request.$ref").isEqualTo("#/definitions/test-method");
        assertThatJson(methodSchema).node("definitions.test-method.properties").isAbsent();
    }

    @Test
    void testGenerateSimpleMethodWithPrimitiveParameters() {
        final List<FieldInfo> parameters = ImmutableList.of(FieldInfo.of("param1", TypeSignature.ofBase("int")),
                                                            FieldInfo.of("param2",
                                                                         TypeSignature.ofBase("double")),
                                                            FieldInfo.of("param3",
                                                                         TypeSignature.ofBase("string")),
                                                            FieldInfo.of("param4",
                                                                         TypeSignature.ofBase("boolean")));
        final StructInfo structInfo = new StructInfo(methodName, parameters);
        final ServiceSpecification spec = newServiceSpecificationWithRequestStruct(structInfo);
        final JsonNode methodSchema = JsonSchemaGenerator.generate(spec).get(0);

        assertThatJson(methodSchema).node("properties.request.$ref").isEqualTo("#/definitions/test-method");
        final JsonNode definition = methodSchema.get("definitions").get(methodName);
        assertThatJson(definition).node("properties.param1.type").isEqualTo("integer");
        assertThatJson(definition).node("properties.param2.type").isEqualTo("number");
        assertThatJson(definition).node("properties.param3.type").isEqualTo("string");
        assertThatJson(definition).node("properties.param4.type").isEqualTo("boolean");
    }

    @Test
    void testMethodWithRecursivePath() {
        final Object commonTypeObjectForRecursion = new Object();

        final FieldInfo param1 = FieldInfo.of("param1", TypeSignature.ofBase("int"));
        final FieldInfo paramRecursive = FieldInfo.of("paramRecursive",
                                                      TypeSignature.ofStruct("rec",
                                                                             commonTypeObjectForRecursion));

        final List<FieldInfo> parameters = ImmutableList.of(param1, paramRecursive);

        final StructInfo structInfo = new StructInfo(methodName, parameters);

        final List<FieldInfo> parametersOfRec = ImmutableList.of(
                FieldInfo.of("inner-param1", TypeSignature.ofBase("int32")),
                FieldInfo.of("inner-recurse", TypeSignature.ofStruct("rec", commonTypeObjectForRecursion)));
        final StructInfo rec = new StructInfo("rec", parametersOfRec);

        final ServiceSpecification spec = newServiceSpecificationWithRequestStruct(structInfo, rec);
        final JsonNode methodSchema = JsonSchemaGenerator.generate(spec).get(0);

        assertThatJson(methodSchema).node("definitions.rec.properties.inner-param1.type").isEqualTo("integer");
        assertThatJson(methodSchema).node("definitions.rec.properties.inner-recurse.$ref").isEqualTo(
                "#/definitions/rec");
    }

    @Test
    void shouldGenerateOneOfForPolymorphicType() {
        // 1. Arrange (Given): Create a provider chain and generate StructInfos by analyzing actual classes.
        final DescriptiveTypeInfoProvider providerChain = new JacksonPolymorphismTypeInfoProvider().orElse(
                DocServiceTestUtil.newDefaultDescriptiveTypeInfoProvider(false));

        final StructInfo animalInfo = (StructInfo) providerChain.newDescriptiveTypeInfo(Animal.class);
        final StructInfo dogInfo = (StructInfo) providerChain.newDescriptiveTypeInfo(Dog.class);
        final StructInfo catInfo = (StructInfo) providerChain.newDescriptiveTypeInfo(Cat.class);
        final StructInfo toyInfo = (StructInfo) providerChain.newDescriptiveTypeInfo(Toy.class);
        final StructInfo mammalInfo = (StructInfo) providerChain.newDescriptiveTypeInfo(Mammal.class);

        assertThat(animalInfo).isNotNull();
        assertThat(dogInfo).isNotNull();
        assertThat(catInfo).isNotNull();
        assertThat(toyInfo).isNotNull();
        assertThat(mammalInfo).isNotNull();

        final EndpointInfo endpoint = EndpointInfo.builder("*", "/test-polymorphism").defaultMimeType(
                MediaType.JSON_UTF_8).build();

        final MethodInfo testMethod = new MethodInfo("animal-service", "animalMethod", 0,
                                                     TypeSignature.ofBase("void"), ImmutableList.of(
                FieldInfo.of("animal", TypeSignature.ofStruct(Animal.class))), ImmutableList.of(),
                                                     ImmutableList.of(endpoint), HttpMethod.POST,
                                                     DescriptionInfo.empty());

        final ServiceSpecification specification = new ServiceSpecification(
                ImmutableList.of(new ServiceInfo("animal-service", ImmutableList.of(testMethod))),
                ImmutableList.of(), ImmutableList.of(animalInfo, dogInfo, catInfo, toyInfo, mammalInfo),
                ImmutableList.of());

        // 2. Act (When): Generate the JSON schema.
        final JsonNode methodSchema = JsonSchemaGenerator.generate(specification).get(0);

        // 3. Assert (Then): Verify the generated schema.
        final JsonNode definitions = methodSchema.get("definitions");
        final JsonNode animalSchema = definitions.get(Animal.class.getName());
        final JsonNode discriminator = animalSchema.get("discriminator");

        assertThat(animalSchema).isNotNull();

        assertThat(discriminator).isNotNull();
        assertThatJson(discriminator).node("propertyName").isEqualTo("species");

        assertThatJson(animalSchema).node("oneOf").isArray().ofLength(2);
        assertThatJson(animalSchema).node("oneOf[0].$ref").isEqualTo("#/definitions/" + Dog.class.getName());
        assertThatJson(animalSchema).node("oneOf[1].$ref").isEqualTo("#/definitions/" + Cat.class.getName());

        assertThatJson(discriminator).node("mapping.dog")
                                     .isEqualTo("#/definitions/" + Dog.class.getName());
        assertThatJson(discriminator).node("mapping.cat")
                                     .isEqualTo("#/definitions/" + Cat.class.getName());
    }

    // Test-specific DTOs
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "species")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Dog.class, name = "dog"),
            @JsonSubTypes.Type(value = Cat.class, name = "cat")
    })
    interface Animal {
        String name();
    }

    abstract static class Mammal implements Animal {
        private final String name;

        protected Mammal(String name) {
            this.name = requireNonNull(name, "name");
        }

        @Override
        public String name() {
            return name;
        }

        public abstract String sound();
    }

    static final class Toy {
        @JsonProperty
        private final String toyName;
        @JsonProperty
        private final String color;

        @JsonCreator
        Toy(@JsonProperty("toyName") String toyName, @JsonProperty("color") String color) {
            this.toyName = requireNonNull(toyName, "toyName");
            this.color = requireNonNull(color, "color");
        }

        public String toyName() {
            return toyName;
        }

        public String color() {
            return color;
        }
    }

    static final class Dog extends Mammal {
        @JsonProperty
        private final int age;
        @JsonProperty
        private final String[] favoriteFoods;
        @JsonProperty
        private final Toy favoriteToy;

        @JsonCreator
        Dog(@JsonProperty("name") String name, @JsonProperty("age") int age,
            @JsonProperty("favoriteFoods") String[] favoriteFoods,
            @JsonProperty("favoriteToy") Toy favoriteToy) {
            super(name);
            this.age = age;
            this.favoriteFoods = requireNonNull(favoriteFoods, "favoriteFoods");
            this.favoriteToy = requireNonNull(favoriteToy, "favoriteToy");
        }

        @Override
        public String sound() {
            return "woof woof";
        }

        public int age() {
            return age;
        }

        public String[] favoriteFoods() {
            return favoriteFoods;
        }

        public Toy favoriteToy() {
            return favoriteToy;
        }
    }

    static final class Cat extends Mammal {
        @JsonProperty
        private final boolean likesTuna;
        @JsonProperty
        private final Toy scratchPost;

        @JsonCreator
        Cat(@JsonProperty("name") String name, @JsonProperty("likesTuna") boolean likesTuna,
            @JsonProperty("scratchPost") Toy scratchPost) {
            super(name);
            this.likesTuna = likesTuna;
            this.scratchPost = requireNonNull(scratchPost, "scratchPost");
        }

        @Override
        public String sound() {
            return "meow meow";
        }

        public boolean likesTuna() {
            return likesTuna;
        }

        public Toy scratchPost() {
            return scratchPost;
        }
    }
}
