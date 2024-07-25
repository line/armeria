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

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.JacksonUtil;

/**
 * Generates a JSON Schema from the given service specification.
 *
 * @see <a href="https://json-schema.org/">JSON schema</a>
 */
final class JsonSchemaGenerator {

    private static final Logger logger = LoggerFactory.getLogger(JsonSchemaGenerator.class);

    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    private static final List<FieldLocation> VALID_FIELD_LOCATIONS = ImmutableList.of(
            FieldLocation.BODY,
            FieldLocation.UNSPECIFIED);

    private static final List<String> MEMORIZED_JSON_TYPES = ImmutableList.of("array", "object");

    /**
     * Generate an array of json schema specifications for each method inside the service.
     *
     * @param serviceSpecification the service specification to generate the json schema from.
     *
     * @return ArrayNode that contains service specifications
     */
    static ArrayNode generate(ServiceSpecification serviceSpecification) {
        // TODO: Test for Thrift and annotated services
        final JsonSchemaGenerator generator = new JsonSchemaGenerator(serviceSpecification);
        return generator.generate();
    }

    private final Set<ServiceInfo> serviceInfos;
    private final Map<String, StructInfo> typeSignatureToStructMapping;
    private final Map<String, EnumInfo> typeNameToEnumMapping;

    private JsonSchemaGenerator(ServiceSpecification serviceSpecification) {
        serviceInfos = serviceSpecification.services();
        final ImmutableMap.Builder<String, StructInfo> typeSignatureToStructMappingBuilder =
                ImmutableMap.builderWithExpectedSize(serviceSpecification.structs().size());
        for (StructInfo struct : serviceSpecification.structs()) {
            typeSignatureToStructMappingBuilder.put(struct.name(), struct);
            if (struct.alias() != null && !struct.alias().equals(struct.name())) {
                // TypeSignature.signature() could be StructInfo.alias() if the type is a protobuf Message.
                typeSignatureToStructMappingBuilder.put(struct.alias(), struct);
            }
        }
        typeSignatureToStructMapping = typeSignatureToStructMappingBuilder.build();
        typeNameToEnumMapping = serviceSpecification.enums().stream().collect(
                toImmutableMap(EnumInfo::name, Function.identity()));
    }

    private ArrayNode generate() {
        final ArrayNode definitions = mapper.createArrayNode();

        final Set<ObjectNode> methodDefinitions =
                serviceInfos.stream()
                            .flatMap(serviceInfo -> serviceInfo.methods().stream().map(this::generate))
                            .collect(toImmutableSet());

        return definitions.addAll(methodDefinitions);
    }

    /**
     * Generate the JSON Schema for the given {@link MethodInfo}.
     *
     * @param methodInfo the method to generate the JSON Schema for.
     *
     * @return ObjectNode containing the JSON schema for the parameter type.
     */
    private ObjectNode generate(MethodInfo methodInfo) {
        final ObjectNode root = mapper.createObjectNode();

        root.put("$id", methodInfo.id())
            .put("title", methodInfo.name())
            .put("description", methodInfo.descriptionInfo().docString())
            .put("additionalProperties", false)
            // TODO: Assumes every method takes an object, which is only valid for RPC based services
            //  and most of the REST services.
            .put("type", "object");

        final List<FieldInfo> methodFields;
        final Map<TypeSignature, String> visited = new HashMap<>();
        final String currentPath = "#";

        if (methodInfo.useParameterAsRoot()) {
            final TypeSignature signature = methodInfo.parameters().get(0)
                                                      .typeSignature();
            final StructInfo structInfo = typeSignatureToStructMapping.get(signature.signature());
            if (structInfo == null) {
                logger.debug("Could not find root parameter with signature: {}", signature);
                root.put("additionalProperties", true);
                methodFields = ImmutableList.of();
            } else {
                methodFields = structInfo.fields();
            }
            visited.put(signature, currentPath);
        } else {
            methodFields = methodInfo.parameters();
        }

        generateProperties(methodFields, visited, currentPath, root);
        return root;
    }

