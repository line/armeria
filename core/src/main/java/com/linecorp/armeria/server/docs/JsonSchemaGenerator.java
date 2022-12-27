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
package com.linecorp.armeria.server.docs;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.JacksonUtil;

/**
 * Generates a JSON Schema from the given service specification.
 */
final class JsonSchemaGenerator {

    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    private static final List<FieldLocation> VALID_FIELD_LOCATIONS = ImmutableList.of(
            FieldLocation.BODY,
            FieldLocation.UNSPECIFIED);

    private static final List<String> MEMORIZED_JSON_TYPES = ImmutableList.of("array", "object");

    /**
     * Generate an array of json schema specifications for each method inside the service.
     * @param serviceSpecification the service specification to generate the json schema from.
     * @return ArrayNode that contains service specifications
     */
    public static ArrayNode generate(ServiceSpecification serviceSpecification) {
        // TODO: Test for Thrift, GraphQL, and annotated services
        final JsonSchemaGenerator generator = new JsonSchemaGenerator(serviceSpecification);
        return generator.generate();
    }

    private final Set<EnumInfo> enumInfos;
    private final Set<StructInfo> structInfos;
    private final Set<ServiceInfo> serviceInfos;
    private final Map<String, StructInfo> typeNameToStructMapping;
    private final Map<String, EnumInfo> typeNameToEnumMapping;

    private JsonSchemaGenerator(ServiceSpecification serviceSpecification) {
        this.enumInfos = serviceSpecification.enums();
        this.structInfos = serviceSpecification.structs();
        this.serviceInfos = serviceSpecification.services();
        this.typeNameToStructMapping = structInfos.stream().collect(
                Collectors.toMap(StructInfo::name, Function.identity()));
        this.typeNameToEnumMapping = enumInfos.stream().collect(
                Collectors.toMap(EnumInfo::name, Function.identity()));
    }

    private ArrayNode generate() {
        final ArrayNode definitions = mapper.createArrayNode();

        final Set<ObjectNode> methodDefinitions = serviceInfos.stream().flatMap(
                serviceInfo -> serviceInfo.methods().stream()
                                          .map(methodInfo -> generate(serviceInfo, methodInfo))).collect(
                Collectors.toSet());

        return definitions.addAll(methodDefinitions);
    }

    /**
     * Generate the JSON Schema for the given {@link ServiceInfo} and {@link MethodInfo}.
     * @param serviceInfo service info.
     * @param methodInfo the method to generate the JSON Schema for.
     * @return ObjectNode containing the JSON schema for the parameter type.
     */
    private ObjectNode generate(ServiceInfo serviceInfo, MethodInfo methodInfo) {
        final ObjectNode root = mapper.createObjectNode();

        root.put("$id", methodInfo.id())
            .put("title", methodInfo.name())
            .put("description", methodInfo.descriptionInfo().docString())
            .put("additionalProperties", false)
            // TODO: Assumes every method takes an object, which is only valid for RPC based services
            //  and most of the REST services.
            .put("type", "object");

        // Workaround for gRPC services because we should unwrap the first "request" parameter.
        final boolean isProto = methodInfo.endpoints().stream().flatMap(x -> x.availableMimeTypes().stream())
                                          .anyMatch(x -> x.isProtobuf() || x.toString().contains("grpc"));

        final List<FieldInfo> methodFields = (isProto) ? typeNameToStructMapping
                .get(methodInfo.parameters().get(0)
                               .typeSignature()
                               .signature()).fields() : methodInfo.parameters();

        final Map<String, String> visited = new HashMap<>();
        final String currentPath = "#";

        if (isProto) {
            visited.put(methodInfo.parameters().get(0).typeSignature().signature(), currentPath);
        }
        generateFields(methodFields, visited, currentPath + "/properties", root);
        return root;
    }

    /**
     * Generate the JSON Schema for the given {@link FieldInfo} and add it to the given {@link ObjectNode}
     * and add required fields to the {@link ArrayNode}.
     * @param field field to generate schema for
     * @param visited map of visited types and their paths
     * @param path current path in tree traversal of fields
     * @param root the root to add schema properties
     * @param required the array node to add required field names
     */
    private void generateField(FieldInfo field, Map<String, String> visited, String path, ObjectNode root,
                               ArrayNode required) {
        final ObjectNode fieldNode = mapper.createObjectNode();
        final String fieldTypeSignature = field.typeSignature().signature();
        final String schemaType = getSchemaType(field.typeSignature());

        if (visited.containsKey(fieldTypeSignature)) {
            // If field is already visited, add a reference to the field instead of iterating its children.
            final String pathName = visited.get(fieldTypeSignature);
            fieldNode.put("$ref", pathName);
        } else {
            // Field is not visited, create a new type definition for it.
            fieldNode.put("type", schemaType);
            fieldNode.put("description", field.descriptionInfo().docString());

            // Fill required fields for the current object.
            if (field.requirement() == FieldRequirement.REQUIRED) {
                required.add(field.name());
            }

            if (field.typeSignature().type() == TypeSignatureType.ENUM) {
                fieldNode.set("enum", getEnumType(field.typeSignature()));
            }

            // Set the current path to be "PREVIOUS_PATH/field.name".
            final String currentPath = path + "/" + field.name();

            // Only Struct types map to custom objects to we need reference to those structs.
            // Having references to primitives do not make sense.
            if (MEMORIZED_JSON_TYPES.contains(schemaType)) {
                visited.put(fieldTypeSignature, currentPath);
            }

            if (field.typeSignature().type() == TypeSignatureType.MAP) {
                generateMapFields(fieldNode, field, visited, currentPath + "/properties");
            } else if (field.typeSignature().type() == TypeSignatureType.ITERABLE) {
                generateArrayFields(fieldNode, field, visited, currentPath + "/properties");
            } else {
                generateStructFields(fieldNode, field, visited, currentPath + "/properties");
            }
        }

        // Set current field inside the returned object.
        root.set(field.name(), fieldNode);
    }

