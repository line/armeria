/*
 * Copyright 2015 LINE Corporation
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

/**
 * The type of a field or a return value.
 */
public enum Type {
    /**
     * The 'void' type. Used only as a return type.
     */
    VOID,

    /**
     * The 'boolean' type.
     */
    BOOL,

    /**
     * The signed 8-bit integer type.
     */
    I8,

    /**
     * The double float type.
     */
    DOUBLE,

    /**
     * The signed 16-bit integer type.
     */
    I16,

    /**
     * The signed 32-bit integer type.
     */
    I32,

    /**
     * The signed 64-bit integer type.
     */
    I64,

    /**
     * The 'string' type.
     */
    STRING,

    /**
     * The 'binary' type.
     */
    BINARY,

    /**
     * The 'struct' type.
     */
    STRUCT,

    /**
     * The 'map' type.
     */
    MAP,

    /**
     * The 'set' type.
     */
    SET,

    /**
     * The 'list' type.
     */
    LIST,

    /**
     * The 'enum' type.
     */
    ENUM;

    private final TypeInfo typeInfo = new TypeInfo() {
        @Override
        public Type type() {
            return Type.this;
        }

        @Override
        public String signature() {
            return name();
        }

        @Override
        public String toString() {
            return signature();
        }
    };

    /**
     * Returns the {@link TypeInfo} of this base type.
     *
     * @throws UnsupportedOperationException if this type is not a base type but one of the following:
     * <ul>
     *   <li>{@link #STRUCT}</li>
     *   <li>{@link #MAP}</li>
     *   <li>{@link #SET}</li>
     *   <li>{@link #LIST}</li>
     *   <li>{@link #ENUM}</li>
     * </ul>
     */
    public TypeInfo asTypeInfo() {
        switch (this) {
            case STRUCT:
            case MAP:
            case SET:
            case LIST:
            case ENUM:
                throw new UnsupportedOperationException();
        }

        return typeInfo;
    }
}
