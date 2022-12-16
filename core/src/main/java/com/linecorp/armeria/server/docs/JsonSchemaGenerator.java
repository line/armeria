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

import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.JacksonUtil;

/**
 * Generates a JSON Schema from the given service specification.
 */
final class JsonSchemaGenerator {

    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    /**
     * Generate an array of json schema specifications for each method inside the service.
     * @param serviceSpecification
     * @return ArrayNode that contains service specifications
     */
    public static ArrayNode generate(ServiceSpecification serviceSpecification) {
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
            // TODO: Assumes every method takes an object, which is only valid for RPC based services
            //  and most of the REST services.
            .put("additionalProperties", false).put("type", "object");

        // Workaround for gRPC services because they do not have parameters in method info.
        final boolean isProto = methodInfo.endpoints().stream().flatMap(x -> x.availableMimeTypes().stream())
                                          .anyMatch(MediaType::isProtobuf);

        final List<FieldInfo> methodFields = (isProto) ? typeNameToStructMapping
                .get(methodInfo.parameters().get(0)
                               .typeSignature()
                               .name()).fields() : methodInfo.parameters();

        // TODO: Thrift is not working for child parameters, consider having a custom logic for Thrift too.

        generateFields(methodFields, root);

        return root;
    }

    private void generateFields(List<FieldInfo> fields, ObjectNode root) {
        generateFields(fields, new HashMap<>(), "#", root);
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
        final String fieldTypeName = field.typeSignature().name();

        // Only Struct types map to custom objects to we need reference to those structs
        if (field.typeSignature().type() == TypeSignatureType.STRUCT && visited.containsKey(
                fieldTypeName)) {
            // If field is already visited, add a reference to the field instead of iterating its children.
            final String pathName = visited.get(fieldTypeName);
            fieldNode.put("$ref", pathName);
        } else {
            // Field is not visited, create a new type definition for it.
            fieldNode.put("type", getSchemaType(field.typeSignature()));
            fieldNode.put("description", field.descriptionInfo().docString());
            fieldNode.put("additionalProperties", false);

            // Fill required fields for the current object.
            if (field.requirement() == FieldRequirement.REQUIRED) {
                required.add(field.name());
            }

            if (field.typeSignature().type() == TypeSignatureType.ENUM) {
                fieldNode.set("enum", getEnumType(field.typeSignature()));
            }

            if (field.typeSignature().type() == TypeSignatureType.ITERABLE) {
                final ObjectNode itemsType = getItemsType(field.typeSignature(), path);
                if (itemsType != null) {
                    fieldNode.set("items", itemsType);
                }
            }

            final StructInfo fieldStructInfo = typeNameToStructMapping.get(field.typeSignature().name());

            // Set the current path to be "PREVIOUS_PATH/field.name"
            final String currentPath = path + '/' + field.name();
            // Mark current field as visited
            visited.put(fieldTypeName, currentPath);

            // Iterate over each child field, generate their definitions.
            if (fieldStructInfo != null && !fieldStructInfo.fields().isEmpty()) {
                generateFields(fieldStructInfo.fields(), visited, currentPath, fieldNode);
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

        // TODO: Consider filtering header & path params.
        for (FieldInfo field : fields) {
            generateField(field, visited, path, objectNode, required);
        }

        root.set("properties", objectNode);
        root.set("required", required);
    }

    /**
     * Generate the JSON Schema for the given {@link StructInfo}.
     * @param info struct info object.
     * @return ObjectNode containing the JSON schema for the given struct.
     */
    private ObjectNode generateFromStructInfo(StructInfo info, String path) {
        final ObjectNode root = mapper.createObjectNode();
        root
                .put("title", info.name())
                .put("description", info.descriptionInfo().docString())
                .put("additionalProperties", false)
                .put("type", "object");

        // Initialize an empty visit map, push current type to the visit map.
        final Map<String, String> visited = new HashMap<>();
        final String currentPath = "#";
        visited.put(info.name(), currentPath);

        generateFields(info.fields(), visited, currentPath, root);

        return root;
    }

    @Nullable
    private ArrayNode getEnumType(TypeSignature type) {
        final ArrayNode enumArray = mapper.createArrayNode();
        final EnumInfo enumInfo = typeNameToEnumMapping.get(type.name());

        if (enumInfo != null) {
            enumInfo.values().forEach(x -> enumArray.add(x.name()));
        }

        return enumArray;
    }

    @Nullable
    private ObjectNode getItemsType(TypeSignature type, String path) {
        if (type.type() == TypeSignatureType.ITERABLE) {
            switch (type.name().toLowerCase()) {
                case "repeated":
                case "list":
                case "array":
                case "set":
                    final TypeSignature head = ((ContainerTypeSignature) type).typeParameters().get(0);
                    if (getSchemaType(type).equals("object")) {
                        final StructInfo structInfo = typeNameToStructMapping.get(head.name());
                        if (structInfo != null) {
                            return generateFromStructInfo(structInfo, path);
                        } else {
                            return null;
                        }
                    } else {
                        final ObjectNode primitiveNode = mapper.createObjectNode();
                        primitiveNode.put("type", getSchemaType(head));
                        return primitiveNode;
                    }
                default:
                    return null;
            }
        }

        return null;
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
