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

package com.linecorp.armeria.internal.server.thrift;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.thrift.TBase;
import org.apache.thrift.TEnum;
import org.apache.thrift.TException;
import org.apache.thrift.TFieldIdEnum;
import org.apache.thrift.TFieldRequirementType;
import org.apache.thrift.meta_data.EnumMetaData;
import org.apache.thrift.meta_data.FieldMetaData;
import org.apache.thrift.meta_data.FieldValueMetaData;
import org.apache.thrift.meta_data.ListMetaData;
import org.apache.thrift.meta_data.MapMetaData;
import org.apache.thrift.meta_data.SetMetaData;
import org.apache.thrift.meta_data.StructMetaData;
import org.apache.thrift.protocol.TType;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.thrift.ThriftMetadataAccess;
import com.linecorp.armeria.server.docs.DescriptiveTypeInfo;
import com.linecorp.armeria.server.docs.DescriptiveTypeInfoProvider;
import com.linecorp.armeria.server.docs.EnumInfo;
import com.linecorp.armeria.server.docs.EnumValueInfo;
import com.linecorp.armeria.server.docs.ExceptionInfo;
import com.linecorp.armeria.server.docs.FieldInfo;
import com.linecorp.armeria.server.docs.FieldRequirement;
import com.linecorp.armeria.server.docs.StructInfo;
import com.linecorp.armeria.server.docs.TypeSignature;

/**
 * A {@link DescriptiveTypeInfoProvider} to create a {@link DescriptiveTypeInfo} from a Thrift type
 * such as {@link TBase} {@link TEnum} or {@link TException}.
 */
public final class ThriftDescriptiveTypeInfoProvider implements DescriptiveTypeInfoProvider {

    static final TypeSignature VOID = TypeSignature.ofBase("void");
    private static final TypeSignature BOOL = TypeSignature.ofBase("bool");
    private static final TypeSignature I8 = TypeSignature.ofBase("i8");
    private static final TypeSignature I16 = TypeSignature.ofBase("i16");
    private static final TypeSignature I32 = TypeSignature.ofBase("i32");
    private static final TypeSignature I64 = TypeSignature.ofBase("i64");
    private static final TypeSignature DOUBLE = TypeSignature.ofBase("double");
    private static final TypeSignature STRING = TypeSignature.ofBase("string");
    private static final TypeSignature BINARY = TypeSignature.ofBase("binary");

    @Nullable
    @Override
    public DescriptiveTypeInfo newDescriptiveTypeInfo(Object typeDescriptor) {
        if (!(typeDescriptor instanceof Class)) {
            return null;
        }

        final Class<?> clazz = (Class<?>) typeDescriptor;
        if (TEnum.class.isAssignableFrom(clazz)) {
            @SuppressWarnings("unchecked")
            final Class<? extends Enum<? extends TEnum>> enumType =
                    (Class<? extends Enum<? extends TEnum>>) clazz;
            return newEnumInfo(enumType);
        }

        if (TException.class.isAssignableFrom(clazz)) {
            @SuppressWarnings("unchecked")
            final Class<? extends TException> castType = (Class<? extends TException>) clazz;
            return newExceptionInfo(castType);
        }

        if (TBase.class.isAssignableFrom(clazz)) {
            @SuppressWarnings("unchecked")
            final Class<? extends TBase<?, ?>> castType = (Class<? extends TBase<?, ?>>) clazz;
            return newStructInfo(castType);
        }

        return null;
    }

    @VisibleForTesting
    static EnumInfo newEnumInfo(Class<? extends Enum<? extends TEnum>> enumType) {
        final List<EnumValueInfo> values = Arrays.stream(enumType.getEnumConstants())
                                                 .map(e -> new EnumValueInfo(e.name(), ((TEnum) e).getValue()))
                                                 .collect(toImmutableList());

        return new EnumInfo(enumType.getTypeName(), values);
    }

