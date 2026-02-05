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
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpMethod;

class JsonSchemaGeneratorTest {

    // Common Fixtures
    private static final String methodName = "test-method";

    // Generate a fake ServiceSpecification that only contains the happy path to parameters
    private static StructInfo newStructInfo(String name, List<FieldInfo> parameters) {
        return new StructInfo(name, parameters);
    }

    private static ParamInfo newParamInfo() {
        return ParamInfo.of("request", TypeSignature.ofStruct(methodName, new Object()));
    }

    private static MethodInfo newMethodInfo(ParamInfo... parameters) {
        return new MethodInfo(
                "test-service",
                methodName,
                TypeSignature.ofBase("void"),
                Arrays.asList(parameters),
                true,
                ImmutableSet.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                HttpMethod.POST
        );
    }

    private static ServiceSpecification generateServiceSpecification(StructInfo... structInfos) {
        return generateServiceSpecification(ImmutableMap.of(), structInfos);
    }

    private static ServiceSpecification generateServiceSpecification(
            ImmutableMap<String, DescriptionInfo> docStrings, StructInfo... structInfos) {
        return new ServiceSpecification(
                ImmutableList.of(
                        new ServiceInfo(
                                "test-service",
                                ImmutableList.of(newMethodInfo(newParamInfo()))
                        )
                ),
                ImmutableList.of(),
                Arrays.stream(structInfos).collect(Collectors.toList()),
                ImmutableList.of(),
                ImmutableList.of(),
                docStrings,
                null
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
        // Note: Method descriptions are now in ServiceSpecification.docStrings(), not in MethodInfo
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
        // Note: Method descriptions are now in ServiceSpecification.docStrings(), not in MethodInfo
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

    @Test
    void testDocStringsArePopulatedFromServiceSpecification() {
        // Test that method descriptions are retrieved from docStrings,
        // and field descriptions come from FieldInfo.descriptionInfo() when useParameterAsRoot() is true
        final List<FieldInfo> parameters = ImmutableList.of(
                FieldInfo.of("param1", TypeSignature.ofBase("int"),
                             DescriptionInfo.of("Field param1 from StructInfo")),
                FieldInfo.of("param2", TypeSignature.ofBase("string"),
                             DescriptionInfo.of("Field param2 from StructInfo"))
        );
        final StructInfo structInfo = newStructInfo(methodName, parameters);

        // docStrings contains method description (field descriptions are in FieldInfo when using StructInfo)
        final ImmutableMap<String, DescriptionInfo> docStrings = ImmutableMap.of(
                "test-service/" + methodName, DescriptionInfo.of("Method description from docStrings")
        );

        final ServiceSpecification serviceSpecification = generateServiceSpecification(docStrings, structInfo);
        final JsonNode jsonSchema = JsonSchemaGenerator.generate(serviceSpecification).get(0);

        // Verify method description is populated from docStrings
        assertThatJson(jsonSchema).node("description").isEqualTo("Method description from docStrings");

        // Verify field descriptions come from FieldInfo.descriptionInfo()
        // because useParameterAsRoot() is true, so fields come from StructInfo
        assertThatJson(jsonSchema).node("properties.param1.description")
                .isEqualTo("Field param1 from StructInfo");
        assertThatJson(jsonSchema).node("properties.param2.description")
                .isEqualTo("Field param2 from StructInfo");
    }

    @Test
    void testDocStringsForParametersWhenNotUsingParameterAsRoot() {
        // Test that parameter descriptions are retrieved from docStrings when useParameterAsRoot is false
        final ParamInfo param1 = ParamInfo.builder("queryParam", TypeSignature.ofBase("string"))
                                          .location(FieldLocation.QUERY)
                                          .requirement(FieldRequirement.REQUIRED)
                                          .build();
        final ParamInfo param2 = ParamInfo.builder("headerParam", TypeSignature.ofBase("int"))
                                          .location(FieldLocation.HEADER)
                                          .requirement(FieldRequirement.OPTIONAL)
                                          .build();
        final ParamInfo param3 = ParamInfo.builder("bodyParam", TypeSignature.ofBase("boolean"))
                                          .location(FieldLocation.BODY)
                                          .requirement(FieldRequirement.REQUIRED)
                                          .build();

        // Create a MethodInfo with useParameterAsRoot = false
        final MethodInfo methodInfo = new MethodInfo(
                "test-service",
                methodName,
                TypeSignature.ofBase("void"),
                ImmutableList.of(param1, param2, param3),
                false,  // useParameterAsRoot = false
                ImmutableSet.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                HttpMethod.POST
        );

        // docStrings contains method and parameter descriptions
        final String methodKey = "test-service/" + methodName;
        final ImmutableMap<String, DescriptionInfo> docStrings = ImmutableMap.of(
                methodKey, DescriptionInfo.of("Test method description"),
                methodKey + ":param/queryParam", DescriptionInfo.of("Query parameter desc"),
                methodKey + ":param/headerParam", DescriptionInfo.of("Header parameter desc"),
                methodKey + ":param/bodyParam", DescriptionInfo.of("Body parameter desc")
        );

        final ServiceSpecification serviceSpecification = new ServiceSpecification(
                ImmutableList.of(new ServiceInfo("test-service", ImmutableList.of(methodInfo))),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                docStrings,
                null
        );

        final JsonNode jsonSchema = JsonSchemaGenerator.generate(serviceSpecification).get(0);

        // Verify method description
        assertThatJson(jsonSchema).node("description").isEqualTo("Test method description");

        // Only BODY and UNSPECIFIED location parameters are included in the schema
        // QUERY and HEADER parameters are not included
        assertThatJson(jsonSchema).node("properties.queryParam").isAbsent();
        assertThatJson(jsonSchema).node("properties.headerParam").isAbsent();

        // Body parameter should have its description from docStrings
        assertThatJson(jsonSchema).node("properties.bodyParam.type").isEqualTo("boolean");
        assertThatJson(jsonSchema).node("properties.bodyParam.description").isEqualTo("Body parameter desc");
    }
}
