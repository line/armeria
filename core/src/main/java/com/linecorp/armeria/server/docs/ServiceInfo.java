/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.server.docs;

import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.Service;

/**
 * Metadata about a {@link Service}.
 */
@UnstableApi
public final class ServiceInfo {

    private final String name;
    private final Set<MethodInfo> methods;
    private final List<HttpHeaders> exampleHeaders;
    @Nullable
    private final String docString;

    /**
     * Creates a new instance.
     */
    public ServiceInfo(String name,
                       Iterable<MethodInfo> methods) {
        this(name, methods, null);
    }

    /**
     * Creates a new instance.
     */
    public ServiceInfo(String name,
                       Iterable<MethodInfo> methods,
                       @Nullable String docString) {
        this(name, methods, ImmutableList.of(), docString);
    }

    /**
     * Creates a new instance.
     */
    public ServiceInfo(String name,
                       Iterable<MethodInfo> methods,
                       Iterable<HttpHeaders> exampleHeaders,
                       @Nullable String docString) {

        this.name = requireNonNull(name, "name");
        this.methods = mergeEndpoints(requireNonNull(methods));
        this.exampleHeaders = ImmutableList.copyOf(requireNonNull(exampleHeaders, "exampleHeaders"));
        this.docString = Strings.emptyToNull(docString);
    }

    /**
     * Returns the fully qualified type name of the service.
     */
    @JsonProperty
    public String name() {
        return name;
    }

    /**
     * Returns the metadata about the methods available in the service.
     */
    @JsonProperty
    public Set<MethodInfo> methods() {
        return methods;
    }

    /**
     * Merges the {@link MethodInfo}s with the same method name and {@link HttpMethod} pair
     * into a single {@link MethodInfo}. Note that only the {@link EndpointInfo}s are merged
     * because the {@link MethodInfo}s being merged always have the same
     * {@code exampleHeaders} and {@code exampleRequests}.
     */
    @VisibleForTesting
    static Set<MethodInfo> mergeEndpoints(Iterable<MethodInfo> methodInfos) {
        final Map<List<Object>, MethodInfo> methodInfoMap = new HashMap<>();
        for (MethodInfo methodInfo : methodInfos) {
            final List<Object> mergeKey = ImmutableList.of(methodInfo.name(), methodInfo.httpMethod());
            methodInfoMap.compute(mergeKey, (key, value) -> {
                if (value == null) {
                    return methodInfo;
                } else {
                    final Set<EndpointInfo> endpointInfos =
                            Sets.union(value.endpoints(), methodInfo.endpoints());
                    return new MethodInfo(value.name(), value.returnTypeSignature(),
                                          value.parameters(), value.exceptionTypeSignatures(),
                                          endpointInfos, value.exampleHeaders(),
                                          value.exampleRequests(), value.examplePaths(), value.exampleQueries(),
                                          value.httpMethod(), value.docString());
                }
            });
        }
        return ImmutableSortedSet
                .orderedBy(comparing(MethodInfo::name).thenComparing(MethodInfo::httpMethod))
                .addAll(methodInfoMap.values())
                .build();
    }

    /**
     * Returns all enum, struct and exception {@link TypeSignature}s referred to by this service.
     */
    public Set<TypeSignature> findNamedTypes() {
        final Set<TypeSignature> collectedNamedTypes = new HashSet<>();
        methods().forEach(m -> {
            findNamedTypes(collectedNamedTypes, m.returnTypeSignature());
            m.parameters().forEach(p -> findNamedTypes(collectedNamedTypes, p.typeSignature()));
            m.exceptionTypeSignatures().forEach(s -> findNamedTypes(collectedNamedTypes, s));
        });

        return ImmutableSortedSet.copyOf(comparing(TypeSignature::name), collectedNamedTypes);
    }

    static void findNamedTypes(Set<TypeSignature> collectedNamedTypes, TypeSignature typeSignature) {
        if (typeSignature.isNamed()) {
            collectedNamedTypes.add(typeSignature);
        }

        if (typeSignature.isContainer()) {
            typeSignature.typeParameters().forEach(p -> findNamedTypes(collectedNamedTypes, p));
        }
    }

    /**
     * Returns the documentation string.
     */
    @JsonProperty
    @JsonInclude(Include.NON_NULL)
    @Nullable
    public String docString() {
        return docString;
    }

    /**
     * Returns the example HTTP headers of the service.
     */
    @JsonProperty
    public List<HttpHeaders> exampleHeaders() {
        return exampleHeaders;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof ServiceInfo)) {
            return false;
        }

        final ServiceInfo that = (ServiceInfo) o;
        return name.equals(that.name) && methods.equals(that.methods);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, methods);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("name", name)
                          .add("methods", methods)
                          .add("exampleHeaders", exampleHeaders)
                          .add("docstring", docString)
                          .toString();
    }
}
