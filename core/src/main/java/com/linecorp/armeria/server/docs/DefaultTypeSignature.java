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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.Objects;
import java.util.regex.Pattern;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * The default {@link TypeSignature}.
 */
@UnstableApi
class DefaultTypeSignature implements TypeSignature {

    private static final Pattern BASE_PATTERN = Pattern.compile("^([^.<>]+)$");

    static void checkBaseTypeName(String baseTypeName, String parameterName) {
        requireNonNull(baseTypeName, parameterName);
        checkArgument(BASE_PATTERN.matcher(baseTypeName).matches(), "%s: %s", parameterName, baseTypeName);
    }

    private final TypeSignatureType type;
    private final String name;

    DefaultTypeSignature(TypeSignatureType type, String name) {
        this.type = type;
        this.name = name;
    }

    @Override
    public TypeSignatureType type() {
        return type;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DefaultTypeSignature)) {
            return false;
        }

        final DefaultTypeSignature that = (DefaultTypeSignature) o;
        return type == that.type &&
               name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, name);
    }

    @Override
    public String toString() {
        return signature();
    }
}
