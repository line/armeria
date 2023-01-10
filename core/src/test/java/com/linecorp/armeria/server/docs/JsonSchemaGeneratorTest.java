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

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.hamcrest.CustomTypeSafeMatcher;
import org.junit.jupiter.api.Test;

import net.javacrumbs.jsonunit.core.internal.Node.JsonMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpMethod;

class JsonSchemaGeneratorTest {

    // Common Fixtures
    private static final String methodName = "test-method";
    private static final DescriptionInfo methodDescription = DescriptionInfo.of("test method");

    // Generate a fake ServiceSpecification that only contains the happy path to parameters
    private static StructInfo newStructInfo(String name, List<FieldInfo> parameters) {
        return new StructInfo(name, parameters);
    }

    private static FieldInfo newFieldInfo() {
        return FieldInfo.of("request", TypeSignature.ofStruct(methodName, new Object()));
    }

    private static MethodInfo newMethodInfo(FieldInfo... parameters) {
        return new MethodInfo(
                "test-service",
                methodName,
                TypeSignature.ofBase("void"),
                Arrays.asList(parameters),
                true,
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                HttpMethod.POST,
                methodDescription
        );
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
        assertThatJson(jsonSchema).node("title").isEqualTo(methodName);
        assertThatJson(jsonSchema).node("description").isEqualTo(methodDescription.docString());
        assertThatJson(jsonSchema).node("type").isEqualTo("object");

        // Method specific properties
        assertThatJson(jsonSchema).node("properties").matches(
                new CustomTypeSafeMatcher<JsonMap>("has no key") {
                    @Override
                    protected boolean matchesSafely(JsonMap item) {
                        return item.keySet().size() == 0;
                    }
                });
        assertThatJson(jsonSchema).node("additionalProperties").isEqualTo(false);
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
        assertThatJson(jsonSchema).node("title").isEqualTo(methodName);
        assertThatJson(jsonSchema).node("description").isEqualTo(methodDescription.docString());
        assertThatJson(jsonSchema).node("type").isEqualTo("object");

        // Method specific properties
        assertThatJson(jsonSchema).node("properties").matches(
                new CustomTypeSafeMatcher<JsonMap>("has 4 keys") {
                    @Override
                    protected boolean matchesSafely(JsonMap item) {
                        return item.keySet().size() == 4;
                    }
                });
        assertThatJson(jsonSchema).node("properties.param1.type").isEqualTo("integer");
        assertThatJson(jsonSchema).node("properties.param2.type").isEqualTo("number");
        assertThatJson(jsonSchema).node("properties.param3.type").isEqualTo("string");
        assertThatJson(jsonSchema).node("properties.param4.type").isEqualTo("boolean");
    }

    @Test
    void testMethodWithRecursivePath() {
        final Object commonTypeObjectForRecursion = new Object();
        final List<FieldInfo> parameters = ImmutableList.of(
                FieldInfo.of("param1", TypeSignature.ofBase("int"), DescriptionInfo.of("param1 description")),
                FieldInfo.builder("paramRecursive", TypeSignature.ofStruct("rec", commonTypeObjectForRecursion))
                         .build()
        );

        final StructInfo structInfo = newStructInfo(methodName, parameters);

        final List<FieldInfo> parametersOfRec = ImmutableList.of(
                FieldInfo.of("inner-param1", TypeSignature.ofBase("int32")),
                FieldInfo.of("inner-recurse", TypeSignature.ofStruct("rec", commonTypeObjectForRecursion))
        );
        final StructInfo rec = newStructInfo("rec", parametersOfRec);

        final ServiceSpecification serviceSpecification = generateServiceSpecification(structInfo, rec);
        final JsonNode jsonSchema = JsonSchemaGenerator.generate(serviceSpecification).get(0);

        assertThatJson(jsonSchema).node("properties.paramRecursive.properties.inner-param1").isPresent();
        assertThatJson(jsonSchema).node("properties.paramRecursive.properties.inner-recurse.$ref").isEqualTo(
                "#/properties/paramRecursive");
    }
}
