/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.spring;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static org.springframework.util.ReflectionUtils.findMethod;
import static org.springframework.util.ReflectionUtils.invokeMethod;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.thrift.THttpService;

/**
 * A utility for {@link THttpService} not to accessing classes in armeria-thrift-* even if thrift libraries
 * are not populated in dependency list.
 */
final class ThriftServiceUtils {
    private static final Class<?> thriftServiceClass;
    private static final Method entriesMethod;
    private static final Method interfacesMethod;

    static {
        thriftServiceClass = findClass("com.linecorp.armeria.server.thrift.THttpService");
        entriesMethod = thriftServiceClass != null ? findMethod(thriftServiceClass, "entries") : null;

        Class<?> thriftServiceEntryClass = findClass("com.linecorp.armeria.server.thrift.ThriftServiceEntry");
        interfacesMethod = thriftServiceEntryClass != null ? findMethod(thriftServiceEntryClass,
                                                                        "interfaces") : null;
    }

    private ThriftServiceUtils() {}

    @Nullable
    private static Class<?> findClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /**
     * Retrieves thrift service names of {@code service} using reflection.
     */
    static Set<String> serviceNames(Service<HttpRequest, HttpResponse> service) {
        if (thriftServiceClass == null || entriesMethod == null || interfacesMethod == null) {
            return ImmutableSet.of();
        }
        return service.as(thriftServiceClass)
                      .map(s -> invokeMethod(entriesMethod, s))
                      .map(Map.class::cast)
                      .map(Map::values)
                      .map(ThriftServiceUtils::toServiceName)
                      .orElse(ImmutableSet.of());
    }

    private static Set<String> toServiceName(Collection<?> entries) {
        return entries.stream()
                      .map(entry -> invokeMethod(interfacesMethod, entry))
                      .map(Collection.class::cast)
                      .flatMap(Collection::stream)
                      .map(Class.class::cast)
                      .map(Class::getEnclosingClass)
                      .map(Class::getName)
                      .collect(toImmutableSet());
    }
}
