/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.internal.server.docs;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.armeria.internal.server.docs.DocServiceTypeUtil.toTypeSignature;
import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.server.annotation.Description;
import com.linecorp.armeria.server.docs.DescriptionInfo;
import com.linecorp.armeria.server.docs.DescriptiveTypeInfo;
import com.linecorp.armeria.server.docs.DescriptiveTypeInfoProvider;
import com.linecorp.armeria.server.docs.DiscriminatorInfo;
import com.linecorp.armeria.server.docs.FieldInfo;
import com.linecorp.armeria.server.docs.StructInfo;
import com.linecorp.armeria.server.docs.TypeSignature;

/**
 * A {@link DescriptiveTypeInfoProvider} that provides
 * {@link DescriptiveTypeInfo} for a polymorphic
 * type by inspecting Jackson annotations such as {@link JsonTypeInfo} and
 * {@link JsonSubTypes}.
 */
public final class JacksonPolymorphismTypeInfoProvider implements DescriptiveTypeInfoProvider {

    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    /**
     * Creates a new {@link StructInfo} for the specified {@code typeDescriptor} if
     * it is a polymorphic
     * base type annotated with {@link JsonTypeInfo} and {@link JsonSubTypes}.
     * The generated {@link StructInfo} will contain {@link StructInfo#oneOf()} and
     * {@link StructInfo#discriminator()} metadata.
     *
     * @param typeDescriptor the {@link Class} to be inspected.
     * @return a new {@link StructInfo} with polymorphism metadata, or {@code null}
     *         if the
     *         {@code typeDescriptor} is not a supported polymorphic type.
     */
    @Override
    @Nullable
    public DescriptiveTypeInfo newDescriptiveTypeInfo(Object typeDescriptor) {
        requireNonNull(typeDescriptor, "typeDescriptor");
        if (!(typeDescriptor instanceof Class)) {
            return null;
        }

        final Class<?> clazz = (Class<?>) typeDescriptor;
        final JsonTypeInfo jsonTypeInfo = clazz.getAnnotation(JsonTypeInfo.class);
        final JsonSubTypes jsonSubTypes = clazz.getAnnotation(JsonSubTypes.class);

        if (jsonTypeInfo == null || jsonSubTypes == null) {
            return null;
        }

        final String propertyName = jsonTypeInfo.property();
        if (propertyName.isEmpty()) {
            return null;
        }

        if (jsonSubTypes.value().length == 0) {
            return null;
        }

        final Map<String, String> mapping = new LinkedHashMap<>();
        Arrays.stream(jsonSubTypes.value()).forEach(subType -> {
            final Class<?> subClass = subType.value();
            final String key = isNullOrEmpty(subType.name()) ? subClass.getSimpleName() : subType.name();
            final String schemaName = TypeSignature.ofStruct(subClass).name();
            mapping.put(key, "#/$defs/models/" + schemaName);
        });

        final DiscriminatorInfo discriminator = DiscriminatorInfo.of(propertyName, mapping);

        final List<TypeSignature> oneOf = Arrays.stream(jsonSubTypes.value())
                                                .map(subType -> TypeSignature.ofStruct(subType.value()))
                                                .collect(toImmutableList());

        final JavaType javaType = mapper.constructType(clazz);
        final BeanDescription description = mapper.getSerializationConfig().introspect(javaType);
        final List<BeanPropertyDefinition> properties = description.findProperties();

        final List<FieldInfo> fields = properties.stream()
                                                 .map(prop -> FieldInfo.of(prop.getName(),
                                                                           toTypeSignature(
                                                                                   prop.getPrimaryType())))
                                                 .collect(toImmutableList());

        final Description classDescription = clazz.getAnnotation(Description.class);

        final DescriptionInfo descriptionInfo = classDescription == null ? DescriptionInfo.empty()
                                                                         : DescriptionInfo.from(
                classDescription);

        return new StructInfo(clazz.getName(), null, fields, descriptionInfo, oneOf, discriminator);
    }
}
