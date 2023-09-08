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

import java.util.List;
import java.util.Objects;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A container {@link TypeSignature}.
 */
@UnstableApi
public class ContainerTypeSignature extends DefaultTypeSignature {

    private static final Joiner JOINER = Joiner.on(", ");

    private final List<TypeSignature> typeParameters;

    ContainerTypeSignature(TypeSignatureType type, String name,
                           List<TypeSignature> typeParameters) {
        super(type, name);
        final List<TypeSignature> typeParametersCopy = ImmutableList.copyOf(typeParameters);
        checkArgument(!typeParametersCopy.isEmpty(), "typeParameters is empty.");
        this.typeParameters = typeParametersCopy;
    }

    /**
     * Returns the list of type parameter {@link TypeSignature}s.
     */
    public List<TypeSignature> typeParameters() {
        return typeParameters;
    }

    @Override
    public String signature() {
        return name() + '<' + JOINER.join(typeParameters) + '>';
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ContainerTypeSignature)) {
            return false;
        }

        final ContainerTypeSignature that = (ContainerTypeSignature) o;
        return type() == that.type() &&
               name().equals(that.name()) &&
               Objects.equals(typeParameters, that.typeParameters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type(), name(), typeParameters);
    }
}
