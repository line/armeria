/*
 * Copyright 2017 LINE Corporation
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

import static com.google.common.collect.ImmutableSortedSet.toImmutableSortedSet;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.RoutePathType;
import com.linecorp.armeria.server.Service;

/**
 * The specification of one or more {@link Service}s that provides their {@link ServiceInfo}s and
 * {@link DescriptiveTypeInfo}s.
 */
@UnstableApi
public final class ServiceSpecification {

    private static final ServiceSpecification emptyServiceSpecification =
            new ServiceSpecification(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                                     ImmutableList.of(), ImmutableList.of(), null);

    /**
     * Merges the specified {@link ServiceSpecification}s into one.
     */
    public static ServiceSpecification merge(Iterable<ServiceSpecification> specs, Route docServiceRoute) {
        return new ServiceSpecification(
                Streams.stream(specs).flatMap(s -> s.services().stream())::iterator,
                Streams.stream(specs).flatMap(s -> s.enums().stream())::iterator,
                Streams.stream(specs).flatMap(s -> s.structs().stream())::iterator,
                Streams.stream(specs).flatMap(s -> s.exceptions().stream())::iterator,
                ImmutableList.of(),
                docServiceRoute
        );
    }

    /**
     * Generates a new {@link ServiceSpecification} from the specified {@link ServiceInfo}s and
     * the factory {@link Function} that creates {@link DescriptiveTypeInfo}s for the enum, struct or
     * exception types referred by the specified {@link ServiceInfo}s.
     */
    public static ServiceSpecification generate(
            Iterable<ServiceInfo> services,
            Function<DescriptiveTypeSignature, ? extends DescriptiveTypeInfo> descriptiveTypeInfoFactory) {
        if (Iterables.isEmpty(services)) {
            return emptyServiceSpecification;
        }

        // Collect all descriptive types referred by the services.
        final Set<DescriptiveTypeSignature> descriptiveTypes =
                Streams.stream(services)
                       .flatMap(s -> s.findDescriptiveTypes().stream())
                       .collect(toImmutableSortedSet(comparing(TypeSignature::name)));

        final Map<String, EnumInfo> enums = new HashMap<>();
        final Map<String, StructInfo> structs = new HashMap<>();
        final Map<String, ExceptionInfo> exceptions = new HashMap<>();

        generateDescriptiveTypeInfos(descriptiveTypeInfoFactory, enums, structs, exceptions, descriptiveTypes);

        return new ServiceSpecification(services, enums.values(), structs.values(), exceptions.values());
    }

    private static void generateDescriptiveTypeInfos(
            Function<DescriptiveTypeSignature, ? extends DescriptiveTypeInfo> descriptiveTypeInfoFactory,
            Map<String, EnumInfo> enums, Map<String, StructInfo> structs,
            Map<String, ExceptionInfo> exceptions, Set<DescriptiveTypeSignature> descriptiveTypes) {

        descriptiveTypes.forEach(type -> {
            final String typeName = type.name();
            if (enums.containsKey(typeName) ||
                structs.containsKey(typeName) ||
                exceptions.containsKey(typeName)) {
                return;
            }

            final DescriptiveTypeInfo newInfo = descriptiveTypeInfoFactory.apply(type);
            if (newInfo instanceof EnumInfo) {
                enums.put(newInfo.name(), (EnumInfo) newInfo);
                return;
            }
            if (newInfo instanceof StructInfo) {
                structs.put(newInfo.name(), (StructInfo) newInfo);
            } else if (newInfo instanceof ExceptionInfo) {
                exceptions.put(newInfo.name(), (ExceptionInfo) newInfo);
            } else {
                throw new Error(); // Should never reach here.
            }

            generateDescriptiveTypeInfos(descriptiveTypeInfoFactory, enums, structs, exceptions,
                                         newInfo.findDescriptiveTypes());
        });
    }

