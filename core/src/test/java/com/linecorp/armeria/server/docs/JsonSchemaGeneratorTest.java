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
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpMethod;

class JsonSchemaGeneratorTest {

    private enum Color {
        RED, GREEN, BLUE
    }

    // ---- Test helpers -------------------------------------------------------

    private static String modelNodePath(String modelName) {
        return "$defs.models." + modelName.replace(".", "\\.");
    }

    private static String methodNodePath(String methodName) {
        return "$defs.methods." + methodName;
    }

    private static String modelRefValue(String modelName) {
        return "#/$defs/models/" + modelName;
    }

    private static ServiceSpecification specWithSingleRestMethod(List<ParamInfo> params,
                                                                 List<StructInfo> structs,
                                                                 List<EnumInfo> enums) {
        final MethodInfo m = new MethodInfo(
                "test-service", "test-method", 0,
                TypeSignature.ofBase("void"),
                ImmutableList.copyOf(params),
                ImmutableList.of(),                       // exampleHeaders
                ImmutableList.of(),                       // endpoints
                HttpMethod.POST);

        return new ServiceSpecification(
                ImmutableList.of(new ServiceInfo("test-service", ImmutableList.of(m))),
                ImmutableList.copyOf(enums),
                ImmutableList.copyOf(structs),
                ImmutableList.of());
    }

    private static ServiceSpecification specWithSingleGrpcMethod(
            String requestStructName,
            ImmutableList<FieldInfo> requestStructFields) {

        final ParamInfo requestParam = ParamInfo.builder("request",
                                                         TypeSignature.ofStruct(
                                                                 requestStructName, new Object()))
                                                .requirement(FieldRequirement.REQUIRED)
                                                .build();
        final MethodInfo grpcMethod = new MethodInfo(
                "svc.grpc", "test-method", TypeSignature.ofBase("void"),
                ImmutableList.of(requestParam),
                /* useParameterAsRoot */ true,
                ImmutableSet.of(), ImmutableList.of(),
                ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(), ImmutableList.of(),
                HttpMethod.POST);

        final List<StructInfo> allStructs = new ArrayList<>();
        allStructs.add(new StructInfo(requestStructName, requestStructFields));

        return new ServiceSpecification(
                ImmutableList.of(new ServiceInfo("svc.grpc", ImmutableList.of(grpcMethod))),
                ImmutableList.of(), allStructs, ImmutableList.of());
    }

    // ---- Tests --------------------------------------------------------------

    @Test
    void optionalIsUnwrapped_andNotRequired() {
        final FieldInfo opt = FieldInfo.builder("maybe",
                                                TypeSignature.ofOptional(TypeSignature.ofBase("int")))
                                       .requirement(FieldRequirement.OPTIONAL)
                                       .build();

        final StructInfo s = new StructInfo("S", ImmutableList.of(opt));
        final ParamInfo request = ParamInfo.of("request", TypeSignature.ofStruct("S", new Object()));

        final ServiceSpecification spec = specWithSingleRestMethod(
                ImmutableList.of(request), ImmutableList.of(s), ImmutableList.of());

        final JsonNode schema = JsonSchemaGenerator.generate(spec);
        final JsonNode sModel = schema.path("$defs").path("models").path("S");

        assertThatJson(schema).node(methodNodePath("test-method") + ".properties.request.$ref")
                              .isEqualTo(modelRefValue("S"));
        assertThatJson(sModel).node("properties.maybe.type").isEqualTo("integer");
        assertThat(sModel.get("required")).isNull();
    }

    @Test
    void arrayAndMapOfStructs_areUnpackedCorrectly() {
        final StructInfo foo = new StructInfo("Foo", ImmutableList.of(
                FieldInfo.of("x", TypeSignature.ofBase("int"))));
        final FieldInfo listFoo = FieldInfo.of("list",
                                               TypeSignature.ofList(
                                                       TypeSignature.ofStruct("Foo", new Object())));
        final FieldInfo mapFoo = FieldInfo.of("map",
                                              TypeSignature.ofMap(TypeSignature.ofBase("string"),
                                                                  TypeSignature.ofStruct("Foo", new Object())));
        final StructInfo holder = new StructInfo("Holder", ImmutableList.of(listFoo, mapFoo));

        final ServiceSpecification spec = specWithSingleRestMethod(
                ImmutableList.of(ParamInfo.of("request", TypeSignature.ofStruct("Holder", new Object()))),
                ImmutableList.of(holder, foo), ImmutableList.of());

        final JsonNode schema = JsonSchemaGenerator.generate(spec);
        final JsonNode holderModel = schema.path("$defs").path("models").path("Holder");

        assertThatJson(holderModel).node("properties.list.items.$ref").isEqualTo(modelRefValue("Foo"));
        assertThatJson(holderModel).node("properties.map.additionalProperties.$ref").isEqualTo(
                modelRefValue("Foo"));
    }

