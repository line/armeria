/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */
package com.linecorp.armeria.server.docs;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.util.UnstableApi;

/**
 * Type signature of a method parameter, a method return value or a struct/exception field.
 * A type signature can be represented as a string in one of the following forms:
 * <ul>
 *   <li>Base types: {@code "{type name}"}<ul>
 *     <li>{@code "i64"}</li>
 *     <li>{@code "double"}</li>
 *     <li>{@code "string"}</li>
 *   </ul></li>
 *   <li>Container types: {@code "{type name}<{element type signature}[, {element type signature}]*>"}<ul>
 *     <li>{@code "list<i32>"}</li>
 *     <li>{@code "repeated<set<string>>"}</li>
 *     <li>{@code "map<string, com.example.FooStruct>"}</li>
 *     <li>{@code "tuple<i8, string, double>"}</li>
 *   </ul></li>
 *   <li>Named types (enums, structs and exceptions): {@code "{fully qualified type name}"}<ul>
 *     <li>{@code "com.example.FooStruct"}</li>
 *   </ul></li>
 *   <li>Unresolved types: {@code "?{type name}"}<ul>
 *     <li>{@code "?BarStruct"}</li>
 *     <li>{@code "?com.example.BarStruct"}</li>
 *   </ul></li>
 * </ul>
 */
@UnstableApi
@JsonSerialize(using = TypeSignatureJsonSerializer.class)
public final class TypeSignature {

    private static final Pattern BASE_PATTERN = Pattern.compile("^([^.<>]+)$");
    private static final Pattern NAMED_PATTERN = Pattern.compile("^([^.<>]+(?:\\.[^.<>]+)+)$");

    /**
     * Creates a new type signature for a base type.
     *
     * @throws IllegalArgumentException if the specified type name is not valid
     */
    public static TypeSignature ofBase(String baseTypeName) {
        checkBaseTypeName(baseTypeName, "baseTypeName");
        return new TypeSignature(baseTypeName, ImmutableList.of());
    }

    /**
     * Creates a new container type with the specified container type name and the type signatures of the
     * elements it contains.
     *
     * @throws IllegalArgumentException if the specified type name is not valid or
     *                                  {@code elementTypeSignatures} is empty.
     */
    public static TypeSignature ofContainer(String containerTypeName, TypeSignature... elementTypeSignatures) {
        requireNonNull(elementTypeSignatures, "elementTypeSignatures");
        return ofContainer(containerTypeName, ImmutableList.copyOf(elementTypeSignatures));
    }

    /**
     * Creates a new container type with the specified container type name and the type signatures of the
     * elements it contains.
     *
     * @throws IllegalArgumentException if the specified type name is not valid or
     *                                  {@code elementTypeSignatures} is empty.
     */
    public static TypeSignature ofContainer(String containerTypeName,
                                            Iterable<TypeSignature> elementTypeSignatures) {
        checkBaseTypeName(containerTypeName, "containerTypeName");
        requireNonNull(elementTypeSignatures, "elementTypeSignatures");
        final List<TypeSignature> elementTypeSignaturesCopy = ImmutableList.copyOf(elementTypeSignatures);
        checkArgument(!elementTypeSignaturesCopy.isEmpty(), "elementTypeSignatures is empty.");
        return new TypeSignature(containerTypeName, elementTypeSignaturesCopy);
    }

    private static void checkBaseTypeName(String baseTypeName, String parameterName) {
        requireNonNull(baseTypeName, parameterName);
        checkArgument(BASE_PATTERN.matcher(baseTypeName).matches(), "%s: %s", parameterName, baseTypeName);
    }

    /**
     * Creates a new type signature for the list with the specified element type signature.
     * This method is a shortcut for:
     * <pre>{@code
     * ofContainer("list", elementTypeSignature);
     * }</pre>
     */
    public static TypeSignature ofList(TypeSignature elementTypeSignature) {
        requireNonNull(elementTypeSignature, "elementTypeSignature");
        return ofContainer("list", elementTypeSignature);
    }

    /**
     * Creates a new type signature for the list with the specified named element type.
     * This method is a shortcut for:
     * <pre>{@code
     * ofList(ofNamed(namedElementType));
     * }</pre>
     */
    public static TypeSignature ofList(Class<?> namedElementType) {
        return ofList(ofNamed(namedElementType, "namedElementType"));
    }

    /**
     * Creates a new type signature for the set with the specified element type signature.
     * This method is a shortcut for:
     * <pre>{@code
     * ofContainer("set", elementTypeSignature);
     * }</pre>
     */
    public static TypeSignature ofSet(TypeSignature elementTypeSignature) {
        requireNonNull(elementTypeSignature, "elementTypeSignature");
        return ofContainer("set", elementTypeSignature);
    }

    /**
     * Creates a new type signature for the set with the specified named element type.
     * This method is a shortcut for:
     * <pre>{@code
     * ofSet(ofNamed(namedElementType));
     * }</pre>
     */
    public static TypeSignature ofSet(Class<?> namedElementType) {
        return ofSet(ofNamed(namedElementType, "namedElementType"));
    }

