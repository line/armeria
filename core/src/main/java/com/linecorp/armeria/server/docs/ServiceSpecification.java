/*
 * Copyright 2017 LINE Corporation
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

import static com.google.common.collect.ImmutableSortedMap.toImmutableSortedMap;
import static java.util.Objects.requireNonNull;

import java.util.Comparator;
import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.Streams;

import com.linecorp.armeria.server.Service;

/**
 * The specification of one or more {@link Service}s that provides their {@link ServiceInfo}s and
 * {@link ClassInfo}s.
 */
public final class ServiceSpecification {

    /**
     * Merges the specified {@link ServiceSpecification}s into one.
     */
    public static ServiceSpecification merge(Iterable<ServiceSpecification> specs) {
        return new ServiceSpecification(
                Streams.stream(specs).flatMap(s -> s.services().values().stream())::iterator,
                Streams.stream(specs).flatMap(s -> s.classes().values().stream())::iterator);
    }

    private final Map<String, ServiceInfo> services;
    private final Map<String, ClassInfo> classes;

    /**
     * Creates a new instance.
     */
    public ServiceSpecification(Iterable<ServiceInfo> services, Iterable<ClassInfo> classes) {
        this.services = Streams.stream(requireNonNull(services, "services"))
                               .collect(toImmutableSortedMap(Comparator.naturalOrder(),
                                                             ServiceInfo::name, Function.identity()));
        this.classes = Streams.stream(requireNonNull(classes, "classes"))
                              .collect(toImmutableSortedMap(Comparator.naturalOrder(),
                                                            ClassInfo::name, Function.identity()));
    }

    /**
     * Returns the metadata about the services in this specification.
     */
    @JsonProperty
    public Map<String, ServiceInfo> services() {
        return services;
    }

    /**
     * Returns the metadata about the structs, enums and exceptions related with the services
     * in this specification.
     */
    @JsonProperty
    public Map<String, ClassInfo> classes() {
        return classes;
    }
}
