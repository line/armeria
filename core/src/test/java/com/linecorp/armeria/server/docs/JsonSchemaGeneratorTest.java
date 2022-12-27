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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;

class JsonSchemaGeneratorTest {

    // Common Fixtures
    private static final String methodName = "test-method";
    private static final DescriptionInfo methodDescription = DescriptionInfo.of("test method");

    // Generate a fake ServiceSpecification that only contains the happy path to parameters
    private static StructInfo newStructInfo(String name, List<FieldInfo> parameters) {
        return new StructInfo(name, parameters);
    }

    private static EndpointInfo newGrpcEndpoint() {
        return EndpointInfo.builder("", "").defaultMimeType(MediaType.PROTOBUF).build();
    }

    private static FieldInfo newFieldInfo() {
        return FieldInfo.of("request", TypeSignature.ofStruct(methodName, new Object()));
    }

    private static MethodInfo newMethodInfo(FieldInfo... parameters) {
        return new MethodInfo(
                "test-service",
                methodName,
                0,
                TypeSignature.ofBase("void"),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(newGrpcEndpoint()),
                HttpMethod.POST,
                methodDescription
        ).withParameters(Arrays.asList(parameters));
    }

    private static ServiceSpecification generateServiceSpecification(StructInfo... structInfos) {
        return new ServiceSpecification(
                ImmutableList.of(
                        new ServiceInfo(
                                "test-service",
                                ImmutableList.of(newMethodInfo(newFieldInfo())),
                                DescriptionInfo.empty()
                        )
                ),
                ImmutableList.of(),
                Arrays.stream(structInfos).collect(Collectors.toList()),
                ImmutableList.of()
        );
    }

    @Test
    void testGenerateSimpleMethodWithoutParameters() {
        final List<FieldInfo> parameters = ImmutableList.of();
        final StructInfo structInfo = newStructInfo(methodName, parameters);

        final ServiceSpecification serviceSpecification = generateServiceSpecification(structInfo);
        final JsonNode jsonSchema = JsonSchemaGenerator.generate(serviceSpecification).get(0);

        // Base properties
        assertThat(jsonSchema.get("title").asText()).isEqualTo(methodName);
        assertThat(jsonSchema.get("description").asText()).isEqualTo(methodDescription.docString());
        assertThat(jsonSchema.get("type").asText()).isEqualTo("object");

        // Method specific properties
        assertThat(jsonSchema.get("properties").isEmpty()).isTrue();
    }

    @Test
    void testGenerateSimpleMethodWithPrimitiveParameters() {
        final List<FieldInfo> parameters = ImmutableList.of(
                FieldInfo.of("param1", TypeSignature.ofBase("int"), DescriptionInfo.of("param1 description")),
                FieldInfo.of("param2", TypeSignature.ofBase("double"),
                             DescriptionInfo.of("param2 description")),
                FieldInfo.of("param3", TypeSignature.ofBase("string"),
                             DescriptionInfo.of("param3 description")),
                FieldInfo.of("param4", TypeSignature.ofBase("boolean"),
                             DescriptionInfo.of("param4 description")));
        final StructInfo structInfo = newStructInfo(methodName, parameters);

        final ServiceSpecification serviceSpecification = generateServiceSpecification(structInfo);
        final JsonNode jsonSchema = JsonSchemaGenerator.generate(serviceSpecification).get(0);

        // Base properties
        assertThat(jsonSchema.get("title").asText()).isEqualTo(methodName);
        assertThat(jsonSchema.get("description").asText()).isEqualTo(methodDescription.docString());
        assertThat(jsonSchema.get("type").asText()).isEqualTo("object");

        // Method specific properties
        final List<JsonNode> properties = ImmutableList.copyOf(jsonSchema.get("properties").elements());
        assertThat(properties).hasSize(4);
    }

    @Test
    void testMethodWithRecursivePath() {
        final List<FieldInfo> parameters = ImmutableList.of(
                FieldInfo.of("param1", TypeSignature.ofBase("int"), DescriptionInfo.of("param1 description")),
                FieldInfo.builder("paramRecursive", TypeSignature.ofStruct("rec", new Object())).build()
        );

        final StructInfo structInfo = newStructInfo(methodName, parameters);

        final List<FieldInfo> parametersOfRec = ImmutableList.of(
                FieldInfo.of("inner-param1", TypeSignature.ofBase("int32")),
                FieldInfo.of("inner-recurse", TypeSignature.ofStruct("rec", new Object()))
        );
        final StructInfo rec = newStructInfo("rec", parametersOfRec);

        final ServiceSpecification serviceSpecification = generateServiceSpecification(structInfo, rec);
        final JsonNode jsonSchema = JsonSchemaGenerator.generate(serviceSpecification).get(0);

        assertThat(jsonSchema.get("properties").isNull()).isFalse();
        assertThat(jsonSchema.get("properties").get("paramRecursive")).isNotNull();
        assertThat(jsonSchema.get("properties").get("paramRecursive").get("properties")
                             .get("inner-param1")).isNotNull();
        assertThat(jsonSchema.get("properties").get("paramRecursive").get("properties")
                             .get("inner-recurse")).isNotNull();
        assertThat(jsonSchema.get("properties").get("paramRecursive").get("properties")
                             .get("inner-recurse").get("$ref").asText()).isEqualTo("#/properties/paramRecursive");
    }
}
