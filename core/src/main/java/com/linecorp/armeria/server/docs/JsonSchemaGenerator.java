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
import static java.util.Objects.requireNonNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableMap;

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

    private final ServiceSpecification serviceSpecification;
    private final Map<String, StructInfo> structs;
    private final Map<String, EnumInfo> enums;

    private JsonSchemaGenerator(ServiceSpecification serviceSpecification) {
        this.serviceSpecification = requireNonNull(serviceSpecification, "serviceSpecification");

        final ImmutableMap.Builder<String, StructInfo> structsBuilder =
                ImmutableMap.builderWithExpectedSize(serviceSpecification.structs().size());
        for (final StructInfo structInfo : serviceSpecification.structs()) {
            structsBuilder.put(structInfo.name(), structInfo);
            if (structInfo.alias() != null) {
                // TypeSignature.signature() could be StructInfo.alias() if the type is a protobuf Message.
                structsBuilder.put(structInfo.alias(), structInfo);
            }
        }
        structs = structsBuilder.build();

        enums = serviceSpecification.enums().stream()
                                    .collect(toImmutableMap(EnumInfo::name, Function.identity()));
    }

    // Public static entry point
    static ArrayNode generate(ServiceSpecification serviceSpecification) {
        return new JsonSchemaGenerator(serviceSpecification).doGenerate();
    }

    // Map an Armeria TypeSignature to JSON Schema "type"
    private static String getSchemaType(TypeSignature typeSignature) {
        switch (typeSignature.type()) {
            case ENUM:
                return "string";
            case ITERABLE:
                return "array";
            case MAP:
            case STRUCT:
                return "object";
            case OPTIONAL:
            case CONTAINER: {
                // Unwrap and return the inner type's schema type
                final TypeSignature inner =
                        ((ContainerTypeSignature) typeSignature).typeParameters().get(0);
                return getSchemaType(inner);
            }
            default:
                break;
        }

        // Base types
        switch (typeSignature.name().toLowerCase()) {
            case "boolean":
            case "bool":
                return "boolean";
            case "short":
            case "float":
            case "double":
                return "number";
            case "i8":
            case "i16":
            case "i32":
            case "i64":
            case "integer":
            case "int":
            case "long":
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

    private ArrayNode doGenerate() {
        final ObjectNode definitions = generateDefinitions();
        final ArrayNode methodSchemas = mapper.createArrayNode();
        for (final ServiceInfo svc : serviceSpecification.services()) {
            for (final MethodInfo m : svc.methods()) {
                final ObjectNode schema = generateMethodSchema(m, definitions);
                methodSchemas.add(schema);
            }
        }
        return methodSchemas;
    }

    private ObjectNode generateDefinitions() {
        final ObjectNode definitionsNode = mapper.createObjectNode();
        for (final StructInfo structInfo : serviceSpecification.structs()) {
            definitionsNode.set(structInfo.name(), generateStructDefinition(structInfo));
        }
        for (final EnumInfo enumInfo : serviceSpecification.enums()) {
            definitionsNode.set(enumInfo.name(), generateEnumDefinition(enumInfo));
        }
        return definitionsNode;
    }

    private ObjectNode generateStructDefinition(StructInfo structInfo) {
        final ObjectNode schemaNode = mapper.createObjectNode();
        schemaNode.put("type", "object");
        schemaNode.put("title", structInfo.name());
        final String docString = structInfo.descriptionInfo().docString();
        if (!docString.isEmpty()) {
            schemaNode.put("description", docString);
        }

        final List<TypeSignature> oneOf = structInfo.oneOf();
        if (!oneOf.isEmpty()) {
            final ArrayNode oneOfNode = schemaNode.putArray("oneOf");
            oneOf.forEach(sub -> {
                final ObjectNode ref = mapper.createObjectNode();
                ref.put("$ref", "#/definitions/" + sub.name());
                oneOfNode.add(ref);
            });

            final DiscriminatorInfo discriminator = structInfo.discriminator();
            if (discriminator != null) {
                final ObjectNode disc = schemaNode.putObject("discriminator");
                disc.put("propertyName", discriminator.propertyName());
                if (!discriminator.mapping().isEmpty()) {
                    final ObjectNode mapping = disc.putObject("mapping");
                    discriminator.mapping().forEach(mapping::put);
                }
            }
            return schemaNode;
        }

        final ObjectNode props = mapper.createObjectNode();
        final ArrayNode required = mapper.createArrayNode();
        for (final FieldInfo field : structInfo.fields()) {
            props.set(field.name(), generateFieldSchema(field));
            if (field.requirement() == FieldRequirement.REQUIRED) {
                required.add(field.name());
            }
        }
        if (!props.isEmpty()) {
            schemaNode.set("properties", props);
        }
        if (!required.isEmpty()) {
            schemaNode.set("required", required);
        }
        return schemaNode;
    }

    private static ObjectNode generateEnumDefinition(EnumInfo enumInfo) {
        final ObjectNode schemaNode = mapper.createObjectNode();
        schemaNode.put("type", "string");
        final ArrayNode enumValues = mapper.createArrayNode();
        enumInfo.values().forEach(value -> enumValues.add(value.name()));
        schemaNode.set("enum", enumValues);
        return schemaNode;
    }

    private ObjectNode generateMethodSchema(MethodInfo methodInfo, ObjectNode definitions) {
        final ObjectNode root = mapper.createObjectNode();
        root.put("$id", methodInfo.id());
        root.put("title", methodInfo.name());
        final String docString = methodInfo.descriptionInfo().docString();
        if (!docString.isEmpty()) {
            root.put("description", docString);
        }
        root.put("additionalProperties", false);
        root.put("type", "object");
        if (!methodInfo.useParameterAsRoot()) {
            final ObjectNode propertiesNode = mapper.createObjectNode();
            final ArrayNode requiredNode = mapper.createArrayNode();

            for (final FieldInfo field : methodInfo.parameters()) {
                final FieldLocation loc = field.location();
                if (loc == FieldLocation.BODY || loc == FieldLocation.UNSPECIFIED) {
                    propertiesNode.set(field.name(), generateFieldSchema(field));
                    if (field.requirement() == FieldRequirement.REQUIRED) {
                        requiredNode.add(field.name());
                    }
                }
            }

            if (!propertiesNode.isEmpty()) {
                root.set("properties", propertiesNode);
            }
            if (!requiredNode.isEmpty()) {
                root.set("required", requiredNode);
            }

            root.set("definitions", definitions);
            return root;
        }

        //gRPC
        final Map<TypeSignature, String> visited = new HashMap<>();
        final FieldInfo firstParam = methodInfo.parameters().get(0);
        final StructInfo structInfo = structs.get(firstParam.typeSignature().signature());

        if (structInfo != null) {
            visited.put(firstParam.typeSignature(), "#");
            generateProperties(structInfo.fields(), visited, "#", root);
        } else {
            logger.warn("Could not find root struct for signature: {}",
                        firstParam.typeSignature().signature());
            root.put("additionalProperties", true);
        }
        return root;
    }

    private ObjectNode generateFieldSchema(FieldInfo field) {
        final ObjectNode fieldNode = mapper.createObjectNode();
        final TypeSignature typeSignature = field.typeSignature();
        final String docString = field.descriptionInfo().docString();
        if (!docString.isEmpty()) {
            fieldNode.put("description", docString);
        }

        if (typeSignature.type() == TypeSignatureType.STRUCT ||
            typeSignature.type() == TypeSignatureType.ENUM) {
            fieldNode.put("$ref", "#/definitions/" + typeSignature.name());
            return fieldNode;
        }

        if (typeSignature.type() == TypeSignatureType.OPTIONAL ||
            typeSignature.type() == TypeSignatureType.CONTAINER) {
            final TypeSignature inner =
                    ((ContainerTypeSignature) typeSignature).typeParameters().get(0);
            final ObjectNode innerNode = generateFieldSchema(FieldInfo.of("", inner));
            fieldNode.setAll(innerNode);
            return fieldNode;
        }

        final String schemaType = getSchemaType(typeSignature);
        fieldNode.put("type", schemaType);

        switch (typeSignature.type()) {
            case ITERABLE: {
                final TypeSignature itemType =
                        ((ContainerTypeSignature) typeSignature).typeParameters().get(0);
                fieldNode.set("items", generateFieldSchema(FieldInfo.of("", itemType)));
                break;
            }
            case MAP: {
                final TypeSignature valueType =
                        ((MapTypeSignature) typeSignature).valueTypeSignature();
                fieldNode.set("additionalProperties",
                              generateFieldSchema(FieldInfo.of("", valueType)));
                break;
            }
            default:
                break;
        }
        return fieldNode;
    }

    private void generateProperties(List<FieldInfo> fields, Map<TypeSignature, String> visited,
                                    String path, ObjectNode parent) {
        final ObjectNode propertiesNode = mapper.createObjectNode();
        final ArrayNode requiredNode = mapper.createArrayNode();

        for (final FieldInfo field : fields) {
            generateFieldSchemaInline(field, visited, path + "/properties", propertiesNode, requiredNode);
        }

        if (!propertiesNode.isEmpty()) {
            parent.set("properties", propertiesNode);
        }
        if (!requiredNode.isEmpty()) {
            parent.set("required", requiredNode);
        }
    }

    private void generateFieldSchemaInline(FieldInfo field, Map<TypeSignature, String> visited,
                                           String path, ObjectNode parent, @Nullable ArrayNode required) {
        final ObjectNode fieldNode = mapper.createObjectNode();
        final TypeSignature typeSignature = field.typeSignature();
        final String docString = field.descriptionInfo().docString();
        if (!docString.isEmpty()) {
            fieldNode.put("description", docString);
        }

        if (required != null && field.requirement() == FieldRequirement.REQUIRED) {
            required.add(field.name());
        }

        if (visited.containsKey(typeSignature)) {
            fieldNode.put("$ref", visited.get(typeSignature));
            parent.set(field.name(), fieldNode);
            return;
        }
        if (typeSignature.type() == TypeSignatureType.OPTIONAL ||
            typeSignature.type() == TypeSignatureType.CONTAINER) {
            final TypeSignature inner =
                    ((ContainerTypeSignature) typeSignature).typeParameters().get(0);
            generateFieldSchemaInline(FieldInfo.of(field.name(), inner), visited, path, parent, required);
            return;
        }

        final String currentPath = path + '/' + field.name();
        final String schemaType = getSchemaType(typeSignature);
        fieldNode.put("type", schemaType);

        if ("object".equals(schemaType) || "array".equals(schemaType)) {
            visited.put(typeSignature, currentPath);
        }

        switch (typeSignature.type()) {
            case ENUM: {
                final EnumInfo enumInfo = enums.get(typeSignature.name());
                if (enumInfo != null) {
                    final ArrayNode enumValues = mapper.createArrayNode();
                    enumInfo.values().forEach(value -> enumValues.add(value.name()));
                    fieldNode.set("enum", enumValues);
                }
                break;
            }
            case STRUCT: {
                final StructInfo structInfo = structs.get(typeSignature.signature());
                if (structInfo == null) {
                    logger.warn("Unknown struct signature: {}", typeSignature.signature());
                    fieldNode.put("additionalProperties", true);
                } else {
                    fieldNode.put("additionalProperties", false);
                    if (!structInfo.fields().isEmpty()) {
                        generateProperties(structInfo.fields(), visited, currentPath, fieldNode);
                    }
                }
                break;
            }
            case ITERABLE: {
                final TypeSignature itemType =
                        ((ContainerTypeSignature) typeSignature).typeParameters().get(0);
                final ObjectNode itemsNode = mapper.createObjectNode();
                generateFieldSchemaInline(FieldInfo.of("", itemType), visited, path + "/items", itemsNode,
                                          null);
                if (itemsNode.has("")) {
                    fieldNode.set("items", itemsNode.get(""));
                }
                break;
            }
            case MAP: {
                final TypeSignature valueType =
                        ((MapTypeSignature) typeSignature).valueTypeSignature();
                final ObjectNode additionalPropertiesNode = mapper.createObjectNode();
                generateFieldSchemaInline(FieldInfo.of("", valueType), visited,
                                          path + "/additionalProperties", additionalPropertiesNode, null);
                if (additionalPropertiesNode.has("")) {
                    fieldNode.set("additionalProperties", additionalPropertiesNode.get(""));
                }
                break;
            }
            default:
                break;
        }
        parent.set(field.name(), fieldNode);
    }
}
