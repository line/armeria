/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.common.logging;

import org.jspecify.annotations.Nullable;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.logging.FieldMaskers.FallThroughFieldMasker;
import com.linecorp.armeria.common.logging.FieldMaskers.NoMaskFieldMasker;
import com.linecorp.armeria.common.logging.FieldMaskers.NullifyFieldMasker;

/**
 * Masks a field value. Users are encouraged to use one of the provided implementations
 * or {@link #builder()} rather than implementing this interface directly.
 */
@UnstableApi
@FunctionalInterface
public interface FieldMasker {

    /**
     * A {@link FieldMasker} which does not mask the provided field value.
     */
    static FieldMasker noMask() {
        return NoMaskFieldMasker.INSTANCE;
    }

    /**
     * A special marker {@link FieldMasker} which indicates a {@link FieldMaskerSelector} will not handle
     * a field.
     */
    static FieldMasker fallthrough() {
        return FallThroughFieldMasker.INSTANCE;
    }

    /**
     * A {@link FieldMasker} which will nullify a field value.
     */
    static FieldMasker nullify() {
        return NullifyFieldMasker.INSTANCE;
    }

    /**
     * Returns a {@link FieldMaskerBuilder} which helps create a type-safe
     * {@link FieldMasker}.
     */
    static FieldMaskerBuilder builder() {
        return new FieldMaskerBuilder();
    }

    /**
     * Has the same semantics as {@link #mask(Object)} but also provides a {@link RequestContext} object
     * if available.
     */
    @Nullable
    default Object mask(@Nullable RequestContext ctx, Object obj) {
        return mask(obj);
    }

    /**
     * A masking function which is invoked for a field value.
     * The returned object will be further serialized while checking if other
     * {@link FieldMasker}s should be applied.
     * The input field value is not deep-copied, and users discouraged from mutating the object directly.
     * <pre>{@code
     * public Object mask(Object obj) {
     *     if (obj instanceof Map) {
     *         Map<?, ?> map = (Map<?, ?>) obj;          // (x) prefer copying the map
     *         Map<?, ?> map = new HashMap<>((Map) obj); // (o)
     *         map.put(..);
     *         return map;
     *     }
     * }
     * }</pre>
     */
    @Nullable
    Object mask(Object obj);

    /**
     * Unmasks the target object when deserializing an object.
     * This may be useful if a field was symmetrically encrypted, and deserialization should
     * decrypt the object.
     */
    default Object unmask(Object obj, Class<?> expected) {
        return obj;
    }

    /**
     * Provides a mapping from between the output class and the input class.
     */
    default Class<?> mappedClass(Class<?> clazz) {
        return clazz;
    }
}
