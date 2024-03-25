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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import com.linecorp.armeria.common.HttpHeaders;
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
    private final DescriptionInfo descriptionInfo;

    /**
     * Creates a new instance.
     */
    public ServiceInfo(String name,
                       Iterable<MethodInfo> methods) {
        this(name, methods, DescriptionInfo.empty());
    }

    /**
     * Creates a new instance.
     */
    public ServiceInfo(String name,
                       Iterable<MethodInfo> methods,
                       DescriptionInfo descriptionInfo) {
        this(name, methods, ImmutableList.of(), descriptionInfo);
    }

    /**
     * Creates a new instance.
     */
    public ServiceInfo(String name,
                       Iterable<MethodInfo> methods,
                       Iterable<HttpHeaders> exampleHeaders,
                       DescriptionInfo descriptionInfo) {

        this.name = requireNonNull(name, "name");
        this.methods = mergeEndpoints(requireNonNull(methods));
        this.exampleHeaders = ImmutableList.copyOf(requireNonNull(exampleHeaders, "exampleHeaders"));
        this.descriptionInfo = requireNonNull(descriptionInfo, "descriptionInfo");
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
     * Returns a new {@link ServiceInfo} with the specified {@link MethodInfo}s.
     * Returns {@code this} if this {@link ServiceInfo} has the same {@link MethodInfo}s.
     */
    public ServiceInfo withMethods(Iterable<MethodInfo> methods) {
        requireNonNull(methods, "methods");
        if (methods.equals(this.methods)) {
            return this;
        }

        return new ServiceInfo(name, methods, exampleHeaders, descriptionInfo);
    }

    /**
     * Merges the {@link MethodInfo}s with the same method {@link MethodInfo#id()}
     * into a single {@link MethodInfo}. Note that only the {@link EndpointInfo}s are merged
     * because the {@link MethodInfo}s being merged always have the same
     * {@code exampleHeaders} and {@code exampleRequests}.
     */
    @VisibleForTesting
    static Set<MethodInfo> mergeEndpoints(Iterable<MethodInfo> methodInfos) {
        final Map<String, MethodInfo> methodInfoMap = new HashMap<>();
        for (MethodInfo methodInfo : methodInfos) {
            methodInfoMap.compute(methodInfo.id(), (key, value) -> {
                if (value == null) {
                    return methodInfo;
                }
                final Set<EndpointInfo> endpointInfos =
                        Sets.union(value.endpoints(), methodInfo.endpoints());
                return new MethodInfo(value.name(), value.returnTypeSignature(), value.parameters(),
                                      value.useParameterAsRoot(), value.exceptionTypeSignatures(),
                                      endpointInfos, value.exampleHeaders(),
                                      value.exampleRequests(), value.examplePaths(), value.exampleQueries(),
                                      value.httpMethod(), value.descriptionInfo(), value.id());
            });
        }
        return ImmutableSortedSet
                .orderedBy(comparing(MethodInfo::name).thenComparing(MethodInfo::httpMethod)
                                                      .thenComparing(MethodInfo::id))
                .addAll(methodInfoMap.values())
                .build();
    }

    /**
     * Returns all enum, struct and exception {@link TypeSignature}s referred to by this service.
     */
    public Set<DescriptiveTypeSignature> findDescriptiveTypes() {
        final Set<DescriptiveTypeSignature> requestDescriptiveTypes = findDescriptiveTypes(true);
        final Set<DescriptiveTypeSignature> responseDescriptiveType = findDescriptiveTypes(false);
        final int estimatedSize = requestDescriptiveTypes.size() + responseDescriptiveType.size();
        return ImmutableSet.<DescriptiveTypeSignature>builderWithExpectedSize(estimatedSize)
                           .addAll(requestDescriptiveTypes)
                           .addAll(responseDescriptiveType)
                           .build();
    }

    /**
     * Returns all {@link TypeSignature} of {@link MethodInfo#parameters()} of {@link #methods()} if
     * {@code request} is set to true. Otherwise, returns all {@link MethodInfo#returnTypeSignature()} and
     * {@link MethodInfo#exceptionTypeSignatures()} of the {@link #methods()}.
     */
    public Set<DescriptiveTypeSignature> findDescriptiveTypes(boolean request) {
        final Set<DescriptiveTypeSignature> collectedDescriptiveTypes = new HashSet<>();
        methods().forEach(m -> {
            if (request) {
                m.parameters().forEach(p -> findDescriptiveTypes(collectedDescriptiveTypes, p.typeSignature()));
            } else {
                findDescriptiveTypes(collectedDescriptiveTypes, m.returnTypeSignature());
                m.exceptionTypeSignatures().forEach(s -> findDescriptiveTypes(collectedDescriptiveTypes, s));
            }
        });
        return ImmutableSet.copyOf(collectedDescriptiveTypes);
    }

    static void findDescriptiveTypes(Set<DescriptiveTypeSignature> collectedDescriptiveTypes,
                                     TypeSignature typeSignature) {
        final TypeSignatureType type = typeSignature.type();
        if (type.hasTypeDescriptor()) {
            collectedDescriptiveTypes.add((DescriptiveTypeSignature) typeSignature);
            return;
        }

        if (typeSignature instanceof ContainerTypeSignature) {
            ((ContainerTypeSignature) typeSignature)
                    .typeParameters()
                    .forEach(p -> findDescriptiveTypes(collectedDescriptiveTypes, p));
        }
    }

    /**
     * Returns the description information of the service.
     * If not available, {@link DescriptionInfo#empty()} is returned.
     */
    @JsonProperty
    public DescriptionInfo descriptionInfo() {
        return descriptionInfo;
    }

    /**
     * Returns a new {@link ServiceInfo} with the specified {@link DescriptionInfo}.
     * Returns {@code this} if this {@link ServiceInfo} has the same {@link DescriptionInfo}.
     */
    public ServiceInfo withDescriptionInfo(DescriptionInfo descriptionInfo) {
        requireNonNull(descriptionInfo, "descriptionInfo");
        if (descriptionInfo.equals(this.descriptionInfo)) {
            return this;
        }

        return new ServiceInfo(name, methods, exampleHeaders, descriptionInfo);
    }

    /**
     * Returns the example HTTP headers of the service.
     */
    @JsonProperty
    public List<HttpHeaders> exampleHeaders() {
        return exampleHeaders;
    }

    /**
     * Returns a new {@link ServiceInfo} with the specified example headers.
     * Returns {@code this} if this {@link ServiceInfo} has the same example headers.
     */
    public ServiceInfo withExampleHeaders(Iterable<HttpHeaders> exampleHeaders) {
        requireNonNull(exampleHeaders, "exampleHeaders");
        if (exampleHeaders.equals(this.exampleHeaders)) {
            return this;
        }

        return new ServiceInfo(name, methods, exampleHeaders, descriptionInfo);
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
                          .add("descriptionInfo", descriptionInfo)
                          .toString();
    }
}
