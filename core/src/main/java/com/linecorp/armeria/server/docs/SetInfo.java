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

import static java.util.Objects.requireNonNull;

import java.util.Objects;

/**
 * Metadata about a set-type value.
 */
public final class SetInfo implements CollectionInfo {

    private final TypeInfo elementTypeInfo;

    /**
     * Creates a new instance.
     */
    public SetInfo(TypeInfo elementTypeInfo) {
        this.elementTypeInfo = requireNonNull(elementTypeInfo, "elementTypeInfo");
    }

    @Override
    public Type type() {
        return Type.SET;
    }

    @Override
    public String signature() {
        return "SET<" + elementTypeInfo + '>';
    }

    @Override
    public TypeInfo elementTypeInfo() {
        return elementTypeInfo;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final SetInfo that = (SetInfo) o;
        return Objects.equals(elementTypeInfo, that.elementTypeInfo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type(), elementTypeInfo);
    }

    @Override
    public String toString() {
        return signature();
    }
}
