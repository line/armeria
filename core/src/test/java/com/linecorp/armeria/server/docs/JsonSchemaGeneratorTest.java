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
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpMethod;

class JsonSchemaGeneratorTest {

    private static final String methodName = "test-method";

    private enum Color {
        RED, GREEN, BLUE
    }

    // ---- Test helpers -------------------------------------------------------

    private static ServiceSpecification newServiceSpecificationWithRequestStruct(StructInfo... structInfos) {
        final MethodInfo methodInfo = new MethodInfo(
                "test-service", methodName, 0,
                TypeSignature.ofBase("void"),
                ImmutableList.of(FieldInfo.of("request",
                                              TypeSignature.ofStruct(methodName, new Object()))),
                ImmutableList.of(),                      // exampleHeaders
                ImmutableList.of(),                      // endpoints
                HttpMethod.POST, DescriptionInfo.empty());

        return new ServiceSpecification(
                ImmutableList.of(new ServiceInfo("test-service", ImmutableList.of(methodInfo))),
                ImmutableList.of(),
                Arrays.stream(structInfos).collect(Collectors.toList()),
                ImmutableList.of());
    }

    private static ServiceSpecification specWithSingleRestMethod(String svc, String method,
                                                                 List<FieldInfo> params,
                                                                 List<StructInfo> structs,
                                                                 List<EnumInfo> enums) {
        final MethodInfo m = new MethodInfo(
                svc, method, 0,
                TypeSignature.ofBase("void"),
                ImmutableList.copyOf(params),
                ImmutableList.of(),                       // exampleHeaders
                ImmutableList.of(),                       // endpoints
                HttpMethod.POST, DescriptionInfo.empty());

        return new ServiceSpecification(
                ImmutableList.of(new ServiceInfo(svc, ImmutableList.of(m))),
                ImmutableList.copyOf(enums),
                ImmutableList.copyOf(structs),
                ImmutableList.of());
    }

    private static ServiceSpecification specWithSingleGrpcMethod(
            String svc, String method, String requestStructName,
            ImmutableList<FieldInfo> requestStructFields,
            boolean includeRequestStruct,
            ImmutableList<StructInfo> extraStructs) {

        final FieldInfo requestParam = FieldInfo.builder("request",
                                                         TypeSignature.ofStruct(
                                                                 requestStructName, new Object()))
                                                .requirement(FieldRequirement.REQUIRED)
                                                .build();
        final MethodInfo grpcMethod = new MethodInfo(
                svc, method, TypeSignature.ofBase("void"),
                ImmutableList.of(requestParam),
                /* useParameterAsRoot */ true,
                ImmutableList.of(), ImmutableSet.of(),
                ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(), ImmutableList.of(),
                HttpMethod.POST, DescriptionInfo.empty());

        final List<StructInfo> allStructs = new ArrayList<>();
        if (includeRequestStruct) {
            allStructs.add(new StructInfo(requestStructName, requestStructFields));
        }
        allStructs.addAll(extraStructs);

        return new ServiceSpecification(
                ImmutableList.of(new ServiceInfo(svc, ImmutableList.of(grpcMethod))),
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

        final ServiceSpecification spec = specWithSingleRestMethod(
                "svc", "m",
                ImmutableList.of(FieldInfo.of("request", TypeSignature.ofStruct("S", new Object()))),
                ImmutableList.of(s), ImmutableList.of());

        final JsonNode schema = JsonSchemaGenerator.generate(spec).get(0);
        final JsonNode def = schema.get("definitions").get("S");

        assertThatJson(schema).node("properties.request.$ref").isEqualTo("#/definitions/S");
        assertThatJson(def).node("properties.maybe.type").isEqualTo("integer");
        assertThat(def.get("required")).isNull();
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
                "svc", "m",
                ImmutableList.of(FieldInfo.of("request", TypeSignature.ofStruct("Holder", new Object()))),
                ImmutableList.of(holder, foo), ImmutableList.of());

        final JsonNode schema = JsonSchemaGenerator.generate(spec).get(0);
        final JsonNode def = schema.get("definitions").get("Holder");

        assertThatJson(def).node("properties.list.items.$ref").isEqualTo("#/definitions/Foo");
        assertThatJson(def).node("properties.map.additionalProperties.$ref").isEqualTo("#/definitions/Foo");
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
                "svc", "m",
                ImmutableList.of(FieldInfo.of("request", TypeSignature.ofStruct("Dto", new Object()))),
                ImmutableList.of(dto),
                ImmutableList.of(colorInfo));

        final JsonNode schema = JsonSchemaGenerator.generate(spec).get(0);
        assertThatJson(schema).node("properties.request.$ref").isEqualTo("#/definitions/Dto");
        assertThatJson(schema).node("definitions.Dto.properties.color.$ref")
                              .isEqualTo("#/definitions/" + enumName);
        final JsonNode defs = schema.get("definitions");
        assertThat(defs).isNotNull();
        assertThat(defs.has(enumName)).isTrue();

