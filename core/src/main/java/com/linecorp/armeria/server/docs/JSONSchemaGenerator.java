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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.JacksonUtil;

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
        // TODO: Test
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
                case "bool":
                    return "boolean";
                case "byte":
                case "short":
                case "i":
                case "i32":
                case "i64":
                case "int":
                case "int32":
                case "int64":
                case "integer":
                case "l32":
                case "l64":
                case "long":
                case "long32":
                case "long64":
                case "float":
                case "double":
                    return "number";
                case "string":
                case "binary":
                    return "string";
                default:
                    return "null";
            }
        }

        return "any";
    }

    private static FieldSchemaWithAdditionalProperties generateFields(List<FieldInfo> fields) {
        final ObjectNode objectNode = mapper.createObjectNode();
        final Set<String> requiredFields = new HashSet<>();

        for (FieldInfo field : fields) {
            final ObjectNode fieldNode = mapper.createObjectNode();
            fieldNode.put("type", getSchemaType(field.typeSignature()));
            fieldNode.put("description", field.descriptionInfo().docString());

            if (!field.childFieldInfos().isEmpty()) {
                // TODO: Handle recursion
                FieldSchemaWithAdditionalProperties childProperties = generateFields(field.childFieldInfos());
                fieldNode.set("properties", childProperties.node);

                ArrayNode required = mapper.createArrayNode();
                for (String requiredField : childProperties.requiredFieldNames) {
                    required.add(requiredField);
                }

                fieldNode.set("required", required);
            }

            if (field.requirement() == FieldRequirement.REQUIRED) {
                requiredFields.add(field.name());
            }

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

        FieldSchemaWithAdditionalProperties properties = generateFields(info.fields());
        root.set("properties", properties.node);

        ArrayNode required = mapper.createArrayNode();
        for (String requiredField : properties.requiredFieldNames) {
            required.add(requiredField);
        }

        root.set("required", required);
        return root;
    }
}
