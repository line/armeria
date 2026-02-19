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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JavaType;
import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.docs.TypeSignature;

import io.netty.buffer.ByteBuf;

/**
 * A utility class that provides methods for converting type representations into
 * {@link TypeSignature} for {@link DocService}.
 * This class centralizes the logic for interpreting various Java and Jackson types
 * and mapping them to the standardized documentation model.
 */
public final class DocServiceTypeUtil {

    @VisibleForTesting
    public static final TypeSignature VOID = TypeSignature.ofBase("void");
    @VisibleForTesting
    public static final TypeSignature BOOLEAN = TypeSignature.ofBase("boolean");
    @VisibleForTesting
    public static final TypeSignature BYTE = TypeSignature.ofBase("byte");
    @VisibleForTesting
    public static final TypeSignature SHORT = TypeSignature.ofBase("short");
    @VisibleForTesting
    public static final TypeSignature INT = TypeSignature.ofBase("int");
    @VisibleForTesting
    public static final TypeSignature LONG = TypeSignature.ofBase("long");
    @VisibleForTesting
    public static final TypeSignature FLOAT = TypeSignature.ofBase("float");
    @VisibleForTesting
    public static final TypeSignature DOUBLE = TypeSignature.ofBase("double");
    @VisibleForTesting
    public static final TypeSignature CHAR = TypeSignature.ofBase("char");
    @VisibleForTesting
    public static final TypeSignature STRING = TypeSignature.ofBase("string");
    @VisibleForTesting
    public static final TypeSignature BINARY = TypeSignature.ofBase("binary");

    /**
     * Creates a {@link TypeSignature} from the specified {@link JavaType}.
     * This method acts as a bridge between Jackson's type representation and Armeria's documentation model.
     */
    public static TypeSignature toTypeSignature(JavaType type) {
        if (type.isArrayType() || type.isCollectionLikeType()) {
            return TypeSignature.ofList(toTypeSignature(type.getContentType()));
        }

        if (type.isMapLikeType()) {
            final TypeSignature key = toTypeSignature(type.getKeyType());
            final TypeSignature value = toTypeSignature(type.getContentType());
            return TypeSignature.ofMap(key, value);
        }

        if (Optional.class.isAssignableFrom(type.getRawClass()) ||
            "scala.Option".equals(type.getRawClass().getName())) {
            return TypeSignature.ofOptional(
                    toTypeSignature(type.getBindings().getBoundType(0)));
        }

        return toTypeSignature(type.getRawClass());
    }

    /**
     * Creates a {@link TypeSignature} from the specified {@link Type}.
     */
    public static TypeSignature toTypeSignature(Type type) {
        requireNonNull(type, "type");

        if (type instanceof JavaType) {
            return toTypeSignature((JavaType) type);
        }

        // The data types defined by the OpenAPI Specification:

        if (type == Void.class || type == void.class) {
            return VOID;
        }
        if (type == Boolean.class || type == boolean.class) {
            return BOOLEAN;
        }
        if (type == Byte.class || type == byte.class) {
            return BYTE;
        }
        if (type == Short.class || type == short.class) {
            return SHORT;
        }
        if (type == Integer.class || type == int.class) {
            return INT;
        }
        if (type == Long.class || type == long.class) {
            return LONG;
        }
        if (type == Float.class || type == float.class) {
            return FLOAT;
        }
        if (type == Double.class || type == double.class) {
            return DOUBLE;
        }
        if (type == Character.class || type == char.class) {
            return CHAR;
        }
        if (type == String.class) {
            return STRING;
        }
        if (type == byte[].class || type == Byte[].class ||
            type == ByteBuffer.class || type == ByteBuf.class) {
            return BINARY;
        }
        // End of data types defined by the OpenAPI Specification.

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

            if (Optional.class.isAssignableFrom(rawType) || "scala.Option".equals(rawType.getName())) {
                return TypeSignature.ofOptional(toTypeSignature(parameterizedType.getActualTypeArguments()[0]));
            }

            final List<TypeSignature> actualTypes = Stream.of(parameterizedType.getActualTypeArguments())
                                                          .map(DocServiceTypeUtil::toTypeSignature)
                                                          .collect(toImmutableList());
            return TypeSignature.ofContainer(rawType.getSimpleName(), actualTypes);
        }

        if (type instanceof WildcardType) {
            // Create an unresolved type with an empty string so that the type name will be '?'.
            return TypeSignature.ofUnresolved("");
        }
        if (type instanceof TypeVariable) {
            return TypeSignature.ofBase(type.getTypeName());
        }
        if (type instanceof GenericArrayType) {
            return TypeSignature.ofList(toTypeSignature(((GenericArrayType) type).getGenericComponentType()));
        }

        if (!(type instanceof Class)) {
            return TypeSignature.ofBase(type.getTypeName());
        }

        final Class<?> clazz = (Class<?>) type;
        if (clazz.isArray()) {
            // If it's an array, return it as a list.
            return TypeSignature.ofList(toTypeSignature(clazz.getComponentType()));
        }

        return TypeSignature.ofStruct(clazz);
    }

    private DocServiceTypeUtil() {}
}
