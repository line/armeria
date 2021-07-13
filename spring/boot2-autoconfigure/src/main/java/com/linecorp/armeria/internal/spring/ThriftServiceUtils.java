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

package com.linecorp.armeria.internal.spring;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static org.springframework.util.ReflectionUtils.findMethod;
import static org.springframework.util.ReflectionUtils.invokeMethod;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.thrift.THttpService;

/**
 * A utility for {@link THttpService} not to accessing classes in armeria-thrift-* even if thrift libraries
 * are not populated in dependency list.
 */
final class ThriftServiceUtils {
    @Nullable
    private static final Class<?> thriftServiceClass;
    @Nullable
    private static final Method entriesMethod;
    @Nullable
    private static final Method interfacesMethod;

    static {
        final String serverPackageName = Server.class.getPackage().getName();
        thriftServiceClass = findClass(serverPackageName + ".thrift.THttpService");
        entriesMethod = thriftServiceClass != null ? findMethod(thriftServiceClass, "entries") : null;

        final Class<?> thriftServiceEntryClass = findClass(serverPackageName + ".thrift.ThriftServiceEntry");
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
    static Set<String> serviceNames(HttpService service) {
        if (thriftServiceClass == null || entriesMethod == null || interfacesMethod == null) {
            return ImmutableSet.of();
        }
        final Object thriftService = service.as(thriftServiceClass);
        if (thriftService == null) {
            return ImmutableSet.of();
        }

        @SuppressWarnings("unchecked")
        final Map<String, ?> entries = (Map<String, ?>) invokeMethod(entriesMethod, thriftService);
        assert entries != null;
        return toServiceName(entries.values());
    }

    private static Set<String> toServiceName(Collection<?> entries) {
        return entries.stream()
                      .flatMap(entry -> {
                          @SuppressWarnings("unchecked")
                          final Set<Class<?>> ifaces = (Set<Class<?>>) invokeMethod(interfacesMethod, entry);
                          assert ifaces != null;
                          return ifaces.stream();
                      })
                      .map(iface -> iface.getEnclosingClass().getName())
                      .collect(toImmutableSet());
    }
}