        final JsonNode colorDef = defs.get(enumName);
        assertThat(colorDef.get("type").asText()).isEqualTo("string");
        assertThat(colorDef.get("enum")).isNotNull();
        assertThat(colorDef.get("enum").size()).isEqualTo(3);
    }

    @Test
    void grpc_useParameterAsRoot_expandsRootStructFields() {
        final ImmutableList<FieldInfo> reqFields = ImmutableList.of(
                FieldInfo.of("a", TypeSignature.ofBase("int")),
                FieldInfo.of("b", TypeSignature.ofBase("string")));
        final ServiceSpecification spec = specWithSingleGrpcMethod(
                "svc.grpc", "Add", "AddRequest", reqFields, true, ImmutableList.of());

        final JsonNode schema = JsonSchemaGenerator.generate(spec).get(0);
        assertThatJson(schema).node("properties.a.type").isEqualTo("integer");
        assertThatJson(schema).node("properties.b.type").isEqualTo("string");
    }

    @Test
    void grpc_unknownStruct_fallsBackToAdditionalPropertiesTrue() {
        final ServiceSpecification spec = specWithSingleGrpcMethod(
                "svc.grpc", "Unknown", "UnknownReq",
                ImmutableList.of(FieldInfo.of("x", TypeSignature.ofBase("int"))),
                /* includeRequestStruct */ false,
                /* extraStructs */ ImmutableList.of());

        final JsonNode schema = JsonSchemaGenerator.generate(spec).get(0);
        assertThatJson(schema).node("additionalProperties").isEqualTo(true);
    }

    @Test
    void rest_filtersOutPathQueryHeader_keepsOnlyBodyAndUnspecified() {
        final FieldInfo path = FieldInfo.builder("id", TypeSignature.ofBase("int"))
                                        .location(FieldLocation.PATH)
                                        .requirement(FieldRequirement.REQUIRED).build();
        final FieldInfo query = FieldInfo.builder("q", TypeSignature.ofBase("string"))
                                         .location(FieldLocation.QUERY)
                                         .requirement(FieldRequirement.OPTIONAL).build();
        final FieldInfo header = FieldInfo.builder("h", TypeSignature.ofBase("string"))
                                          .location(FieldLocation.HEADER)
                                          .requirement(FieldRequirement.OPTIONAL).build();
        final FieldInfo body = FieldInfo.builder("payload",
                                                 TypeSignature.ofStruct("Payload", new Object()))
                                        .location(FieldLocation.BODY)
                                        .requirement(FieldRequirement.REQUIRED).build();

        final StructInfo payload = new StructInfo("Payload",
                                                  ImmutableList.of(FieldInfo.of("x",
                                                                                TypeSignature.ofBase("int"))));

        final ServiceSpecification spec = specWithSingleRestMethod(
                "svc", "m",
                ImmutableList.of(path, query, header, body),
                ImmutableList.of(payload), ImmutableList.of());

        final JsonNode schema = JsonSchemaGenerator.generate(spec).get(0);
        assertThat(schema.get("properties").size()).isEqualTo(1);
        assertThat(schema.get("properties").has("payload")).isTrue();
        assertThatJson(schema).node("properties.payload.$ref").isEqualTo("#/definitions/Payload");
    }

    @Test
    void requiredArray_createdOnlyForRequiredFields() {
        final FieldInfo r = FieldInfo.builder("r", TypeSignature.ofBase("int"))
                                     .requirement(FieldRequirement.REQUIRED).build();
        final FieldInfo o = FieldInfo.builder("o", TypeSignature.ofBase("string"))
                                     .requirement(FieldRequirement.OPTIONAL).build();

        final StructInfo s = new StructInfo("S", ImmutableList.of(r, o));
        final ServiceSpecification spec = specWithSingleRestMethod(
                "svc", "m",
                ImmutableList.of(FieldInfo.of("request", TypeSignature.ofStruct("S", new Object()))),
                ImmutableList.of(s), ImmutableList.of());

        final JsonNode schema = JsonSchemaGenerator.generate(spec).get(0);
        final JsonNode def = schema.get("definitions").get("S");
        assertThat(def.get("required")).isNotNull();
        assertThatJson(def).node("required").isArray().ofLength(1);
        assertThatJson(def).node("required[0]").isEqualTo("r");
    }

    @Test
    void containerType_isUnwrappedToInnerType() {
        final FieldInfo box = FieldInfo.of("box",
                                           TypeSignature.ofContainer("Box", ImmutableList.of(
                                                   TypeSignature.ofBase("int"))));
        final StructInfo s = new StructInfo("HasBox", ImmutableList.of(box));

        final ServiceSpecification spec = specWithSingleRestMethod(
                "svc", "m",
                ImmutableList.of(FieldInfo.of("request", TypeSignature.ofStruct("HasBox", new Object()))),
                ImmutableList.of(s), ImmutableList.of());

        final JsonNode schema = JsonSchemaGenerator.generate(spec).get(0);
        assertThatJson(schema).node("definitions.HasBox.properties.box.type").isEqualTo("integer");
    }
}
