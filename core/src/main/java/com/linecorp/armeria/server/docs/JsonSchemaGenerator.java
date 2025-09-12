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
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableMap;

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
    private final Map<String, DiscriminatorInfo> polymorphismToBase;

    private JsonSchemaGenerator(ServiceSpecification serviceSpecification) {
        this.serviceSpecification = requireNonNull(serviceSpecification, "serviceSpecification");

        final ImmutableMap.Builder<String, StructInfo> structsBuilder =
                ImmutableMap.builderWithExpectedSize(serviceSpecification.structs().size());
        for (final StructInfo structInfo : serviceSpecification.structs()) {
            structsBuilder.put(structInfo.name(), structInfo);
            if (structInfo.alias() != null) {
                structsBuilder.put(structInfo.alias(), structInfo);
            }
        }
        structs = structsBuilder.build();

        enums = serviceSpecification.enums().stream()
                                    .collect(toImmutableMap(EnumInfo::name, Function.identity()));

        // Pre-compute mappings from subtype to its base type's DiscriminatorInfo
        polymorphismToBase = new HashMap<>();
        for (final StructInfo structInfo : serviceSpecification.structs()) {
            if (structInfo.discriminator() != null && !structInfo.oneOf().isEmpty()) {
                for (TypeSignature subType : structInfo.oneOf()) {
                    polymorphismToBase.put(subType.name(), structInfo.discriminator());
                }
            }
        }
    }

    // Public static entry point
    static ObjectNode generate(ServiceSpecification serviceSpecification) {
        return new JsonSchemaGenerator(serviceSpecification).doGenerate();
    }

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
                final TypeSignature inner =
                        ((ContainerTypeSignature) typeSignature).typeParameters().get(0);
                return getSchemaType(inner);
            }
            default:
                break;
        }

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

    private ObjectNode doGenerate() {
        final ObjectNode root = mapper.createObjectNode();
        final ServiceInfo representativeService = serviceSpecification.services().iterator().next();
        // Use a representative service name for the title and ID for now.
        final String serviceName = representativeService.name();

        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.put("$id", serviceName);
        root.put("title", serviceName);

        final ObjectNode defs = root.putObject("$defs");
        defs.set("models", generateModels());
        defs.set("methods", generateMethods());

        return root;
    }

    private ObjectNode generateModels() {
        final ObjectNode modelsNode = mapper.createObjectNode();
        for (final StructInfo structInfo : serviceSpecification.structs()) {
            modelsNode.set(structInfo.name(), generateStructDefinition(structInfo));
        }
        for (final EnumInfo enumInfo : serviceSpecification.enums()) {
            modelsNode.set(enumInfo.name(), generateEnumDefinition(enumInfo));
        }
        return modelsNode;
    }

    private ObjectNode generateMethods() {
        final ObjectNode methodsNode = mapper.createObjectNode();
        for (final ServiceInfo svc : serviceSpecification.services()) {
            for (final MethodInfo m : svc.methods()) {
                // To avoid potential name collision, we can use a more unique key like method id.
                // For now, using method name as requested.
                methodsNode.set(m.name(), generateMethodSchema(m));
            }
        }
        return methodsNode;
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
                ref.put("$ref", "#/$defs/models/" + sub.name());
                oneOfNode.add(ref);
            });

            final DiscriminatorInfo discriminator = structInfo.discriminator();
            if (discriminator != null) {
                final ObjectNode disc = schemaNode.putObject("discriminator");
                disc.put("propertyName", discriminator.propertyName());
                if (!discriminator.mapping().isEmpty()) {
                    final ObjectNode mapping = disc.putObject("mapping");
                    // Update mapping paths
                    discriminator.mapping().forEach((key, value) -> {
                        final String newPath = value.replace("#/definitions/", "#/$defs/models/");
                        mapping.put(key, newPath);
                    });
                }
            }
            return schemaNode;
        }

        final ObjectNode props = mapper.createObjectNode();
        final ArrayNode required = mapper.createArrayNode();

        // Check if this struct is a subtype and add the discriminator property
        final DiscriminatorInfo discriminatorInfo = polymorphismToBase.get(structInfo.name());
        if (discriminatorInfo != null) {
            final ObjectNode propertySchema = props.putObject(discriminatorInfo.propertyName());
            propertySchema.put("type", "string");
        }

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
            // Filter out discriminator property from required list as it's often not in the constructor
            final List<String> requiredFields = structInfo.fields().stream()
                                                          .filter(f -> f.requirement() ==
                                                                       FieldRequirement.REQUIRED)
                                                          .map(FieldInfo::name)
                                                          .collect(Collectors.toList());

            if (discriminatorInfo != null) {
                requiredFields.add(discriminatorInfo.propertyName());
            }
            if (!requiredFields.isEmpty()) {
                final ArrayNode requiredNode = mapper.createArrayNode();
                requiredFields.forEach(requiredNode::add);
                schemaNode.set("required", requiredNode);
            }
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

    private ObjectNode generateMethodSchema(MethodInfo methodInfo) {
        final ObjectNode root = mapper.createObjectNode();
        root.put("$id", methodInfo.id());
        root.put("title", methodInfo.name());
        final String docString = methodInfo.descriptionInfo().docString();
        if (!docString.isEmpty()) {
            root.put("description", docString);
        }

        root.put("additionalProperties", false);
        root.put("type", "object");

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
            fieldNode.put("$ref", "#/$defs/models/" + typeSignature.name());
            return fieldNode;
        }

        if (typeSignature.type() == TypeSignatureType.OPTIONAL ||
            typeSignature.type() == TypeSignatureType.CONTAINER) {
            final TypeSignature inner =
                    ((ContainerTypeSignature) typeSignature).typeParameters().get(0);
            return generateFieldSchema(FieldInfo.of("", inner));
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
}