    @VisibleForTesting
    static ExceptionInfo newExceptionInfo(Class<? extends TException> exceptionClass) {
        requireNonNull(exceptionClass, "exceptionClass");
        final String name = exceptionClass.getName();

        List<FieldInfo> fields;
        try {
            @SuppressWarnings("unchecked")
            final Map<?, FieldMetaData> metaDataMap =
                    (Map<?, FieldMetaData>) exceptionClass.getDeclaredField("metaDataMap").get(null);

            fields = metaDataMap.values().stream()
                                .map(fieldMetaData -> newFieldInfo(exceptionClass, fieldMetaData))
                                .collect(toImmutableList());
        } catch (IllegalAccessException e) {
            throw new AssertionError("will not happen", e);
        } catch (NoSuchFieldException ignored) {
            fields = Collections.emptyList();
        }

        return new ExceptionInfo(name, fields);
    }

    static FieldInfo newFieldInfo(Class<?> parentType, FieldMetaData fieldMetaData) {
        requireNonNull(fieldMetaData, "fieldMetaData");
        final FieldValueMetaData fieldValueMetaData = fieldMetaData.valueMetaData;
        final TypeSignature typeSignature;

        if (fieldValueMetaData.isStruct() && fieldValueMetaData.isTypedef() &&
            parentType.getSimpleName().equals(fieldValueMetaData.getTypedefName())) {
            // Handle the special case where a struct field refers to itself,
            // where the Thrift compiler handles it as a typedef.
            typeSignature = TypeSignature.ofStruct(parentType);
        } else {
            typeSignature = toTypeSignature(parentType, fieldMetaData, fieldValueMetaData);
        }

        return FieldInfo.builder(fieldMetaData.fieldName, typeSignature)
                        .requirement(convertRequirement(fieldMetaData.requirementType)).build();
    }

    private static FieldRequirement convertRequirement(byte value) {
        switch (value) {
            case TFieldRequirementType.REQUIRED:
                return FieldRequirement.REQUIRED;
            case TFieldRequirementType.OPTIONAL:
                return FieldRequirement.OPTIONAL;
            case TFieldRequirementType.DEFAULT:
                // Convert to unspecified for consistency with gRPC and AnnotatedService.
                return FieldRequirement.UNSPECIFIED;
            default:
                throw new IllegalArgumentException("unknown requirement type: " + value);
        }
    }

    static TypeSignature toTypeSignature(Class<?> parentType, FieldMetaData fieldMetaData,
                                         FieldValueMetaData fieldValueMetaData) {
        if (!fieldValueMetaData.isTypedef() && !hasTypeDef(fieldValueMetaData)) {
            return toTypeSignature(fieldValueMetaData);
        }

        try {
            final Field field = parentType.getField(fieldMetaData.fieldName);
            return toTypeSignature(field.getGenericType());
        } catch (NoSuchFieldException e) {
            // Ignore exception.
        }
        return TypeSignature.ofUnresolved(firstNonNull(fieldValueMetaData.getTypedefName(), "unknown"));
    }

    static TypeSignature toTypeSignature(FieldValueMetaData fieldValueMetaData) {
        if (fieldValueMetaData instanceof StructMetaData) {
            return TypeSignature.ofStruct(((StructMetaData) fieldValueMetaData).structClass);
        }

        if (fieldValueMetaData instanceof EnumMetaData) {
            return TypeSignature.ofEnum(((EnumMetaData) fieldValueMetaData).enumClass);
        }

        if (fieldValueMetaData instanceof ListMetaData) {
            return TypeSignature.ofList(toTypeSignature(((ListMetaData) fieldValueMetaData).elemMetaData));
        }

        if (fieldValueMetaData instanceof SetMetaData) {
            return TypeSignature.ofSet(toTypeSignature(((SetMetaData) fieldValueMetaData).elemMetaData));
        }

        if (fieldValueMetaData instanceof MapMetaData) {
            final MapMetaData mapMetaData = (MapMetaData) fieldValueMetaData;
            return TypeSignature.ofMap(toTypeSignature(mapMetaData.keyMetaData),
                                       toTypeSignature(mapMetaData.valueMetaData));
        }

        if (fieldValueMetaData.isBinary()) {
            return BINARY;
        }

        switch (fieldValueMetaData.type) {
            case TType.VOID:
                return VOID;
            case TType.BOOL:
                return BOOL;
            case TType.BYTE:
                return I8;
            case TType.DOUBLE:
                return DOUBLE;
            case TType.I16:
                return I16;
            case TType.I32:
                return I32;
            case TType.I64:
                return I64;
            case TType.STRING:
                return STRING;
        }
        return TypeSignature.ofUnresolved("unknown");
    }

