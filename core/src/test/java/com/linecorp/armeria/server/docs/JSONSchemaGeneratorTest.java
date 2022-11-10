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

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;

class JSONSchemaGeneratorTest {

    private static StructInfo newStructInfo(String name, List<FieldInfo> parameters) {
        return new StructInfo(name, parameters);
    }

    @Test
    void testGenerateSimpleMethodWithoutParameters() {
        final String methodName = "test-method";
        final List<FieldInfo> parameters = ImmutableList.of();
        final DescriptionInfo description = DescriptionInfo.of("test method");
        final StructInfo methodInfo = newStructInfo(methodName, parameters).withDescriptionInfo(description);

        final ObjectNode jsonSchema = JSONSchemaGenerator.generate(methodInfo);

        // Base properties
        assertThat(jsonSchema.get("$schema").asText()).isNotEmpty();
        assertThat(jsonSchema.get("title").asText()).isEqualTo(methodName);
        assertThat(jsonSchema.get("description").asText()).isEqualTo(description.docString());
        assertThat(jsonSchema.get("type").asText()).isEqualTo("object");

        // Method specific properties
        assertThat(jsonSchema.get("properties").isEmpty()).isTrue();
    }

    @Test
    void testGenerateSimpleMethodWithPrimitiveParameters() {
        final String methodName = "test-method";
        final List<FieldInfo> parameters = ImmutableList.of(
                FieldInfo.of("param1", TypeSignature.ofBase("int"), DescriptionInfo.of("param1 description")),
                FieldInfo.of("param2", TypeSignature.ofBase("double"),
                             DescriptionInfo.of("param2 description")),
                FieldInfo.of("param3", TypeSignature.ofBase("string"),
                             DescriptionInfo.of("param3 description")),
                FieldInfo.of("param4", TypeSignature.ofBase("boolean"),
                             DescriptionInfo.of("param4 description")));
        final DescriptionInfo description = DescriptionInfo.of("test method");
        final StructInfo methodInfo = newStructInfo(methodName, parameters).withDescriptionInfo(description);

        final ObjectNode jsonSchema = JSONSchemaGenerator.generate(methodInfo);

        // Base properties
        assertThat(jsonSchema.get("$schema").asText()).isNotEmpty();
        assertThat(jsonSchema.get("title").asText()).isEqualTo(methodName);
        assertThat(jsonSchema.get("description").asText()).isEqualTo(description.docString());
        assertThat(jsonSchema.get("type").asText()).isEqualTo("object");

        // Method specific properties
        List<JsonNode> properties = ImmutableList.copyOf(jsonSchema.get("properties").elements());
        assertThat(properties).hasSize(4);
    }

    @Test
    void testMethodWithRecursivePath() {
        final String methodName = "test-method";
        final List<FieldInfo> parameters = ImmutableList.of(
                FieldInfo.of("param1", TypeSignature.ofBase("int"), DescriptionInfo.of("param1 description")),
                FieldInfo.builder("paramRecursive", TypeSignature.ofNamed("rec", new Object()),
                                  FieldInfo.of("inner-param1", TypeSignature.ofBase("int32")),
                                  FieldInfo.of("inner-recurse", TypeSignature.ofNamed("rec", new Object()))
                ).build()
        );
        final StructInfo methodInfo = newStructInfo(methodName, parameters);

        final ObjectNode jsonSchema = JSONSchemaGenerator.generate(methodInfo);

        assertThat(jsonSchema.get("properties").isNull()).isFalse();
        assertThat(jsonSchema.get("properties").get("paramRecursive")).isNotNull();
        assertThat(jsonSchema.get("properties").get("paramRecursive").get("properties")
                             .get("inner-param1")).isNotNull();
        assertThat(jsonSchema.get("properties").get("paramRecursive").get("properties")
                             .get("inner-recurse")).isNotNull();
        assertThat(jsonSchema.get("properties").get("paramRecursive").get("properties")
                             .get("inner-recurse").get("$ref").asText()).isEqualTo("#/paramRecursive");
    }

}
