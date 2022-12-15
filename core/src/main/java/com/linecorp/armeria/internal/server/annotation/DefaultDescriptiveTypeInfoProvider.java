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
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.server.annotation.Description;
import com.linecorp.armeria.server.docs.ContainerTypeSignature;
import com.linecorp.armeria.server.docs.DescriptionInfo;
import com.linecorp.armeria.server.docs.DescriptiveTypeInfo;
import com.linecorp.armeria.server.docs.DescriptiveTypeInfoProvider;
import com.linecorp.armeria.server.docs.DescriptiveTypeSignature;
import com.linecorp.armeria.server.docs.EnumInfo;
import com.linecorp.armeria.server.docs.EnumValueInfo;
import com.linecorp.armeria.server.docs.FieldInfo;
import com.linecorp.armeria.server.docs.FieldRequirement;
import com.linecorp.armeria.server.docs.StructInfo;
import com.linecorp.armeria.server.docs.TypeSignature;
import com.linecorp.armeria.server.docs.TypeSignatureType;

/**
 * A default {@link DescriptiveTypeInfoProvider} to create a {@link StructInfo} from a {@code typeDescriptor}.
 * If {@code typeDescriptor} is unknown type, Jackson is used to try to extract fields
 * and their metadata.
 */
public final class DefaultDescriptiveTypeInfoProvider implements DescriptiveTypeInfoProvider {

    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    private static final StructInfo HTTP_RESPONSE_INFO =
            new StructInfo(HttpResponse.class.getName(), ImmutableList.of());

    private final boolean request;

    DefaultDescriptiveTypeInfoProvider(boolean request) {
        this.request = request;
    }

    @Nullable
    @Override
    public DescriptiveTypeInfo newDescriptiveTypeInfo(Object typeDescriptor) {
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

    private StructInfo requestStructInfo(Class<?> type) {
        final JavaType javaType = mapper.constructType(type);
        if (!mapper.canDeserialize(javaType)) {
            return newReflectiveStructInfo(type);
        }
        return new StructInfo(type.getName(), requestFieldInfos(javaType),
                              classDescriptionInfo(javaType.getRawClass()));
    }

    private List<FieldInfo> requestFieldInfos(JavaType javaType) {
        if (!mapper.canDeserialize(javaType)) {
            return ImmutableList.of();
        }

        final BeanDescription description = mapper.getDeserializationConfig().introspect(javaType);
        final List<BeanPropertyDefinition> properties = description.findProperties();
        if (properties.isEmpty()) {
            return newReflectiveStructInfo(javaType.getRawClass()).fields();
        }

        return properties.stream()
                         .map(property -> fieldInfos(javaType,
                                                     property.getName(),
                                                     property.getInternalName(),
                                                     property.getPrimaryType()))
                         .collect(toImmutableList());
    }

    private StructInfo responseStructInfo(Class<?> type) {
        if (!mapper.canSerialize(type)) {
            return newReflectiveStructInfo(type);
        }
        final JavaType javaType = mapper.constructType(type);
        return new StructInfo(type.getName(), responseFieldInfos(javaType), classDescriptionInfo(type));
    }

    private List<FieldInfo> responseFieldInfos(JavaType javaType) {
        if (!mapper.canSerialize(javaType.getRawClass())) {
            return ImmutableList.of();
        }

        final BeanDescription description = mapper.getSerializationConfig().introspect(javaType);
        final List<BeanPropertyDefinition> properties = description.findProperties();
        if (properties.isEmpty()) {
            return newReflectiveStructInfo(javaType.getRawClass()).fields();
        }

        return properties.stream()
                         .map(property -> fieldInfos(javaType,
                                                     property.getName(),
                                                     property.getInternalName(),
                                                     property.getPrimaryType()))
                         .collect(toImmutableList());
    }

    private FieldInfo fieldInfos(JavaType javaType, String name, String internalName, JavaType fieldType) {
        TypeSignature typeSignature = toTypeSignature(fieldType);
        final FieldRequirement fieldRequirement;
        if (typeSignature.type() == TypeSignatureType.OPTIONAL) {
            typeSignature = ((ContainerTypeSignature) typeSignature).typeParameters().get(0);
            if (typeSignature.type().hasTypeDescriptor()) {
                final Object descriptor =
                        ((DescriptiveTypeSignature) typeSignature).descriptor();
                if (descriptor instanceof Class) {
                    fieldType = mapper.constructType((Class<?>) descriptor);
                }
            }
            fieldRequirement = FieldRequirement.OPTIONAL;
        } else {
            fieldRequirement = fieldRequirement(javaType, fieldType, internalName);
        }

        final DescriptionInfo descriptionInfo = fieldDescriptionInfo(javaType, fieldType, internalName);
        return FieldInfo.builder(name, typeSignature)
                        .requirement(fieldRequirement)
                        .descriptionInfo(descriptionInfo)
                        .build();
    }

    private FieldRequirement fieldRequirement(JavaType classType, JavaType fieldType, String fieldName) {
        if (fieldType.isPrimitive()) {
            return FieldRequirement.REQUIRED;
        }

        if (KotlinUtil.isData(classType.getRawClass())) {
            // Only the parameters in the constructor of data classes correctly provide
            // `isMarkedNullable` information.
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

    static boolean isNullable(AnnotatedElement element) {
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
        // There are no standard rules to get properties of a request object. But we might assume that a request
        // object is a settable object. Before directly accessing private fields, try the patterns that are
        // commonly used in settable objects.
        //
        // - Java POJO style setters such as `void setName(String name)`.
        // - Non-standard setters such as `void name(String name)`.
        // - A single constructor.
        // - Private fields as a last resort.

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
        // There are no standard rules to get properties of a response object. But we might assume that a
        // response object is a gettable object. Before directly accessing private fields, try the patterns that
        // are commonly used in gettable objects. Although the constructor has the characteristics of setters,
        // it is added just in case.
        //
        // - Java POJO style getters such as `String getName()`.
        // - Non-standard getters such as `String name()`.
        // - Private fields.
        // - A single constructor as a last resort.

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
            for (Field field : classType.getRawClass().getDeclaredFields()) {
                final JsonProperty renameAnnotation = AnnotationUtil.findFirst(field, JsonProperty.class);
                if (renameAnnotation != null &&
                    renameAnnotation.value().equals(fieldName) &&
                    field.getType() == fieldType.getRawClass()) {
                    return extractor.apply(field);
                }
            }
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
            final JsonProperty renameAnnotation = AnnotationUtil.findFirst(parameter, JsonProperty.class);
            if (renameAnnotation != null &&
                renameAnnotation.value().equals(fieldName) &&
                parameter.getType() == fieldType.getRawClass()) {
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

    private static StructInfo newReflectiveStructInfo(Class<?> clazz) {
        return (StructInfo) ReflectiveDescriptiveTypeInfoProvider.INSTANCE.newDescriptiveTypeInfo(clazz);
    }
}