    @Test
    void enumField_isRefAndDefinitionsContainEnumArray() {
        final String enumName = TypeSignature.ofEnum(Color.class).name();
        final EnumInfo colorInfo = new EnumInfo(
                enumName,
                Arrays.asList(new EnumValueInfo("RED", null),
                              new EnumValueInfo("GREEN", null),
                              new EnumValueInfo("BLUE", null)),
                DescriptionInfo.empty());

        final FieldInfo enumField = FieldInfo.of("color", TypeSignature.ofEnum(Color.class));
        final StructInfo dto = new StructInfo("Dto", ImmutableList.of(enumField));

        final ServiceSpecification spec = specWithSingleRestMethod(
                ImmutableList.of(ParamInfo.of("request", TypeSignature.ofStruct("Dto", new Object()))),
                ImmutableList.of(dto),
                ImmutableList.of(colorInfo));

        final JsonNode schema = JsonSchemaGenerator.generate(spec);
        assertThatJson(schema).node(modelNodePath("Dto") + ".properties.color.$ref")
                              .isEqualTo(modelRefValue(enumName));

        final JsonNode colorModel = schema.path("$defs").path("models").path(enumName);
        assertThat(colorModel.get("type").asText()).isEqualTo("string");
        assertThat(colorModel.get("enum")).isNotNull();
        assertThat(colorModel.get("enum").size()).isEqualTo(3);
    }

    @Test
    void grpc_methodSchemaIsRef_andModelContainsFields() {
        final ImmutableList<FieldInfo> reqFields = ImmutableList.of(
                FieldInfo.of("a", TypeSignature.ofBase("int")),
                FieldInfo.of("b", TypeSignature.ofBase("string")));
        final ServiceSpecification spec = specWithSingleGrpcMethod("AddRequest", reqFields);

        final JsonNode schema = JsonSchemaGenerator.generate(spec);

        // Verify that the method schema now contains a $ref to the model
        assertThatJson(schema).node(methodNodePath("test-method") + ".properties.request.$ref")
                              .isEqualTo(modelRefValue("AddRequest"));

        // Verify that the model itself is defined in models and contains the fields
        final JsonNode addRequestModel = schema.path("$defs").path("models").path("AddRequest");
        assertThatJson(addRequestModel).node("properties.a.type").isEqualTo("integer");
        assertThatJson(addRequestModel).node("properties.b.type").isEqualTo("string");
    }

    @Test
    void rest_filtersOutPathQueryHeader_keepsOnlyBodyAndUnspecified() {
        final ParamInfo path = ParamInfo.builder("id", TypeSignature.ofBase("int"))
                                        .location(FieldLocation.PATH).build();
        final ParamInfo query = ParamInfo.builder("q", TypeSignature.ofBase("string"))
                                         .location(FieldLocation.QUERY).build();
        final ParamInfo header = ParamInfo.builder("h", TypeSignature.ofBase("string"))
                                          .location(FieldLocation.HEADER).build();
        final ParamInfo body = ParamInfo.builder("payload",
                                                 TypeSignature.ofStruct("Payload", new Object()))
                                        .location(FieldLocation.BODY).build();

        final StructInfo payload = new StructInfo("Payload",
                                                  ImmutableList.of(FieldInfo.of("x",
                                                                                TypeSignature.ofBase("int"))));

        final ServiceSpecification spec = specWithSingleRestMethod(
                ImmutableList.of(path, query, header, body),
                ImmutableList.of(payload), ImmutableList.of());

        final JsonNode schema = JsonSchemaGenerator.generate(spec);
        final JsonNode methodSchema = schema.path("$defs").path("methods").path("test-method");

        assertThat(methodSchema.get("properties").size()).isEqualTo(1);
        assertThat(methodSchema.get("properties").has("payload")).isTrue();
        assertThatJson(methodSchema).node("properties.payload.$ref").isEqualTo(modelRefValue("Payload"));
    }

