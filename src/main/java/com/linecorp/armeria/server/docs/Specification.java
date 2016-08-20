/*
 * Copyright 2016 LINE Corporation
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
import java.util.Set;
import java.util.TreeMap;

import org.apache.thrift.TBase;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.thrift.THttpService;

class Specification {

    static Specification forServiceConfigs(Iterable<ServiceConfig> serviceConfigs,
                                           Map<Class<?>, ? extends TBase<?, ?>> sampleRequests,
                                           Map<Class<?>, Map<String, String>> sampleHttpHeaders) {

        final Map<Class<?>, Iterable<EndpointInfo>> map = new LinkedHashMap<>();

        for (ServiceConfig c : serviceConfigs) {
            c.service().as(THttpService.class).ifPresent(service -> {
                for (Class<?> iface : service.interfaces()) {
                    final Class<?> serviceClass = iface.getEnclosingClass();
                    final List<EndpointInfo> endpoints =
                            (List<EndpointInfo>) map.computeIfAbsent(serviceClass, cls -> new ArrayList<>());

                    c.pathMapping().exactPath().ifPresent(
                            p -> endpoints.add(EndpointInfo.of(
                                    c.virtualHost().hostnamePattern(),
                                    p, service.defaultSerializationFormat(),
                                    service.allowedSerializationFormats())));
                }
            });
        }

        return forServiceClasses(map, sampleRequests, sampleHttpHeaders);
    }

    static Specification forServiceClasses(Map<Class<?>, Iterable<EndpointInfo>> map,
                                           Map<Class<?>, ? extends TBase<?, ?>> sampleRequests,
                                           Map<Class<?>, Map<String, String>> sampleHttpHeaders) {
        requireNonNull(map, "map");

        final List<ServiceInfo> services = new ArrayList<>(map.size());
        final Set<ClassInfo> classes = new HashSet<>();
        map.forEach((serviceClass, endpoints) -> {
            try {
                final ServiceInfo service = ServiceInfo.of(
                        serviceClass, endpoints, sampleRequests, sampleHttpHeaders.get(serviceClass));
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

        services.forEach(s -> serviceMap.put(s.name(), s));
        classes.forEach(c -> classMap.put(c.name(), c));

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