    /**
     * Generate properties for the given fields and writes to the object node.
     * @param fields list of fields that the child has.
     * @param visited a map of visited fields, required for cycle detection.
     * @param path current path as defined in JSON Schema spec, required for cyclic references.
     * @param root object node that the results will be written to.
     */
    private void generateFields(List<FieldInfo> fields, Map<String, String> visited, String path,
                                ObjectNode root) {
        final ObjectNode objectNode = mapper.createObjectNode();
        final ArrayNode required = mapper.createArrayNode();

        for (FieldInfo field : fields) {
            if (VALID_FIELD_LOCATIONS.contains(field.location())) {
                generateField(field, visited, path, objectNode, required);
            }
        }

        root.set("properties", objectNode);
        root.set("required", required);
    }

    private void generateMapFields(ObjectNode fieldNode, FieldInfo field, Map<String, String> visited,
                                   String path) {
        final ObjectNode additionalProperties = mapper.createObjectNode();
        final String nextPath = path + '/' + field.name();

        // TODO: How does map<int, ...> serialize to json? I guess it uses string keys.
        //  Same question applies to other types too, how about Map<Object, ...>?
        final TypeSignature keyType =
                ((ContainerTypeSignature) field.typeSignature()).typeParameters().get(1);

        final String innerSchemaType = getSchemaType(keyType);

        if (visited.containsKey(keyType.signature())) {
            final String pathName = visited.get(keyType.signature());
            additionalProperties.put("$ref", pathName);
        } else {
            additionalProperties.put("type", innerSchemaType);

            if ("object".equals(innerSchemaType)) {
                final StructInfo fieldStructInfo = typeNameToStructMapping.get(keyType.name());

                visited.put(keyType.signature(), nextPath);
                generateFields(fieldStructInfo.fields(), visited, nextPath + "/additionalProperties",
                               additionalProperties);
            }
        }

        fieldNode.set("additionalProperties", additionalProperties);
    }

    private void generateArrayFields(ObjectNode fieldNode, FieldInfo field, Map<String, String> visited,
                                     String path) {
        final ObjectNode items = mapper.createObjectNode();
        final String nextPath = path + '/' + field.name();

        final TypeSignature itemsType =
                ((ContainerTypeSignature) field.typeSignature()).typeParameters().get(0);

        final String innerSchemaType = getSchemaType(itemsType);

        if (visited.containsKey(itemsType.signature())) {
            final String pathName = visited.get(itemsType.signature());
            items.put("$ref", pathName);
        } else {
            items.put("type", innerSchemaType);

            if ("object".equals(innerSchemaType)) {
                final StructInfo fieldStructInfo = typeNameToStructMapping.get(itemsType.name());
                visited.put(itemsType.signature(), nextPath);
                generateFields(fieldStructInfo.fields(), visited, nextPath + "/items", items);
            }
        }

        fieldNode.set("items", items);
    }

    private void generateStructFields(ObjectNode fieldNode, FieldInfo field, Map<String, String> visited,
                                      String path) {
        fieldNode.put("additionalProperties", true);

        final StructInfo fieldStructInfo = typeNameToStructMapping.get(field.typeSignature().name());

        // Iterate over each child field, generate their definitions.
        if (fieldStructInfo != null && !fieldStructInfo.fields().isEmpty()) {
            generateFields(fieldStructInfo.fields(), visited, path, fieldNode);
        }
    }

    @Nullable
    private ArrayNode getEnumType(TypeSignature type) {
        final ArrayNode enumArray = mapper.createArrayNode();
        final EnumInfo enumInfo = typeNameToEnumMapping.get(type.signature());

        if (enumInfo != null) {
            enumInfo.values().forEach(x -> enumArray.add(x.name()));
        }

        return enumArray;
    }

    private String getSchemaType(TypeSignature typeSignature) {
        if (typeSignature.type() == TypeSignatureType.ENUM) {
            return "string";
        }

        if (typeSignature.type() == TypeSignatureType.ITERABLE) {
            switch (typeSignature.name().toLowerCase()) {
                case "repeated":
                case "list":
                case "array":
                case "set":
                    return "array";
                default:
                    return "object";
            }
        }

        if (typeSignature.type() == TypeSignatureType.MAP) {
            return "object";
        }

        if (typeSignature.type() == TypeSignatureType.BASE) {
            switch (typeSignature.name().toLowerCase()) {
                case "boolean":
                    // Proto scalar types
                case "bool":
                    return "boolean";
                case "short":
                case "number":
                    // Proto scalar types
                case "float":
                case "double":
                    return "number";
                case "i":
                case "i32":
                case "i64":
                case "integer":
                case "int":
                case "l32":
                case "l64":
                case "long":
                case "long32":
                case "long64":
                    // Proto scalar types
                case "int32":
                case "int64":
                case "uint32":
                case "uint64":
                case "sint32":
                case "sint64":
                case "fixed32":
                case "fixed64":
                case "sfixed32":
                case "sfixed64":
                    return "integer";
                case "binary":
                    // Proto scalar types
                case "bytes":
                case "string":
                    return "string";
                default:
                    return "object";
            }
        }

        return "object";
    }
}