    /**
     * Generate the JSON Schema for the given {@link FieldInfo} and add it to the given {@link ObjectNode}
     * and add required fields to the {@link ArrayNode}.
     *
     * @param field field to generate schema for
     * @param visited map of visited types and their paths
     * @param path current path in tree traversal of fields
     * @param parent the parent to add schema properties
     * @param required the array node to add required field names, if parent doesn't support, it is null.
     */
    private void generateField(FieldInfo field, Map<TypeSignature, String> visited, String path,
                               ObjectNode parent,
                               @Nullable ArrayNode required) {
        final ObjectNode fieldNode = mapper.createObjectNode();
        final TypeSignature fieldTypeSignature = field.typeSignature();

        fieldNode.put("description", field.descriptionInfo().docString());

        // Fill required fields for the current object.
        if (required != null && field.requirement() == FieldRequirement.REQUIRED) {
            required.add(field.name());
        }

        if (visited.containsKey(fieldTypeSignature)) {
            // If field is already visited, add a reference to the field instead of iterating its children.
            final String pathName = visited.get(fieldTypeSignature);
            fieldNode.put("$ref", pathName);
        } else {
            final String schemaType = getSchemaType(field.typeSignature());

            // Field is not visited, create a new type definition for it.
            fieldNode.put("type", schemaType);

            if (field.typeSignature().type() == TypeSignatureType.ENUM) {
                fieldNode.set("enum", getEnumType(field.typeSignature()));
            }

            final String currentPath;
            if (field.name().isEmpty()) {
                currentPath = path;
            } else {
                currentPath = path + '/' + field.name();
            }

            // Only Struct types map to custom objects to we need reference to those structs.
            // Having references to primitives do not make sense.
            if (MEMORIZED_JSON_TYPES.contains(schemaType)) {
                visited.put(fieldTypeSignature, currentPath);
            }

            // Based on field type, we need to call the appropriate method to generate the schema.
            // For example maps have `additionalProperties` field, arrays have `items` field and structs
            // have `properties` field.
            if (field.typeSignature().type() == TypeSignatureType.MAP) {
                generateMapFields(fieldNode, field, visited, currentPath);
            } else if (field.typeSignature().type() == TypeSignatureType.ITERABLE) {
                generateArrayFields(fieldNode, field, visited, currentPath);
            } else if ("object".equals(schemaType)) {
                generateStructFields(fieldNode, field, visited, currentPath);
            }
        }

        // Set current field inside the returned object.
        // If field is nameless, unpack it.
        // Example:
        // For `list<int> x` we should have `{"x": {"items": {"type": "integer"}}}`
        // Not `{"x": {"items": {"": {"type": "integer"}}}}`
        if (field.name().isEmpty()) {
            parent.setAll(fieldNode);
        } else {
            parent.set(field.name(), fieldNode);
        }
    }

    /**
     * Generate properties for the given fields and writes to the object node.
     *
     * @param fields list of fields that the child has.
     * @param visited a map of visited fields, required for cycle detection.
     * @param path current path as defined in JSON Schema spec, required for cyclic references.
     * @param parent object node that the results will be written to.
     */
    private void generateProperties(List<FieldInfo> fields, Map<TypeSignature, String> visited, String path,
                                    ObjectNode parent) {
        final ObjectNode objectNode = mapper.createObjectNode();
        final ArrayNode required = mapper.createArrayNode();

        for (FieldInfo field : fields) {
            if (VALID_FIELD_LOCATIONS.contains(field.location())) {
                generateField(field, visited, path + "/properties", objectNode, required);
            }
        }

        parent.set("properties", objectNode);
        parent.set("required", required);
    }

