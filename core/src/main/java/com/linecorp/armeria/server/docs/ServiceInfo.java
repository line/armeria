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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.server.Service;

/**
 * Metadata about a {@link Service}.
 */
public final class ServiceInfo {

    private final String name;
    private final Set<MethodInfo> methods;
    private final List<HttpHeaders> exampleHttpHeaders;
    private final String docString;

    /**
     * Creates a new instance.
     */
    public ServiceInfo(String name,
                       Iterable<MethodInfo> methods) {
        this(name, methods, ImmutableList.of(), null);
    }

    /**
     * Creates a new instance.
     */
    public ServiceInfo(String name,
                       Iterable<MethodInfo> methods,
                       Iterable<HttpHeaders> exampleHttpHeaders,
                       @Nullable String docString) {

        this.name = requireNonNull(name, "name");

        requireNonNull(methods, "methods");

        this.methods = ImmutableSortedSet.copyOf(comparing(MethodInfo::name), methods);
        this.exampleHttpHeaders = Streams.stream(requireNonNull(exampleHttpHeaders, "exampleHttpHeaders"))
                                         .map(HttpHeaders::copyOf)
                                         .map(HttpHeaders::asImmutable)
                                         .collect(toImmutableList());
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
    public List<HttpHeaders> exampleHttpHeaders() {
        return exampleHttpHeaders;
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
                .add("exampleHttpHeaders", exampleHttpHeaders)
                .add("docstring", docString)
                .toString();
    }
}
