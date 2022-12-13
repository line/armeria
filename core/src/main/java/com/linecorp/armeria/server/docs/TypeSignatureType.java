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

package com.linecorp.armeria.server.docs;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Represents the different type that is used for {@link DocService}.
 */
@UnstableApi
public enum TypeSignatureType {

    /**
     * Base type.
     */
    BASE(false, false),

    /**
     * Struct of exception type. The {@link TypeSignature} whose type is {@link #STRUCT} is converted to a
     * {@link StructInfo} or {@link ExceptionInfo}
     */
    STRUCT(false, true),

    /**
     * Enum type. The {@link TypeSignature} whose type is {@link #ENUM} is converted to a {@link EnumInfo}.
     */
    ENUM(false, true),

    /**
     * Iterable type.
     */
    ITERABLE(true, false),

    /**
     * Map type.
     */
    MAP(true, false),

    /**
     * Optional type.
     */
    OPTIONAL(true, false),

    /**
     * Container type.
     */
    CONTAINER(true, false),

    // TODO(minwoox): Support oneof type:
    //                https://json-schema.org/understanding-json-schema/reference/combining.html#oneof

    /**
     * Unresolved type.
     */
    UNRESOLVED(false, false);

    private final boolean hasParameter;
    private final boolean hasTypeDescriptor;

    TypeSignatureType(boolean hasParameter, boolean hasTypeDescriptor) {
        this.hasParameter = hasParameter;
        this.hasTypeDescriptor = hasTypeDescriptor;
    }

    /**
     * Returns true if the type has type parameter {@link TypeSignature}.
     */
    public boolean hasParameter() {
        return hasParameter;
    }

    /**
     * Returns true if this {@link TypeSignatureType} has type a type descriptor.
     */
    public boolean hasTypeDescriptor() {
        return hasTypeDescriptor;
    }
}
