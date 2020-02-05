/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.internal.common.util;

import java.util.Set;
import java.util.TreeSet;

/**
 * Provides various utility functions for Java types.
 */
public final class Types {

    /**
     * Returns a set of all interfaces defined for {@code cls}.
     * Interfaces are sorted from lowest to highest level (i.e. children before parents)
     */
    public static TreeSet<Class<?>> getAllInterfaces(Class<?> cls) {
        final TreeSet<Class<?>> interfacesFound = new TreeSet<>(
            (c1, c2) -> {
                if (c1.equals(c2)) {
                    return 0;
                } else if (c1.isAssignableFrom(c2)) {
                    return 1;
                } else {
                    return -1;
                }
            }
        );
        getAllInterfaces(cls, interfacesFound);
        return interfacesFound;
    }

    private static void getAllInterfaces(Class<?> cls, Set<Class<?>> interfacesFound) {
        while (cls != null) {
            final Class<?>[] interfaces = cls.getInterfaces();
            for (final Class<?> i : interfaces) {
                if (interfacesFound.add(i)) {
                    getAllInterfaces(i, interfacesFound);
                }
            }
            cls = cls.getSuperclass();
        }
    }

    private Types() {}
}