    private final Set<ServiceInfo> services;
    private final Set<EnumInfo> enums;
    private final Set<StructInfo> structs;
    private final Set<ExceptionInfo> exceptions;
    private final List<HttpHeaders> exampleHeaders;
    @Nullable
    private final Route docServiceRoute;

    /**
     * Creates a new instance.
     */
    @VisibleForTesting
    public ServiceSpecification(Iterable<ServiceInfo> services,
                                Iterable<EnumInfo> enums,
                                Iterable<StructInfo> structs,
                                Iterable<ExceptionInfo> exceptions) {
        this(services, enums, structs, exceptions, ImmutableList.of(), null);
    }

    /**
     * Creates a new instance.
     */
    @VisibleForTesting
    public ServiceSpecification(Iterable<ServiceInfo> services,
                                Iterable<EnumInfo> enums,
                                Iterable<StructInfo> structs,
                                Iterable<ExceptionInfo> exceptions,
                                Iterable<HttpHeaders> exampleHeaders
    ) {
        this(services, enums, structs, exceptions, exampleHeaders, null);
    }

    /**
     * Creates a new instance.
     */
    public ServiceSpecification(Iterable<ServiceInfo> services,
                                Iterable<EnumInfo> enums,
                                Iterable<StructInfo> structs,
                                Iterable<ExceptionInfo> exceptions,
                                Iterable<HttpHeaders> exampleHeaders,
                                @Nullable Route docServiceRoute) {
        this.services = Streams.stream(requireNonNull(services, "services"))
                               .collect(toImmutableSortedSet(comparing(ServiceInfo::name)));
        this.enums = collectDescriptiveTypeInfo(enums, "enums");
        this.structs = collectStructInfo(structs);
        this.exceptions = collectDescriptiveTypeInfo(exceptions, "exceptions");
        this.exampleHeaders = ImmutableList.copyOf(requireNonNull(exampleHeaders, "exampleHeaders"));
        if (docServiceRoute != null && docServiceRoute.pathType() == RoutePathType.PREFIX) {
            this.docServiceRoute = docServiceRoute;
        } else {
            this.docServiceRoute = null;
        }
    }

    private static <T extends DescriptiveTypeInfo> Set<T> collectDescriptiveTypeInfo(
            Iterable<T> values, String name) {
        return Streams.stream(requireNonNull(values, name))
                      .collect(toImmutableSortedSet(comparing(DescriptiveTypeInfo::name)));
    }

    private static Set<StructInfo> collectStructInfo(Iterable<StructInfo> structInfos) {
        requireNonNull(structInfos, "structInfos");
        return Streams.stream(structInfos)
                      .collect(Collectors.toMap(StructInfo::name, Function.identity(),
                                                (a, b) -> {
                                                    // If the name is duplicate, prefer the one with alias.
                                                    if (a.alias() != null) {
                                                        return a;
                                                    }
                                                    if (b.alias() != null) {
                                                        return b;
                                                    }
                                                    return a;
                                                }))
                      .values().stream()
                      .collect(toImmutableSortedSet(comparing(StructInfo::name)));
    }

    /**
     * Returns the metadata about the services in this specification.
     */
    @JsonProperty
    public Set<ServiceInfo> services() {
        return services;
    }

    /**
     * Returns the metadata about the enums related with the services in this specification.
     */
    @JsonProperty
    public Set<EnumInfo> enums() {
        return enums;
    }

    /**
     * Returns the metadata about the structs related with the services in this specification.
     */
    @JsonProperty
    public Set<StructInfo> structs() {
        return structs;
    }

    /**
     * Returns the metadata about the exceptions related with the services in this specification.
     */
    @JsonProperty
    public Set<ExceptionInfo> exceptions() {
        return exceptions;
    }

    /**
     * Returns the example HTTP headers of the services in this specification.
     */
    @JsonProperty
    public List<HttpHeaders> exampleHeaders() {
        return exampleHeaders;
    }

    /**
     * Returns the path pattern string of the {@link DocService} mount location on server.
     */
    @JsonProperty
    @Nullable
    public Route docServiceRoute() {
        return docServiceRoute;
    }
}
