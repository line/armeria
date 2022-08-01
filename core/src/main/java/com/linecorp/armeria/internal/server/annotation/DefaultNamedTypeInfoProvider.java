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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.armeria.internal.server.annotation.AnnotatedDocServicePlugin.toTypeSignature;
import static com.linecorp.armeria.internal.server.annotation.AnnotatedValueResolver.isAnnotatedNullable;
import static java.util.Objects.requireNonNull;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

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
import com.linecorp.armeria.server.annotation.Description;
import com.linecorp.armeria.server.docs.DescriptionInfo;
import com.linecorp.armeria.server.docs.EnumInfo;
import com.linecorp.armeria.server.docs.EnumValueInfo;
import com.linecorp.armeria.server.docs.FieldInfo;
import com.linecorp.armeria.server.docs.FieldRequirement;
import com.linecorp.armeria.server.docs.NamedTypeInfo;
import com.linecorp.armeria.server.docs.NamedTypeInfoProvider;
import com.linecorp.armeria.server.docs.StructInfo;
import com.linecorp.armeria.server.docs.TypeSignature;

/**
 * A default {@link NamedTypeInfoProvider} to create a {@link StructInfo} from a {@code typeDescriptor}.
 * If {@code typeDescriptor} is unknown type, Jackson is used to try to extract fields
 * and their metadata.
 */
public final class DefaultNamedTypeInfoProvider implements NamedTypeInfoProvider {

    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();
    private static final SerializerProvider serializerProvider = mapper.getSerializerProviderInstance();

    private static final StructInfo HTTP_RESPONSE_INFO =
            new StructInfo(HttpResponse.class.getName(), ImmutableList.of());

    private final boolean request;