    /**
     * Creates a new type signature for the map with the specified key and value type signatures.
     * This method is a shortcut for:
     * <pre>{@code
     * ofMap("map", keyTypeSignature, valueTypeSignature);
     * }</pre>
     */
    public static TypeSignature ofMap(TypeSignature keyTypeSignature, TypeSignature valueTypeSignature) {
        requireNonNull(keyTypeSignature, "keyTypeSignature");
        requireNonNull(valueTypeSignature, "valueTypeSignature");
        return ofContainer("map", keyTypeSignature, valueTypeSignature);
    }

    /**
     * Creates a new type signature for the map with the specified named key and value types.
     * This method is a shortcut for:
     * <pre>{@code
     * ofMap(ofNamed(namedKeyType), ofNamed(namedValueType));
     * }</pre>
     */
    public static TypeSignature ofMap(Class<?> namedKeyType, Class<?> namedValueType) {
        return ofMap(ofNamed(namedKeyType, "namedKeyType"), ofNamed(namedValueType, "namedValueType"));
    }

    /**
     * Creates a new named type signature for the specified type.
     */
    public static TypeSignature ofNamed(Class<?> namedType) {
        return ofNamed(namedType, "namedType");
    }

    /**
     * Creates a new named type signature for the provided name and arbitrary descriptor.
     */
    public static TypeSignature ofNamed(String name, Object namedTypeDescriptor) {
        return new TypeSignature(requireNonNull(name, "name"),
                                 requireNonNull(namedTypeDescriptor, "namedTypeDescriptor"));
    }

    private static TypeSignature ofNamed(Class<?> namedType, String parameterName) {
        requireNonNull(namedType, parameterName);

        final String typeName = namedType.getName();
        checkArgument(NAMED_PATTERN.matcher(typeName).matches(), "%s: %s", parameterName, typeName);
        checkArgument(!namedType.isArray(), "%s is an array: %s", parameterName, typeName);
        checkArgument(!namedType.isPrimitive(), "%s is a primitive type: %s", parameterName, typeName);

        return new TypeSignature(namedType);
    }

    /**
     * Creates a new unresolved type signature with the specified type name.
     */
    public static TypeSignature ofUnresolved(String unresolvedTypeName) {
        requireNonNull(unresolvedTypeName, "unresolvedTypeName");
        return new TypeSignature('?' + unresolvedTypeName, ImmutableList.of());
    }

    private final String name;
    @Nullable
    private final Object namedTypeDescriptor;
    private final List<TypeSignature> typeParameters;

    /**
     * Creates a new non-named type signature.
     */
    private TypeSignature(String name, List<TypeSignature> typeParameters) {
        this.name = name;
        this.typeParameters = typeParameters;
        namedTypeDescriptor = null;
    }

    /**
     * Creates a new type signature for a named type.
     */
    private TypeSignature(Class<?> namedTypeDescriptor) {
        name = namedTypeDescriptor.getName();
        this.namedTypeDescriptor = namedTypeDescriptor;
        typeParameters = ImmutableList.of();
    }

    private TypeSignature(String name, Object namedTypeDescriptor) {
        this.name = name;
        this.namedTypeDescriptor = namedTypeDescriptor;
        typeParameters = ImmutableList.of();
    }

    /**
     * Returns the name of the type.
     */
    public String name() {
        return name;
    }

    /**
     * Returns the descriptor of the type if and only if this type signature represents a named type.
     * For reflection-based {@link DocServicePlugin}s, this will probably be a {@link Class}, but
     * other plugins may use an actual instance with descriptor information.
     */
    @Nullable
    public Object namedTypeDescriptor() {
        return namedTypeDescriptor;
    }

    /**
     * Returns the list of the type parameters of this type signature.
     */
    public List<TypeSignature> typeParameters() {
        return typeParameters;
    }

    /**
     * Returns the {@link String} representation of this type signature, as described in the class
     * documentation.
     */
    public String signature() {
        if (typeParameters.isEmpty()) {
            return name;
        } else {
            return name + '<' + Joiner.on(", ").join(typeParameters) + '>';
        }
    }

    /**
     * Returns {@code true} if this type signature represents a base type.
     */
    public boolean isBase() {
        return !isUnresolved() && !isNamed() && !isContainer();
    }

    /**
     * Returns {@code true} if this type signature represents a container type.
     */
    public boolean isContainer() {
        return !typeParameters.isEmpty();
    }

    /**
     * Returns {@code true} if this type signature represents a named type.
     */
    public boolean isNamed() {
        return namedTypeDescriptor != null;
    }

    /**
     * Returns {@code true} if this type signature represents an unresolved type.
     */
    public boolean isUnresolved() {
        return name.startsWith("?");
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TypeSignature)) {
            return false;
        }

        final TypeSignature that = (TypeSignature) o;
        if (!name.equals(that.name)) {
            return false;
        }

        return Objects.equals(namedTypeDescriptor, that.namedTypeDescriptor);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, namedTypeDescriptor, typeParameters);
    }

    @Override
    public String toString() {
        return signature();
    }
}