    @Test
    void requiredArray_createdOnlyForRequiredFields() {
        final FieldInfo r = FieldInfo.builder("r", TypeSignature.ofBase("int"))
                                     .requirement(FieldRequirement.REQUIRED).build();
        final FieldInfo o = FieldInfo.builder("o", TypeSignature.ofBase("string"))
                                     .requirement(FieldRequirement.OPTIONAL).build();

        final StructInfo s = new StructInfo("S", ImmutableList.of(r, o));
        final ServiceSpecification spec = specWithSingleRestMethod(
                ImmutableList.of(ParamInfo.of("request", TypeSignature.ofStruct("S", new Object()))),
                ImmutableList.of(s), ImmutableList.of());

        final JsonNode schema = JsonSchemaGenerator.generate(spec);
        final JsonNode sModel = schema.path("$defs").path("models").path("S");

        assertThat(sModel.get("required")).isNotNull();
        assertThatJson(sModel).node("required").isArray().ofLength(1);
        assertThatJson(sModel).node("required[0]").isEqualTo("r");
    }

    @Test
    void containerType_isUnwrappedToInnerType() {
        final FieldInfo box = FieldInfo.of("box",
                                           TypeSignature.ofContainer("Box", ImmutableList.of(
                                                   TypeSignature.ofBase("int"))));
        final StructInfo s = new StructInfo("HasBox", ImmutableList.of(box));

        final ServiceSpecification spec = specWithSingleRestMethod(
                ImmutableList.of(ParamInfo.of("request", TypeSignature.ofStruct("HasBox", new Object()))),
                ImmutableList.of(s), ImmutableList.of());

        final JsonNode schema = JsonSchemaGenerator.generate(spec);
        assertThatJson(schema).node(modelNodePath("HasBox") + ".properties.box.type").isEqualTo("integer");
    }

    @Test
    void testDocStringsArePopulatedFromServiceSpecification() {
        // Test that method descriptions are retrieved from docStrings,
        // and field descriptions come from FieldInfo.descriptionInfo() when useParameterAsRoot() is true
        final String methodName = "test-method";

        final List<FieldInfo> parameters = ImmutableList.of(
                FieldInfo.of("param1", TypeSignature.ofBase("int"),
                             DescriptionInfo.of("Field param1 from StructInfo")),
                FieldInfo.of("param2", TypeSignature.ofBase("string"),
                             DescriptionInfo.of("Field param2 from StructInfo"))
        );
        final StructInfo structInfo = new StructInfo(methodName, parameters);

        // docStrings contains method description (field descriptions are in FieldInfo when using StructInfo)
        final ImmutableMap<String, DescriptionInfo> docStrings = ImmutableMap.of(
                "test-service/" + methodName, DescriptionInfo.of("Method description from docStrings")
        );

        final ServiceSpecification serviceSpecification =
                new ServiceSpecification(
                        ImmutableList.of(
                                new ServiceInfo(
                                        "test-service",
                                        ImmutableList.of(newMethodInfo(methodName, ParamInfo.of(
                                                "request", TypeSignature.ofStruct(methodName, new Object()))))
                                )
                        ),
                        ImmutableList.of(),
                        ImmutableList.of(structInfo),
                        ImmutableList.of(),
                        ImmutableList.of(),
                        docStrings,
                        null);

        final ObjectNode generated = JsonSchemaGenerator.generate(serviceSpecification);

        // Verify method description is populated from docStrings
        assertThatJson(generated).node("$defs.methods." + methodName + ".description")
                                 .isEqualTo("Method description from docStrings");

        // Verify field descriptions come from FieldInfo.descriptionInfo()
        assertThatJson(generated).node("$defs.models." + methodName + ".properties.param1.description")
                                 .isEqualTo("Field param1 from StructInfo");
        assertThatJson(generated).node("$defs.models." + methodName + ".properties.param2.description")
                                 .isEqualTo("Field param2 from StructInfo");
    }

    @Test
    void testDocStringsForParametersWhenNotUsingParameterAsRoot() {
        final String methodName = "test-method";
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

        final ObjectNode generated = JsonSchemaGenerator.generate(serviceSpecification);

        assertThatJson(generated).node("$defs.methods." + methodName + ".description")
                                 .isEqualTo("Test method description");

        // Only BODY and UNSPECIFIED location parameters are included in the schema
        // QUERY and HEADER parameters are not included
        assertThatJson(generated).node("$defs.methods." + methodName + ".properties.queryParam").isAbsent();
        assertThatJson(generated).node("$defs.methods." + methodName + ".properties.headerParam").isAbsent();

        System.err.println(generated.toPrettyString());

        // Body parameter should have its description from docStrings
        assertThatJson(generated).node("$defs.methods." + methodName + ".properties.bodyParam.type")
                                 .isEqualTo("boolean");
        assertThatJson(generated).node("$defs.methods." + methodName + ".properties.bodyParam.description")
                                 .isEqualTo("Body parameter desc");
    }

    private static MethodInfo newMethodInfo(String methodName, ParamInfo... parameters) {
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
}
