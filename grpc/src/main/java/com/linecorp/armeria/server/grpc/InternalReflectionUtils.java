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

package com.linecorp.armeria.server.grpc;

import static org.reflections.util.Utils.isEmpty;

import java.lang.reflect.Method;
import java.util.List;

import org.reflections.ReflectionUtils;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

/**
 * Utility class which is used by {@link GrpcServiceBuilder} to get all sorted methods
 * from a bottom service class.
 */
class InternalReflectionUtils extends ReflectionUtils {

    /**
     * get all methods of given type which are sorted from bottom class, up the super class hierarchy,
     * optionally filtered by predicates
     */
    static List<Method> getAllSortedMethods(final Class<?> type,
                                            Predicate<? super Method>... predicates) {
        final List<Method> result = Lists.newArrayList();
        for (Class<?> t : getAllSortedSuperTypes(type)) {
            result.addAll(getMethods(t, predicates));
        }
        return result;
    }

    private static List<Class<?>> getAllSortedSuperTypes(final Class<?> type,
                                                         Predicate<? super Class<?>>... predicates) {
        List<Class<?>> result = Lists.newArrayList();
        if (type != null && (includeObject || !type.equals(Object.class))) {
            result.add(type);
            for (Class<?> supertype : getSuperTypes(type)) {
                result.addAll(getAllSortedSuperTypes(supertype));
            }
        }
        return filter(result, predicates);
    }

    private static <T> List<T> filter(final Iterable<T> elements, Predicate<? super T>... predicates) {
        return isEmpty(predicates) ? Lists.newArrayList(elements) :
               Lists.newArrayList(Iterables.filter(elements, Predicates.and(predicates)));
    }
}
