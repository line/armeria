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
import java.util.HashSet;
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

    public static ArrayNode generate(ServiceSpecification serviceSpecification) {
        final JsonSchemaGenerator generator = new JsonSchemaGenerator(serviceSpecification);
        return generator.generate();
    }

    public static ObjectNode generate(StructInfo structInfo) {
        final JsonSchemaGenerator generator = new JsonSchemaGenerator();
        return generator.generateFromStructInfo(structInfo, "#");
    }

    private static final class FieldSchemaWithAdditionalProperties {
        private final ObjectNode node;
        private final Set<String> requiredFieldNames;

        private FieldSchemaWithAdditionalProperties(ObjectNode node, Set<String> requiredFieldNames) {
            this.node = node;
            this.requiredFieldNames = requiredFieldNames;
        }
    }

    private final Set<EnumInfo> enumInfos;
    private final Set<StructInfo> structInfos;
    private final Set<ServiceInfo> serviceInfos;
    private final Map<String, StructInfo> typeToStructMapping;
    private final Map<String, EnumInfo> typeToEnumMapping;

    private JsonSchemaGenerator(ServiceSpecification serviceSpecification) {
        this.enumInfos = serviceSpecification.enums();
        this.structInfos = serviceSpecification.structs();
        this.serviceInfos = serviceSpecification.services();
        this.typeToStructMapping = structInfos.stream().collect(
                Collectors.toMap(StructInfo::name, Function.identity()));
        this.typeToEnumMapping = enumInfos.stream().collect(
                Collectors.toMap(EnumInfo::name, Function.identity()));
    }

    private JsonSchemaGenerator() {
        this.enumInfos = new HashSet<>();
        this.structInfos = new HashSet<>();
        this.serviceInfos = new HashSet<>();
        this.typeToStructMapping = new HashMap<>();
        this.typeToEnumMapping = new HashMap<>();
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

        final String fullQualifier =
                serviceInfo.name() + '/' + methodInfo.name() + '/' + methodInfo.httpMethod().name();

        root.put("$id", fullQualifier)
            .put("title", methodInfo.name())
            .put("description", methodInfo.descriptionInfo().docString())
            .put("additionalProperties", false).put("type", "object");

        // Workaround for gRPC services because they do not have parameters in method info.
        final boolean isProto = methodInfo.endpoints().stream().flatMap(x -> x.availableMimeTypes().stream())
                                          .anyMatch(MediaType::isProtobuf);

        final List<FieldInfo> methodFields = (isProto) ? typeToStructMapping
                .get(methodInfo.parameters().get(0)
                               .typeSignature()
                               .name()).fields() : methodInfo.parameters();

        // TODO: Thrift is not working for child parameters, consider having a custom logic for Thrift too.

        final FieldSchemaWithAdditionalProperties properties = generateFields(methodFields);
        root.set("properties", properties.node);

        final ArrayNode required = mapper.createArrayNode();
        for (String requiredField : properties.requiredFieldNames) {
            required.add(requiredField);
        }

        root.set("required", required);
        return root;
    }

    private FieldSchemaWithAdditionalProperties generateFields(List<FieldInfo> fields) {
        return generateFields(fields, new HashMap<>(), "#");
    }

    /**
     * Generate a FieldSchemaWithAdditionalProperties for the given fields.
     * @param fields list of fields that the child has.
     * @param visited a map of visited fields, required for cycle detection.
     * @param path current path as defined in JSON Schema spec, required for cyclic references.
     * @return a FieldSchemaWithAdditionalProperties for the given fields.
     */

    private FieldSchemaWithAdditionalProperties generateFields(List<FieldInfo> fields,
                                                               Map<String, String> visited, String path) {
        final ObjectNode objectNode = mapper.createObjectNode();
        final Set<String> requiredFields = new HashSet<>();

        // TODO: Consider filtering header & path params.
        for (FieldInfo field : fields) {
            final ObjectNode fieldNode = mapper.createObjectNode();
            final String fieldTypeName = field.typeSignature().name();

            if (field.typeSignature().isNamed() && visited.containsKey(fieldTypeName)) {
                // If field is already visited, add a reference to the field instead of iterating its children.
                final String pathName = visited.get(fieldTypeName);
                fieldNode.put("$ref", pathName);
            } else {
                // Field is not visited, create a new type definition for it.
                final String schemaType = getSchemaType(field.typeSignature());
                fieldNode.put("type", schemaType);

                final ArrayNode enumValues = getEnumType(field.typeSignature());
                if (enumValues != null) {
                    fieldNode.set("enum", enumValues);
                }

                final ObjectNode itemsType = getItemsType(field.typeSignature(), path);
                if (itemsType != null) {
                    fieldNode.set("items", itemsType);
                }

                fieldNode.put("description", field.descriptionInfo().docString());

                // Iterate over each child field.
                if (!field.childFieldInfos().isEmpty()) {
                    // Set the current path to be "PREVIOUS_PATH/field.name"
                    final String currentPath = path + '/' + field.name();

                    // Mark current field as visited
                    visited.put(fieldTypeName, currentPath);
                    final FieldSchemaWithAdditionalProperties childProperties = generateFields(
                            field.childFieldInfos(), visited, currentPath);

                    fieldNode.set("properties", childProperties.node);
                    fieldNode.put("additionalProperties", false);

                    // Find which child properties are required.
                    final ArrayNode required = mapper.createArrayNode();
                    for (String requiredField : childProperties.requiredFieldNames) {
                        required.add(requiredField);
                    }

                    fieldNode.set("required", required);
                }

                // Fill required fields for the current object.
                if (field.requirement() == FieldRequirement.REQUIRED) {
                    requiredFields.add(field.name());
                }
            }

            // Set current field inside the returned object.
            objectNode.set(field.name(), fieldNode);
        }

        return new FieldSchemaWithAdditionalProperties(objectNode, requiredFields);
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

        final FieldSchemaWithAdditionalProperties properties =
                generateFields(info.fields(), visited, currentPath);
        root.set("properties", properties.node);

        final ArrayNode required = mapper.createArrayNode();
        for (String requiredField : properties.requiredFieldNames) {
            required.add(requiredField);
        }

        root.set("required", required);
        return root;
    }

    @Nullable
    private ArrayNode getEnumType(TypeSignature type) {
        // Hacky way because protobuf library is not in classpath.
        // We can consider moving JSONSchemaGenerator for protos to grpc or protobuf lib.
        if (type.isNamed() &&
            type.namedTypeDescriptor() != null &&
            type.namedTypeDescriptor().getClass().getName().contains("Enum")) {

            final EnumInfo enumInfo = typeToEnumMapping.get(type.name());

            if (enumInfo == null) {
                return null;
            }

            final ArrayNode enumArray = mapper.createArrayNode();
            enumInfo.values().forEach(x -> enumArray.add(x.name()));
            return enumArray;
        }

        return null;
    }

    @Nullable
    private ObjectNode getItemsType(TypeSignature type, String path) {
        if (type.isContainer()) {
            switch (type.name().toLowerCase()) {
                case "repeated":
                case "list":
                case "array":
                case "set":
                    final TypeSignature head = type.typeParameters().get(0);
                    if (getSchemaType(type).equals("object")) {
                        final StructInfo structInfo = typeToStructMapping.get(head.name());
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

    private String getSchemaType(TypeSignature type) {
        // Hacky way because protobuf library is not in classpath.
        // We can consider moving JSONSchemaGenerator for protos to grpc or protobuf lib.
        if (type.namedTypeDescriptor() != null &&
            type.namedTypeDescriptor().getClass().getName().toLowerCase().contains("enum")) {
            return "string";
        }

        if (type.isContainer()) {
            switch (type.name().toLowerCase()) {
                case "repeated":
                case "list":
                case "array":
                case "set":
                    return "array";
                default:
                    return "object";
            }
        }

        if (type.isBase()) {
            switch (type.name().toLowerCase()) {
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