    /**
     * Create the JSON node for a map field.
     * Example for `map(string, int)`: {"type": "object", "additionalProperties": {"type": "integer"}}
     *
     * @see <a href="https://json-schema.org/understanding-json-schema/reference/object.html#additional-properties">JSON Schema</a>
     */
    private void generateMapFields(ObjectNode fieldNode, FieldInfo field, Map<TypeSignature, String> visited,
                                   String path) {
        final ObjectNode additionalProperties = mapper.createObjectNode();

        // Keys are always converted to strings.
        final TypeSignature valueType = ((MapTypeSignature) field.typeSignature()).valueTypeSignature();
        // Create a field info with no name. Field infos with no name are considered to be unpacked.
        final FieldInfo valueFieldInfo = FieldInfo.builder("", valueType)
                                                  .location(FieldLocation.BODY)
                                                  .requirement(FieldRequirement.OPTIONAL)
                                                  .build();

        // Recursively generate the field.
        generateField(valueFieldInfo, visited, path + "/additionalProperties", additionalProperties, null);

        fieldNode.set("additionalProperties", additionalProperties);
    }

    /**
     * Create the JSON node for an array field.
     * Example for `list(int)`: {"type": "array", "items": {"type": "integer"}}
     *
     * @see <a href="https://json-schema.org/understanding-json-schema/reference/array.html">JSON Schema</a>
     */
    private void generateArrayFields(ObjectNode fieldNode, FieldInfo field, Map<TypeSignature, String> visited,
                                     String path) {
        final ObjectNode items = mapper.createObjectNode();

        final TypeSignature itemsType =
                ((ContainerTypeSignature) field.typeSignature()).typeParameters().get(0);
        // Create a field info with no name. Field infos with no name are considered to be unpacked.
        final FieldInfo itemFieldInfo = FieldInfo.builder("", itemsType)
                                                 .location(FieldLocation.BODY)
                                                 .requirement(FieldRequirement.OPTIONAL)
                                                 .build();

        generateField(itemFieldInfo, visited, path + "/items", items, null);

        fieldNode.set("items", items);
    }

    /**
     * Create the JSON node for a struct (object) field. Most custom classes are serialized as structs.
     * Example for `Foo(Integer x)`: {"type": "object", "properties": {"x": {"type": "integer"}}}
     *
     * @see <a href="https://json-schema.org/understanding-json-schema/reference/object.html#properties">JSON Schema</a>
     */
    private void generateStructFields(ObjectNode fieldNode, FieldInfo field, Map<TypeSignature, String> visited,
                                      String path) {

        final StructInfo fieldStructInfo = typeSignatureToStructMapping.get(field.typeSignature().signature());
        fieldNode.put("additionalProperties", fieldStructInfo == null);

        if (fieldStructInfo == null) {
            logger.debug("Could not find struct with signature: {}",
                         field.typeSignature().signature());
        }

        // Iterate over each child field, generate their definitions.
        if (fieldStructInfo != null && !fieldStructInfo.fields().isEmpty()) {
            generateProperties(fieldStructInfo.fields(), visited, path, fieldNode);
        }
    }

    /**
     * Get the JSON type for the given enum type.
     * Example: `enum Foo { BAR, BAZ }`: {"type": "string", "enum": ["BAR", "BAZ"]}
     */
    private ArrayNode getEnumType(TypeSignature type) {
        final ArrayNode enumArray = mapper.createArrayNode();
        final EnumInfo enumInfo = typeNameToEnumMapping.get(type.signature());

        if (enumInfo != null) {
            enumInfo.values().forEach(x -> enumArray.add(x.name()));
        }

        return enumArray;
    }

    /**
     * Get the JSON type for the given type. Unknown types are returned as `object`.
     * This list can be extended to support more types.
     *
     * @see <a href="https://json-schema.org/understanding-json-schema/reference/type.html">JSON Schema</a>
     */
    private static String getSchemaType(TypeSignature typeSignature) {
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
                case "bool":
                    return "boolean";
                case "short":
                case "number":
                case "float":
                case "double":
                    return "number";
                case "i":
                case "i8":
                case "i16":
                case "i32":
                case "i64":
                case "integer":
                case "int":
                case "l32":
                case "l64":
                case "long":
                case "long32":
                case "long64":
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
                case "byte":
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
