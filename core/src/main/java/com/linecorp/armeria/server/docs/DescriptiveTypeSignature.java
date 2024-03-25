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

import java.util.Objects;
import java.util.regex.Pattern;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A descriptive {@link TypeSignature}.
 */
@UnstableApi
public class DescriptiveTypeSignature extends DefaultTypeSignature {

    static final Pattern NAMED_PATTERN = Pattern.compile("^([^.<>]+(?:\\.[^.<>]+)+)$");

    private final Object descriptor;

    DescriptiveTypeSignature(TypeSignatureType type, Class<?> descriptor) {
        super(type, descriptor.getName());
        assert type.hasTypeDescriptor();
        final String typeName = descriptor.getName();
        checkArgument(NAMED_PATTERN.matcher(typeName).matches(), "%s: %s", descriptor, typeName);
        checkArgument(!descriptor.isArray(),
                      "%s is an array: %s", descriptor, typeName);
        checkArgument(!descriptor.isPrimitive(),
                      "%s is a primitive type: %s", descriptor, typeName);
        this.descriptor = descriptor;
    }

    /**
     * Create a new instance.
     */
    protected DescriptiveTypeSignature(TypeSignatureType type, String name, Object descriptor) {
        super(type, name);
        this.descriptor = descriptor;
    }

    /**
     * Returns the descriptor of the type if and only if this type signature represents a descriptive type.
     * For reflection-based {@link DocServicePlugin}s, this will probably be a {@link Class}, but
     * other plugins may use an actual instance with descriptor information.
     */
    public Object descriptor() {
        return descriptor;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DescriptiveTypeSignature)) {
            return false;
        }
        final DescriptiveTypeSignature that = (DescriptiveTypeSignature) o;
        return type() == that.type() &&
               name().equals(that.name()) &&
               Objects.equals(descriptor, that.descriptor);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type(), name(), descriptor);
    }
}
