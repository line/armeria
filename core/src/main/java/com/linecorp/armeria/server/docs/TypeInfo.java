/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.docs;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Metadata about the type of a field or a return value.
 */
public interface TypeInfo {

    /**
     * The 'void' type. Used only as a return type.
     */
    TypeInfo VOID = Type.VOID.asTypeInfo();

    /**
     * The 'boolean' type.
     */
    TypeInfo BOOL = Type.BOOL.asTypeInfo();

    /**
     * The signed 8-bit integer type.
     */
    TypeInfo I8 = Type.I8.asTypeInfo();

    /**
     * The double float type.
     */
    TypeInfo DOUBLE = Type.DOUBLE.asTypeInfo();

    /**
     * The signed 16-bit integer type.
     */
    TypeInfo I16 = Type.I16.asTypeInfo();

    /**
     * The signed 32-bit integer type.
     */
    TypeInfo I32 = Type.I32.asTypeInfo();

    /**
     * The signed 64-bit integer type.
     */
    TypeInfo I64 = Type.I64.asTypeInfo();

    /**
     * The 'string' type.
     */
    TypeInfo STRING = Type.STRING.asTypeInfo();

    /**
     * The 'binary' type.
     */
    TypeInfo BINARY = Type.BINARY.asTypeInfo();

    /**
     * Returns the type of a field or a return value.
     */
    @JsonProperty
    Type type();

    /**
     * Returns the type signature string of a field or a return value. For example:
     * <ul>
     *   <li>{@code STRING} (base types)</li>
     *   <li>{@code LIST<com.linecorp.bar.BarStruct>} (containers)</li>
     *   <li>{@code com.linecorp.foo.FooStruct} (structs and enums)</li>
     * </ul>
     */
    @JsonProperty
    String signature();
}
