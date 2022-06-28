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

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.server.annotation.AnnotatedDocServicePlugin;

/**
 * Creates a new {@link NamedTypeInfo} dynamically via Java SPI (Service Provider Interface).
 * The loaded {@link NamedTypeInfoProvider}s are used in the {@link DocServicePlugin}s to extract
 * a {@link NamedTypeInfo} from the given {@code typeDescriptor}.
 */
@UnstableApi
@FunctionalInterface
public interface NamedTypeInfoProvider {

    /**
     * Creates a new {@link NamedTypeInfo} for the specified {@code typeDescriptor}.
     * If a {@code null} value is returned, a {@link DocServicePlugin} will try to convert the
     * {@code typeDescriptor} by the next converter.
     *
     * <p>The type descriptor is different depending on the implementation of {@link DocServicePlugin}.
     * One of the following type descriptions may be chosen to describe a type information.
     * <li>
     *    <ul>{@link Class} for {@link AnnotatedDocServicePlugin}</ul>
     *    <ul>{@code com.google.protobuf.Descriptors.Descriptor} and
     *        {@code com.google.protobuf.Descriptors.EnumDescriptor} for {@code GrpcDocServicePlugin}</ul>
     *    <ul>{@code org.apache.thrift.TBase} {@code org.apache.thrift.TEnum} and
     *        {@code org.apache.thrift.TException} for {@code ThriftDocServicePlugin}</ul>
     * </li>
     *
     * @return a new {@link StructInfo}. {@code null} if this {@link NamedTypeInfoProvider} cannot convert the
     *         {@code typeDescriptor} to the {@link NamedTypeInfo}.
     */
    @Nullable
    NamedTypeInfo newNamedTypeInfo(Object typeDescriptor);

    /**
     * Returns a newly created {@link NamedTypeInfoProvider} that tries this {@link NamedTypeInfoProvider}
     * first and then the specified {@link NamedTypeInfoProvider} when the first call returns {@code null}.
     */
    default NamedTypeInfoProvider orElse(NamedTypeInfoProvider provider) {
        requireNonNull(provider, "provider");
        return typeDescriptor -> {
            final NamedTypeInfo structInfo = newNamedTypeInfo(typeDescriptor);
            if (structInfo != null) {
                return structInfo;
            } else {
                return provider.newNamedTypeInfo(typeDescriptor);
            }
        };
    }
}