    DefaultNamedTypeInfoProvider(boolean request) {
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
            return newEnumInfo(clazz);
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

    private static EnumInfo newEnumInfo(Class<?> enumClass) {
        final String name = enumClass.getName();

        final Field[] declaredFields = enumClass.getDeclaredFields();
        final List<EnumValueInfo> values =
                Stream.of(declaredFields)
                      .filter(Field::isEnumConstant)
                      .map(f -> {
                          final Description valueDescription = AnnotationUtil.findFirst(f, Description.class);
                          if (valueDescription != null) {
                              return new EnumValueInfo(f.getName(), null,
                                                       DescriptionInfo.from(valueDescription));
                          }

                          return new EnumValueInfo(f.getName(), null);
                      })
                      .collect(toImmutableList());

        return new EnumInfo(name, values, classDescriptionInfo(enumClass));
    }

    @Nullable
    private StructInfo requestStructInfo(Class<?> type) {
        final JavaType javaType = mapper.constructType(type);
        if (!mapper.canDeserialize(javaType)) {
            return null;
        }
        final Set<JavaType> visiting = new HashSet<>();
        return new StructInfo(type.getName(), requestFieldInfos(javaType, visiting),
                              classDescriptionInfo(javaType.getRawClass()));
    }

    private List<FieldInfo> requestFieldInfos(JavaType javaType, Set<JavaType> visiting) {
        if (!mapper.canDeserialize(javaType)) {
            return ImmutableList.of();
        }

        if (!visiting.add(javaType)) {
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
    private StructInfo responseStructInfo(Class<?> type) {
        if (!mapper.canSerialize(type)) {
            return null;
        }
        final JavaType javaType = mapper.constructType(type);
        final Set<JavaType> visiting = new HashSet<>();
        return new StructInfo(type.getName(), responseFieldInfos(javaType, visiting),
                              classDescriptionInfo(type));
    }

    private List<FieldInfo> responseFieldInfos(JavaType javaType, Set<JavaType> visiting) {
        if (!mapper.canSerialize(javaType.getRawClass())) {
            return ImmutableList.of();
        }

        if (!visiting.add(javaType)) {
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

    private FieldInfo fieldInfos(JavaType javaType, String name, JavaType fieldType,
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

        final DescriptionInfo descriptionInfo = fieldDescriptionInfo(javaType, fieldType, name);
        if (typeSignature.isBase() || typeSignature.isContainer()) {
            return FieldInfo.builder(name, typeSignature)
                            .requirement(fieldRequirement)
                            .descriptionInfo(descriptionInfo)
                            .build();
        } else {
            final List<FieldInfo> fieldInfos = childFieldsResolver.apply(fieldType);
            if (fieldInfos.isEmpty()) {
                return FieldInfo.builder(name, typeSignature)
                                .requirement(fieldRequirement)
                                .descriptionInfo(descriptionInfo)
                                .build();
            } else {
                return FieldInfo.builder(name, typeSignature, fieldInfos)
                                .requirement(fieldRequirement)
                                .descriptionInfo(descriptionInfo)
                                .build();
            }
        }
    }

    private FieldRequirement fieldRequirement(JavaType classType, JavaType fieldType, String fieldName) {
        if (fieldType.isPrimitive()) {
            return FieldRequirement.REQUIRED;
        }

        if (KotlinUtil.isData(classType.getRawClass())) {
            // Only the parameters in the constructor of data classes provides `isMarkedNullable` information.
            final FieldRequirement requirement =
                    extractFromConstructor(classType, fieldType, fieldName, parameter -> {
                        if (isNullable(parameter)) {
                            return FieldRequirement.OPTIONAL;
                        } else {
                            return FieldRequirement.REQUIRED;
                        }
                    });
            if (requirement != null) {
                return requirement;
            }
        }

        final FieldRequirement requirement =
                extractFieldMeta(classType, fieldType, fieldName, element -> {
                    if (request) {
                        if (element instanceof Method) {
                            // Use the first parameter information for the setter method
                            element = ((Method) element).getParameters()[0];
                        }
                    }
                    if (isNullable(element)) {
                        return FieldRequirement.OPTIONAL;
                    }
                    return FieldRequirement.REQUIRED;
                });

        return firstNonNull(requirement, FieldRequirement.UNSPECIFIED);
    }

    private static boolean isNullable(AnnotatedElement element) {
        return isAnnotatedNullable(element) || KotlinUtil.isMarkedNullable(element);
    }

    private static DescriptionInfo classDescriptionInfo(Class<?> clazz) {
        final Description description = AnnotationUtil.findFirst(clazz, Description.class);
        if (description != null) {
            return DescriptionInfo.from(description);
        } else {
            return DescriptionInfo.empty();
        }
    }

    private DescriptionInfo fieldDescriptionInfo(JavaType classType, JavaType fieldType, String fieldName) {
        final Description description = extractFieldMeta(classType, fieldType, fieldName, element -> {
            return AnnotationUtil.findFirst(element, Description.class);
        });

        if (description != null) {
            return DescriptionInfo.from(description);
        } else {
            return DescriptionInfo.empty();
        }
    }

    @Nullable
    private <T> T extractFieldMeta(JavaType classType, JavaType fieldType, String fieldName,
                                   Function<AnnotatedElement, @Nullable T> extractor) {
        if (request) {
            return extractRequestFieldMeta(classType, fieldType, fieldName, extractor);
        } else {
            return extractResponseFieldMeta(classType, fieldType, fieldName, extractor);
        }
    }

    @Nullable
    private static <T> T extractRequestFieldMeta(JavaType classType, JavaType fieldType, String fieldName,
                                                 Function<AnnotatedElement, @Nullable T> extractor) {
        // Setter: setFieldName(field)
        final String setter = "set" + CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, fieldName);
        T result = extractFromSetter(classType, fieldType, setter, extractor);

        if (result == null) {
            // Setter: fieldName(field)
            result = extractFromSetter(classType, fieldType, fieldName, extractor);
        }

        if (result == null) {
            result = extractFromConstructor(classType, fieldType, fieldName, extractor);
        }

        if (result == null) {
            result = extractFromField(classType, fieldType, fieldName, extractor);
        }

        return result;
    }

    @Nullable
    private static <T> T extractResponseFieldMeta(JavaType classType, JavaType fieldType, String fieldName,
                                                  Function<AnnotatedElement, @Nullable T> extractor) {
        // Getter: Field getFieldName()
        final String getter = "get" + CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, fieldName);
        T result = extractFromGetter(classType, fieldType, getter, extractor);

        if (result == null) {
            // Getter: Field fieldName()
            result = extractFromGetter(classType, fieldType, fieldName, extractor);
        }

        if (result == null) {
            result = extractFromField(classType, fieldType, fieldName, extractor);
        }

        if (result == null) {
            result = extractFromConstructor(classType, fieldType, fieldName, extractor);
        }
        return result;
    }

    @Nullable
    private static <T> T extractFromField(JavaType classType, JavaType fieldType, String fieldName,
                                          Function<AnnotatedElement, @Nullable T> extractor) {
        try {
            final Field field = classType.getRawClass().getDeclaredField(fieldName);
            if (field.getType() == fieldType.getRawClass()) {
                return extractor.apply(field);
            }
        } catch (NoSuchFieldException ignored) {
        }
        return null;
    }

    @Nullable
    private static <T> T extractFromConstructor(JavaType classType, JavaType fieldType, String fieldName,
                                                Function<AnnotatedElement, @Nullable T> extractor) {
        final Constructor<?>[] ctors = classType.getRawClass().getDeclaredConstructors();
        if (ctors.length == 0) {
            return null;
        }
        Constructor<?> constructor = null;
        for (Constructor<?> ctor : ctors) {
            final Parameter[] parameters = ctor.getParameters();
            final int length = parameters.length;
            if (length == 0) {
                continue;
            }
            if ("kotlin.jvm.internal.DefaultConstructorMarker".equals(
                    parameters[length - 1].getType().getName())) {
                // Ignore an additional constructor generated by Kotlin compiler which is added when a
                // default value is defined in the constructor.
                continue;
            }
            if (constructor == null || constructor.getParameters().length < length) {
                constructor = ctor;
            }
        }
        if (constructor == null) {
            return null;
        }

        final Parameter[] parameters = constructor.getParameters();
        for (final Parameter parameter : parameters) {
            if (parameter.isNamePresent() &&
                parameter.getName().equals(fieldName) &&
                parameter.getType() ==
                fieldType.getRawClass()) {
                return extractor.apply(parameter);
            }
        }
        return null;
    }

    @Nullable
    private static <T> T extractFromSetter(JavaType classType, JavaType fieldType, String methodName,
                                           Function<AnnotatedElement, @Nullable T> extractor) {
        try {
            final Method method = classType.getRawClass()
                                           .getDeclaredMethod(methodName, fieldType.getRawClass());
            return extractor.apply(method);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    @Nullable
    private static <T> T extractFromGetter(JavaType classType, JavaType fieldType, String methodName,
                                           Function<AnnotatedElement, @Nullable T> extractor) {
        try {
            final Method method = classType.getRawClass()
                                           .getDeclaredMethod(methodName);
            if (method.getReturnType() == fieldType.getRawClass()) {
                return extractor.apply(method);
            }
        } catch (NoSuchMethodException ignored) {
        }
        return null;
    }

    private static boolean isOptional(TypeSignature typeSignature) {
        return typeSignature.isContainer() && "optional".equals(typeSignature.name());
    }
}
