/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.docs;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceEntry;
import com.linecorp.armeria.server.thrift.ThriftService;

class Specification {

    static Specification forServiceEntries(List<ServiceEntry> services) {
        final Map<Class<?>, Optional<String>> serviceClassesWithDebugPath = new LinkedHashMap<>();

        for (ServiceEntry serviceEntry : services) {
            Service service = serviceEntry.service();
            final Optional<ThriftService> thriftServiceOptional = service.as(ThriftService.class);
            if (!thriftServiceOptional.isPresent()) {
                continue;
            }

            final ThriftService thriftService = thriftServiceOptional.get();
            final Optional<String> debugPath =
                    thriftService.allowedSerializationFormats().contains(SerializationFormat.THRIFT_TEXT) ?
                    serviceEntry.pathMapping().exactPath() : Optional.empty();

            final Class<?>[] ifaces = thriftService.thriftService().getClass().getInterfaces();
            for (Class<?> iface : ifaces) {
                if (!iface.getName().endsWith("$AsyncIface") && !iface.getName().endsWith("$Iface")) {
                    continue;
                }

                Class<?> serviceClass = iface.getEnclosingClass();
                if (serviceClassesWithDebugPath.getOrDefault(serviceClass, Optional.empty()).isPresent()) {
                    // Class already registered with a debug path, don't overwrite it.
                    continue;
                }
                serviceClassesWithDebugPath.put(iface.getEnclosingClass(), debugPath);
            }
        }

        return Specification.forServiceClasses(serviceClassesWithDebugPath);
    }

    static Specification forServiceClasses(Map<Class<?>, Optional<String>> serviceClassesWithDebugPath) {
        requireNonNull(serviceClassesWithDebugPath, "serviceClassesWithDebugPath");

        final List<ServiceInfo> services = new ArrayList<>(serviceClassesWithDebugPath.size());
        final Set<ClassInfo> classes = new HashSet<>();
        serviceClassesWithDebugPath.forEach((serviceClass, debugPath) -> {
            try {
                final ServiceInfo service = ServiceInfo.of(serviceClass, debugPath);
                services.add(service);
                classes.addAll(service.classes().values());
            } catch (ClassNotFoundException e) {
                throw new RuntimeException("unable to initialize Specification", e);
            }
        });

        return new Specification(services, classes);
    }

    private final Map<String, ServiceInfo> services;
    private final Map<String, ClassInfo> classes;

    private Specification(Collection<ServiceInfo> services, Collection<ClassInfo> classes) {
        final Map<String, ServiceInfo> serviceMap = new TreeMap<>();
        final Map<String, ClassInfo> classMap = new TreeMap<>();

        services.stream().forEach(s -> serviceMap.put(s.name(), s));
        classes.stream().forEach(c -> classMap.put(c.name(), c));

        this.services = Collections.unmodifiableMap(serviceMap);
        this.classes = Collections.unmodifiableMap(classMap);
    }

    @JsonProperty
    public Map<String, ServiceInfo> services() {
        return services;
    }

    @JsonProperty
    public Map<String, ClassInfo> classes() {
        return classes;
    }
}
