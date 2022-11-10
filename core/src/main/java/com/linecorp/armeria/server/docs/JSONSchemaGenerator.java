/*
 * Copyright 2017 LINE Corporation
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.JacksonUtil;

import scala.annotation.meta.field;

/**
 * Generates a JSON Schema from the given service specification.
 */
@UnstableApi
public final class JSONSchemaGenerator {
    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    private static final String SCHEMA = "https://json-schema.org/draft/2020-12/schema";

    private static final class FieldSchemaWithAdditionalProperties {
        public final UUID uniqueId;
        public final ObjectNode node;
        public final Set<String> requiredFieldNames;

        public FieldSchemaWithAdditionalProperties(ObjectNode node) {
            this(node, new HashSet<>());
        }

        public FieldSchemaWithAdditionalProperties(ObjectNode node, Set<String> requiredFieldNames) {
            this.uniqueId = UUID.randomUUID();
            this.node = node;
            this.requiredFieldNames = requiredFieldNames;
        }
    }

    private static String getSchemaType(TypeSignature type) {
        if (type.isNamed()) {
            // Hacky way because protobuf library is not in classpath.
            // We can consider moving JSONSchemaGenerator for protos to grpc or protobuf lib.
            if (type.namedTypeDescriptor() != null && type.namedTypeDescriptor().getClass().getName().contains(
                    "Enum")) {
                return "string";
            } else {
                return "object";
            }
        }

        if (type.isContainer()) {
            switch (type.name().toLowerCase()) {
                case "repeated":
                case "list":
                case "array":
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
                    return "null";
            }
        }

        return "any";
    }

    /**
     * Generate a FieldSchemaWithAdditionalProperties for the given fields.
     * @param fields list of fields that the child has.
     * @param visited a map of visited fields, required for cycle detection.
     * @param path current path as defined in JSON Schema spec, required for cyclic references.
     * @return a FieldSchemaWithAdditionalProperties for the given fields.
     */
    private static FieldSchemaWithAdditionalProperties generateFields(List<FieldInfo> fields,
                                                                      Map<String, String> visited,
                                                                      String path) {
        final ObjectNode objectNode = mapper.createObjectNode();
        final Set<String> requiredFields = new HashSet<>();

        for (FieldInfo field : fields) {
            final ObjectNode fieldNode = mapper.createObjectNode();
            String fieldTypeName = field.typeSignature().name();

            System.out.println(field.typeSignature().namedTypeDescriptor());
            if (field.typeSignature().isNamed() && visited.containsKey(fieldTypeName)) {
                // If field is already visited, add a reference to the field instead of iterating over its children.
                String pathName = visited.get(fieldTypeName);
                fieldNode.put("$ref", pathName);
            } else {
                // Field is not visited, create a new type definition for it.
                String schemaType = getSchemaType(field.typeSignature());
                fieldNode.put("type", schemaType);
                fieldNode.put("description", field.descriptionInfo().docString());

                if (schemaType.equals("array")) {
                    // TODO: Support for repeated fields with non-primitive types.
                    // Use "items": { ... }
                    // But unfortunately container types do not contain field infos.
                    // Maybe consider using $ref ?
                }

                // Iterate over each child field.
                if (!field.childFieldInfos().isEmpty()) {
                    // Set the current path to be "PREVIOUS_PATH/field.name"
                    String currentPath = path + "/" + field.name();

                    // Mark current field as visited
                    visited.put(fieldTypeName, currentPath);
                    FieldSchemaWithAdditionalProperties childProperties = generateFields(
                            field.childFieldInfos(), visited, currentPath);

                    fieldNode.set("properties", childProperties.node);

                    // Find which child properties are required.
                    ArrayNode required = mapper.createArrayNode();
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

    public static ObjectNode generate(StructInfo info) {
        ObjectNode root = mapper.createObjectNode();
        root.put("$schema", SCHEMA)
            .put("title", info.name())
            .put("description", info.descriptionInfo().docString())
            .put("type", "object");

        // Initialize an empty visit map, push current type to the visit map.
        Map<String, String> visited = new HashMap<>();
        String currentPath = "#";
        visited.put(info.name(), currentPath);

        FieldSchemaWithAdditionalProperties properties = generateFields(info.fields(), visited, currentPath);
        root.set("properties", properties.node);

        ArrayNode required = mapper.createArrayNode();
        for (String requiredField : properties.requiredFieldNames) {
            required.add(requiredField);
        }

        root.set("required", required);
        return root;
    }
}