    private static TypeSignature toTypeSignature(Type type) {
        if (type instanceof ParameterizedType) {
            final ParameterizedType parameterizedType = (ParameterizedType) type;
            final Class<?> rawType = (Class<?>) parameterizedType.getRawType();
            if (List.class.isAssignableFrom(rawType)) {
                return TypeSignature.ofList(toTypeSignature(parameterizedType.getActualTypeArguments()[0]));
            }
            if (Set.class.isAssignableFrom(rawType)) {
                return TypeSignature.ofSet(toTypeSignature(parameterizedType.getActualTypeArguments()[0]));
            }

            if (Map.class.isAssignableFrom(rawType)) {
                final TypeSignature key = toTypeSignature(parameterizedType.getActualTypeArguments()[0]);
                final TypeSignature value = toTypeSignature(parameterizedType.getActualTypeArguments()[1]);
                return TypeSignature.ofMap(key, value);
            }
        }

        if (type == Void.class || type == void.class) {
            return VOID;
        }
        if (type == Boolean.class || type == boolean.class) {
            return BOOL;
        }
        if (type == Byte.class || type == byte.class) {
            return I8;
        }
        if (type == Short.class || type == short.class) {
            return I16;
        }
        if (type == Integer.class || type == int.class) {
            return I32;
        }
        if (type == Long.class || type == long.class) {
            return I64;
        }
        if (type == Double.class || type == double.class) {
            return DOUBLE;
        }
        if (type == String.class) {
            return STRING;
        }
        if (type == ByteBuffer.class) {
            return BINARY;
        }

        if (!(type instanceof Class)) {
            return TypeSignature.ofBase(type.getTypeName());
        }

        final Class<?> type1 = (Class<?>) type;
        if (type1.isEnum()) {
            return TypeSignature.ofEnum(type1);
        }
        return TypeSignature.ofStruct(type1);
    }

    private static boolean hasTypeDef(FieldValueMetaData valueMetadata) {
        while (true) {
            if (valueMetadata.isTypedef()) {
                return true;
            }

            if (valueMetadata instanceof ListMetaData) {
                valueMetadata = ((ListMetaData) valueMetadata).elemMetaData;
                continue;
            }

            if (valueMetadata instanceof SetMetaData) {
                valueMetadata = ((SetMetaData) valueMetadata).elemMetaData;
                continue;
            }

            if (valueMetadata instanceof MapMetaData) {
                return hasTypeDef(((MapMetaData) valueMetadata).keyMetaData) ||
                       hasTypeDef(((MapMetaData) valueMetadata).valueMetaData);
            }

            return false;
        }
    }

    @VisibleForTesting
    static <T extends TBase<T, F>, F extends TFieldIdEnum> StructInfo newStructInfo(Class<?> structClass) {
        final String name = structClass.getName();

        final Map<?, FieldMetaData> metaDataMap = ThriftMetadataAccess.getStructMetaDataMap(structClass);
        final List<FieldInfo> fields =
                metaDataMap.values().stream()
                           .map(fieldMetaData -> newFieldInfo(structClass, fieldMetaData))
                           .collect(Collectors.toList());

        return new StructInfo(name, fields);
    }
}
