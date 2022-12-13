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

import static com.linecorp.armeria.server.docs.DefaultTypeSignature.checkBaseTypeName;
import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.UnstableApi;

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
public interface TypeSignature {

    /**
     * Creates a new type signature for a base type.
     *
     * @throws IllegalArgumentException if the specified type name is not valid
     */
    static TypeSignature ofBase(String baseTypeName) {
        checkBaseTypeName(baseTypeName, "baseTypeName");
        return new DefaultTypeSignature(TypeSignatureType.BASE, baseTypeName);
    }

    /**
     * Creates a new container type with the specified container type name and the type signatures of the
     * elements it contains.
     *
     * @throws IllegalArgumentException if the specified type name is not valid or
     *                                  {@code elementTypeSignatures} is empty.
     */
    static ContainerTypeSignature ofContainer(String containerTypeName,
                                              TypeSignature... elementTypeSignatures) {
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
    static ContainerTypeSignature ofContainer(String containerTypeName,
                                              Iterable<TypeSignature> elementTypeSignatures) {
        checkBaseTypeName(containerTypeName, "containerTypeName");
        requireNonNull(elementTypeSignatures, "elementTypeSignatures");
        return new ContainerTypeSignature(TypeSignatureType.CONTAINER, containerTypeName,
                                          ImmutableList.copyOf(elementTypeSignatures));
    }

    /**
     * Creates a new type signature for the list with the specified element type signature.
     * This method is a shortcut for:
     * <pre>{@code
     * ofContainer("list", elementTypeSignature);
     * }</pre>
     */
    static ContainerTypeSignature ofList(TypeSignature elementTypeSignature) {
        requireNonNull(elementTypeSignature, "elementTypeSignature");
        return ofIterable("list", elementTypeSignature);
    }

    /**
     * Creates a new type signature for the set with the specified element type signature.
     * This method is a shortcut for:
     * <pre>{@code
     * ofContainer("set", elementTypeSignature);
     * }</pre>
     */
    static ContainerTypeSignature ofSet(TypeSignature elementTypeSignature) {
        requireNonNull(elementTypeSignature, "elementTypeSignature");
        return ofIterable("set", elementTypeSignature);
    }

    /**
     * Creates a new container type with the specified container type name and the type signatures of the
     * elements it contains.
     *
     * @throws IllegalArgumentException if the specified type name is not valid or
     *                                  {@code elementTypeSignatures} is empty.
     */
    static ContainerTypeSignature ofIterable(String iterableTypeName, TypeSignature elementTypeSignature) {
        requireNonNull(iterableTypeName, "iterableTypeName");
        requireNonNull(elementTypeSignature, "elementTypeSignature");
        return new ContainerTypeSignature(TypeSignatureType.ITERABLE, iterableTypeName,
                                          ImmutableList.of(elementTypeSignature));
    }

    /**
     * Creates a new type signature for the map with the specified key and value type signatures.
     * This method is a shortcut for:
     * <pre>{@code
     * ofMap("map", keyTypeSignature, valueTypeSignature);
     * }</pre>
     */
    static MapTypeSignature ofMap(TypeSignature keyTypeSignature, TypeSignature valueTypeSignature) {
        requireNonNull(keyTypeSignature, "keyTypeSignature");
        requireNonNull(valueTypeSignature, "valueTypeSignature");
        return new MapTypeSignature(keyTypeSignature, valueTypeSignature);
    }

    /**
     * Creates a new type signature for the optional type with the specified element type signature.
     */
    static ContainerTypeSignature ofOptional(TypeSignature elementTypeSignature) {
        requireNonNull(elementTypeSignature, "elementTypeSignature");
        return new ContainerTypeSignature(TypeSignatureType.OPTIONAL, "optional",
                                          ImmutableList.of(elementTypeSignature));
    }

    /**
     * Creates a new struct type signature for the specified type. An {@link Exception} type is also created
     * using this method.
     */
    static DescriptiveTypeSignature ofStruct(Class<?> structType) {
        requireNonNull(structType, "structType");
        return new DescriptiveTypeSignature(TypeSignatureType.STRUCT, structType);
    }

    /**
     * Creates a new struct type signature for the provided name and arbitrary descriptor.
     * An {@link Exception} type is also created using this method.
     */
    static DescriptiveTypeSignature ofStruct(String name, Object typeDescriptor) {
        requireNonNull(name, "name");
        requireNonNull(typeDescriptor, "typeDescriptor");
        return new DescriptiveTypeSignature(TypeSignatureType.STRUCT, name, typeDescriptor);
    }

    /**
     * Creates a new enum type signature for the specified type.
     */
    static DescriptiveTypeSignature ofEnum(Class<?> enumType) {
        requireNonNull(enumType, "enumType");
        return new DescriptiveTypeSignature(TypeSignatureType.ENUM, enumType);
    }

    /**
     * Creates a new enum type signature for the provided name and arbitrary descriptor.
     */
    static DescriptiveTypeSignature ofEnum(String name, Object enumTypeDescriptor) {
        requireNonNull(name, "name");
        requireNonNull(enumTypeDescriptor, "enumTypeDescriptor");
        return new DescriptiveTypeSignature(TypeSignatureType.ENUM, name, enumTypeDescriptor);
    }

    /**
     * Creates a new unresolved type signature with the specified type name.
     */
    static TypeSignature ofUnresolved(String unresolvedTypeName) {
        requireNonNull(unresolvedTypeName, "unresolvedTypeName");
        return new DefaultTypeSignature(TypeSignatureType.UNRESOLVED, '?' + unresolvedTypeName);
    }

    /**
     * Returns the {@link TypeSignatureType}.
     */
    TypeSignatureType type();

    /**
     * Returns the name of the type.
     */
    String name();

    /**
     * Returns the {@link String} representation of this type signature, as described in the class
     * documentation.
     */
    default String signature() {
        return name();
    }
}
