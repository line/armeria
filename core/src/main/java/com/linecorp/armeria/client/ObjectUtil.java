/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.armeria.client;

import java.util.function.Supplier;

/**
 * The utility class for Object.
 */
public final class ObjectUtil {

    /**
     * Returns a non-null value. If an unexpected exception occurs while retrieving the first value,
     * or the first value is null, this function will return the second value.
     * Otherwise, it returns the first value.
     */
    public static <T> T firstNonNullException(Supplier<T> firstSupplier, T second) {
        try {
            if (firstSupplier != null) {
                final T first = firstSupplier.get();
                if (first != null) {
                    return first;
                }
            }
        } catch (Exception e) {
            // Ignore
        }

        if (second != null) {
            return second;
        }

        throw new NullPointerException("Both parameters are null.");
    }

    private ObjectUtil() {
    }
}
