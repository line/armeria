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

package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import java.util.HashMap;
import java.util.Map;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.AttributeKey;

final class ImmutableAttributesBuilder implements AttributesBuilder {

    private final Map<AttributeKey<?>, Object> attributes = new HashMap<>();
    @Nullable
    private final AttributesGetters parent;

    ImmutableAttributesBuilder(@Nullable AttributesGetters parent) {
        this.parent = parent;
    }

    @Override
    public <T> AttributesBuilder set(AttributeKey<T> key, T value) {
        requireNonNull(key, "key");
        requireNonNull(value, "value");
        attributes.put(key, value);
        return this;
    }

    @Override
    public <T> boolean remove(AttributeKey<T> key) {
        return attributes.remove(key) != null;
    }

    @Override
    public Attributes build() {
        return new ImmutableAttributes(parent, attributes);
    }
}
