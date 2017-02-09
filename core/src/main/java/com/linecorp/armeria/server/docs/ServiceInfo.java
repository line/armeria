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

import static com.google.common.collect.ImmutableSortedMap.toImmutableSortedMap;
import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.Streams;

import com.linecorp.armeria.server.Service;

/**
 * Metadata about a {@link Service}.
 */
public final class ServiceInfo {

    private final String name;
    private final Map<String, FunctionInfo> functions;
    private final Map<String, ClassInfo> classes;
    private final Map<String, EndpointInfo> endpoints;
    private final String docString;
    private final String sampleHttpHeaders;

    /**
     * Creates a new instance.
     */
    public ServiceInfo(String name,
                       Iterable<FunctionInfo> functions,
                       Iterable<ClassInfo> classes,
                       Iterable<EndpointInfo> endpoints,
                       @Nullable String docString,
                       @Nullable String sampleHttpHeaders) {

        this.name = requireNonNull(name, "name");

        requireNonNull(functions, "functions");
        requireNonNull(classes, "classes");
        requireNonNull(endpoints, "endpoints");

        this.functions = Streams.stream(functions)
                                .collect(toImmutableSortedMap(Comparator.naturalOrder(),
                                                              FunctionInfo::name, Function.identity()));
        this.classes = Streams.stream(classes)
                              .collect(toImmutableSortedMap(Comparator.naturalOrder(),
                                                            ClassInfo::name, Function.identity()));
        this.endpoints = Streams.stream(endpoints)
                                .collect(toImmutableSortedMap(Comparator.naturalOrder(),
                                                              e -> e.hostnamePattern() + ':' + e.path(),
                                                              Function.identity()));
        this.docString = docString;
        this.sampleHttpHeaders = sampleHttpHeaders;
    }

    /**
     * Returns the fully qualified type name of the service.
     */
    @JsonProperty
    public String name() {
        return name;
    }

    /**
     * Returns the simple type name of the service, which does not contain the package name.
     */
    @JsonProperty
    public String simpleName() {
        return name.substring(name.lastIndexOf('.') + 1);
    }

    /**
     * Returns the metadata about the functions available in the service.
     */
    @JsonProperty
    public Map<String, FunctionInfo> functions() {
        return functions;
    }

    /**
     * Returns the metadata about the structs, enums and exceptions related with the service.
     */
    @JsonProperty
    public Map<String, ClassInfo> classes() {
        return classes;
    }

    /**
     * Returns the endpoints exposed by the service.
     */
    @JsonProperty
    public Collection<EndpointInfo> endpoints() {
        return endpoints.values();
    }

    /**
     * Returns the documentation string.
     */
    @JsonProperty
    public String docString() {
        return docString;
    }

    /**
     * Returns the sample HTTP headers of the service, serialized in JSON format.
     */
    @JsonProperty
    public String sampleHttpHeaders() {
        return sampleHttpHeaders;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final ServiceInfo that = (ServiceInfo) o;
        return Objects.equals(name, that.name) &&
               Objects.equals(functions, that.functions) &&
               Objects.equals(classes, that.classes) &&
               Objects.equals(endpoints, that.endpoints);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, functions, classes, endpoints);
    }

    @Override
    public String toString() {
        return "ServiceInfo{" +
               "name='" + name() + '\'' +
               ", functions=" + functions() +
               ", classes=" + classes() +
               ", endpoints=" + endpoints() +
               ", docString=" + docString() +
               ", sampleHttpHeaders=" + sampleHttpHeaders() +
               '}';
    }
}
