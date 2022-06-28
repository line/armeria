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

package com.linecorp.armeria.internal.server.annotation;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.armeria.internal.server.annotation.AnnotatedDocServicePlugin.toTypeSignature;
import static com.linecorp.armeria.internal.server.annotation.AnnotatedValueResolver.isAnnotatedNullable;
import static java.util.Objects.requireNonNull;

import java.lang.reflect.AnnotatedElement;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.PropertyWriter;
import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.server.docs.EnumInfo;
import com.linecorp.armeria.server.docs.FieldInfo;
import com.linecorp.armeria.server.docs.FieldRequirement;
import com.linecorp.armeria.server.docs.NamedTypeInfo;
import com.linecorp.armeria.server.docs.NamedTypeInfoProvider;
import com.linecorp.armeria.server.docs.StructInfo;
import com.linecorp.armeria.server.docs.TypeSignature;

/**
 * A {@link NamedTypeInfoProvider} to create a {@link StructInfo} from a JSON response object.
 */
final class JsonNamedTypeInfoProvider implements NamedTypeInfoProvider {

    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();
    private static final SerializerProvider serializerProvider = mapper.getSerializerProviderInstance();

    private static final StructInfo HTTP_RESPONSE_INFO =
            new StructInfo(HttpResponse.class.getName(), ImmutableList.of());

    private final boolean request;

    JsonNamedTypeInfoProvider(boolean request) {
        this.request = request;
    }

    @Nullable
    @Override
    public NamedTypeInfo newNamedTypeInfo(Object typeDescriptor) {
        requireNonNull(typeDescriptor, "typeDescriptor");
        if (!(typeDescriptor instanceof Class)) {
            return null;
        }
        final Class<?> clazz = (Class<?>) typeDescriptor;
        if (clazz.isEnum()) {
            @SuppressWarnings("unchecked")
            final Class<? extends Enum<?>> enumType = (Class<? extends Enum<?>>) clazz;
            return new EnumInfo(enumType);
        }

        if (HttpResponse.class.isAssignableFrom(clazz)) {
            return HTTP_RESPONSE_INFO;
        }

        if (request) {
            return requestStructInfo(clazz);
        } else {
            return responseStructInfo(clazz);
        }
    }

    @Nullable
    private static StructInfo requestStructInfo(Class<?> type) {
        final JavaType javaType = mapper.constructType(type);
        if (!mapper.canDeserialize(javaType)) {
            return null;
        }
        final Set<JavaType> visiting = new HashSet<>();
        return new StructInfo(type.getName(), requestFieldInfos(javaType, visiting));
    }

    private static List<FieldInfo> requestFieldInfos(JavaType javaType, Set<JavaType> visiting) {
        if (!visiting.add(javaType)) {
            return ImmutableList.of();
        }
        if (!mapper.canDeserialize(javaType)) {
            visiting.remove(javaType);
            return ImmutableList.of();
        }

        final BeanDescription description = mapper.getDeserializationConfig().introspect(javaType);
        final List<FieldInfo> fieldInfos = description.findProperties().stream().map(property -> {
            return fieldInfos(javaType,
                              property.getName(),
                              property.getPrimaryType(),
                              childType -> requestFieldInfos(childType, visiting));
        }).collect(toImmutableList());
        visiting.remove(javaType);
        return fieldInfos;
    }

    @Nullable
    private static StructInfo responseStructInfo(Class<?> type) {
        if (!mapper.canSerialize(type)) {
            return null;
        }
        final JavaType javaType = mapper.constructType(type);
        final Set<JavaType> visiting = new HashSet<>();
        return new StructInfo(type.getName(), responseFieldInfos(javaType, visiting));
    }

    private static List<FieldInfo> responseFieldInfos(JavaType javaType, Set<JavaType> visiting) {
        if (!visiting.add(javaType)) {
            return ImmutableList.of();
        }
        if (!mapper.canSerialize(javaType.getRawClass())) {
            visiting.remove(javaType);
            return ImmutableList.of();
        }

        try {
            final JsonSerializer<Object> serializer = serializerProvider.findValueSerializer(javaType);
            final Iterator<PropertyWriter> logicalProperties = serializer.properties();
            return Streams.stream(logicalProperties).map(propertyWriter -> {
                return fieldInfos(javaType,
                                  propertyWriter.getName(),
                                  propertyWriter.getType(),
                                  childType -> responseFieldInfos(childType, visiting));
            }).collect(toImmutableList());
        } catch (JsonMappingException e) {
            return ImmutableList.of();
        } finally {
            visiting.remove(javaType);
        }
    }

    private static FieldInfo fieldInfos(JavaType javaType, String name, JavaType fieldType,
                                        Function<JavaType, List<FieldInfo>> childFieldsResolver) {
        TypeSignature typeSignature = toTypeSignature(fieldType);
        final FieldRequirement fieldRequirement;
        if (isOptional(typeSignature)) {
            typeSignature = typeSignature.typeParameters().get(0);
            if (typeSignature.namedTypeDescriptor() instanceof Class) {
                //noinspection OverlyStrongTypeCast
                fieldType = mapper.constructType((Class<?>) typeSignature.namedTypeDescriptor());
            }
            fieldRequirement = FieldRequirement.OPTIONAL;
        } else {
            fieldRequirement = fieldRequirement(javaType, fieldType, name);
        }

        // TODO(ikhoon): Get `docString` from `@Description` if exists
        if (typeSignature.isBase() || typeSignature.isContainer()) {
            return FieldInfo.builder(name, typeSignature)
                            .requirement(fieldRequirement)
                            .build();
        } else {
            final List<FieldInfo> fieldInfos = childFieldsResolver.apply(fieldType);
            if (fieldInfos.isEmpty()) {
                return FieldInfo.builder(name, typeSignature)
                                .requirement(fieldRequirement)
                                .build();
            } else {
                return FieldInfo.builder(name, typeSignature, fieldInfos)
                                .requirement(fieldRequirement)
                                .build();
            }
        }
    }

    private static FieldRequirement fieldRequirement(JavaType classType, JavaType fieldType, String fieldName) {
        if (fieldType.isPrimitive()) {
            return FieldRequirement.REQUIRED;
        }

        final Class<?> rawClass = classType.getRawClass();
        AnnotatedElement typeElement = null;
        try {
            typeElement = rawClass.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            try {
                final String getter = "get" + CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, fieldName);
                typeElement = rawClass.getDeclaredMethod(getter).getAnnotatedReturnType();
            } catch (NoSuchMethodException ex) {
                try {
                    typeElement = rawClass.getDeclaredMethod(fieldName).getAnnotatedReturnType();
                } catch (NoSuchMethodException ignored) {
                }
            }
        }

        if (typeElement == null) {
            return FieldRequirement.UNSPECIFIED;
        }
        if (isAnnotatedNullable(typeElement) || KotlinUtil.isMarkedNullable(typeElement)) {
            return FieldRequirement.OPTIONAL;
        }
        return FieldRequirement.REQUIRED;
    }

    private static boolean isOptional(TypeSignature typeSignature) {
        return typeSignature.isContainer() && "optional".equals(typeSignature.name());
    }
}
