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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.Service;

/**
 * Metadata about a function of a {@link Service}.
 * Descriptions are stored separately in {@link ServiceSpecification#docStrings()}.
 */
@UnstableApi
public final class MethodInfo {

    private final String id;
    private final String name;
    private final TypeSignature returnTypeSignature;

    private final List<ParamInfo> parameters;
    private final boolean useParameterAsRoot;

    private final Set<TypeSignature> exceptionTypeSignatures;
    private final Set<EndpointInfo> endpoints;
    private final List<HttpHeaders> exampleHeaders;
    private final List<String> exampleRequests;
    private final List<String> examplePaths;
    private final List<String> exampleQueries;
    private final HttpMethod httpMethod;

    // TODO(minwoox): consider using fluent builder.

    /**
     * Creates a new instance.
     */
    public MethodInfo(String serviceName, String name,
                      int overloadId,
                      TypeSignature returnTypeSignature,
                      Iterable<ParamInfo> parameters,
                      Iterable<TypeSignature> exceptionTypeSignatures,
                      Iterable<EndpointInfo> endpoints,
                      HttpMethod httpMethod) {
        this(name, returnTypeSignature,
             parameters, false, exceptionTypeSignatures,
             endpoints, ImmutableList.of(),
             ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), httpMethod,
             createId(serviceName, name, overloadId, httpMethod));
    }

    /**
     * Creates a new instance.
     */
    public MethodInfo(String serviceName, String name,
                      int overloadId,
                      TypeSignature returnTypeSignature,
                      Iterable<ParamInfo> parameters,
                      Iterable<EndpointInfo> endpoints,
                      Iterable<String> examplePaths,
                      Iterable<String> exampleQueries,
                      HttpMethod httpMethod) {
        this(name, returnTypeSignature,
             parameters, false, ImmutableList.of(), endpoints, ImmutableList.of(),
             ImmutableList.of(), examplePaths, exampleQueries, httpMethod,
             createId(serviceName, name, overloadId, httpMethod));
    }

    /**
     * Creates a new instance.
     */
    public MethodInfo(String serviceName, String name,
                      TypeSignature returnTypeSignature,
                      Iterable<ParamInfo> parameters,
                      boolean useParameterAsRoot,
                      Iterable<TypeSignature> exceptionTypeSignatures,
                      Iterable<EndpointInfo> endpoints,
                      Iterable<HttpHeaders> exampleHeaders,
                      Iterable<String> exampleRequests,
                      Iterable<String> examplePaths,
                      Iterable<String> exampleQueries,
                      HttpMethod httpMethod) {
        this(name, returnTypeSignature,
             parameters, useParameterAsRoot,
             exceptionTypeSignatures, endpoints, exampleHeaders,
             exampleRequests, examplePaths, exampleQueries, httpMethod,
             createId(serviceName, name, 0, httpMethod));
    }

    MethodInfo(String name, TypeSignature returnTypeSignature,
               Iterable<ParamInfo> parameters, boolean useParameterAsRoot,
               Iterable<TypeSignature> exceptionTypeSignatures, Iterable<EndpointInfo> endpoints,
               Iterable<HttpHeaders> exampleHeaders, Iterable<String> exampleRequests,
               Iterable<String> examplePaths, Iterable<String> exampleQueries, HttpMethod httpMethod,
               String id) {
        this.id = requireNonNull(id, "id");
        this.name = requireNonNull(name, "name");

        this.returnTypeSignature = requireNonNull(returnTypeSignature, "returnTypeSignature");
        this.parameters = ImmutableList.copyOf(requireNonNull(parameters, "parameters"));
        assert !useParameterAsRoot || this.parameters.size() == 1;
        this.useParameterAsRoot = useParameterAsRoot;

        this.exceptionTypeSignatures = ImmutableSortedSet.copyOf(
                comparing(TypeSignature::signature),
                requireNonNull(exceptionTypeSignatures, "exceptionTypeSignatures"));
        this.endpoints = ImmutableSortedSet.copyOf(
                comparing(e -> e.hostnamePattern() + ':' + e.pathMapping()),
                requireNonNull(endpoints, "endpoints"));
        this.exampleHeaders = ImmutableList.copyOf(requireNonNull(exampleHeaders, "exampleHeaders"));
        this.exampleRequests = ImmutableList.copyOf(requireNonNull(exampleRequests, "exampleRequests"));

        requireNonNull(examplePaths, "examplePaths");
        final ImmutableList.Builder<String> examplePathsBuilder =
                ImmutableList.builderWithExpectedSize(Iterables.size(examplePaths));
        for (String path : examplePaths) {
            final RequestTarget reqTarget = RequestTarget.forServer(path);
            checkArgument(reqTarget != null, "examplePaths contains an invalid path: %s", path);
            examplePathsBuilder.add(reqTarget.path());
        }
        this.examplePaths = examplePathsBuilder.build();

        requireNonNull(exampleQueries, "exampleQueries");
        final ImmutableList.Builder<String> exampleQueriesBuilder =
                ImmutableList.builderWithExpectedSize(Iterables.size(exampleQueries));
        for (String query : exampleQueries) {
            final RequestTarget reqTarget = RequestTarget.forServer("/?" + query);
            checkArgument(reqTarget != null, "exampleQueries contains an invalid query string: %s", query);
            final String safeQuery = reqTarget.query();
            assert safeQuery != null;
            exampleQueriesBuilder.add(safeQuery);
        }
        this.exampleQueries = exampleQueriesBuilder.build();

        this.httpMethod = requireNonNull(httpMethod, "httpMethod");
    }

    /**
     * Returns the id of this function. It's a form of {@code serviceName/methodName/httpMethod}.
     * The {@code methodName} might have {@code -x} suffix if the method is overloaded.
     */
    @JsonProperty
    public String id() {
        return id;
    }

    /**
     * Returns the name of the function.
     */
    @JsonProperty
    public String name() {
        return name;
    }

    /**
     * Returns the return type signature of the method.
     */
    @JsonProperty
    public TypeSignature returnTypeSignature() {
        return returnTypeSignature;
    }

    /**
     * Returns the endpoints for accessing this method.
     */
    @JsonProperty
    public Set<EndpointInfo> endpoints() {
        return endpoints;
    }

    /**
     * Returns the metadata about the parameters of the function.
     */
    @JsonProperty
    public List<ParamInfo> parameters() {
        return parameters;
    }

    /**
     * Tells whether the {@link #parameters()} is used as the root when creating
     * <a href="https://json-schema.org/">JSON schema</a>. The size of the {@link #parameters()} must be one
     * if this returns {@code true}.
     */
    @JsonIgnore
    public boolean useParameterAsRoot() {
        return useParameterAsRoot;
    }

    /**
     * Returns the type signatures of exceptions declared by the function.
     */
    @JsonProperty
    public Set<TypeSignature> exceptionTypeSignatures() {
        return exceptionTypeSignatures;
    }

    /**
     * Returns the example HTTP headers of the method.
     */
    @JsonProperty
    public List<HttpHeaders> exampleHeaders() {
        return exampleHeaders;
    }

    /**
     * Returns the list of the example request serialized in a string. The format of the example request string
     * depends on the underlying RPC implementation.
     */
    @JsonProperty
    public List<String> exampleRequests() {
        return exampleRequests;
    }

    /**
     * Returns the example paths of the method.
     */
    @JsonProperty
    public List<String> examplePaths() {
        return examplePaths;
    }

    /**
     * Returns the example queries of the method.
     */
    @JsonProperty
    public List<String> exampleQueries() {
        return exampleQueries;
    }

    /**
     * Returns the HTTP method of this method.
     */
    @JsonProperty
    public HttpMethod httpMethod() {
        return httpMethod;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof MethodInfo)) {
            return false;
        }

        final MethodInfo that = (MethodInfo) o;
        return id().equals(that.id()) &&
               name().equals(that.name()) &&
               returnTypeSignature().equals(that.returnTypeSignature()) &&
               parameters().equals(that.parameters()) &&
               useParameterAsRoot() == that.useParameterAsRoot() &&
               exceptionTypeSignatures().equals(that.exceptionTypeSignatures()) &&
               endpoints().equals(that.endpoints()) &&
               httpMethod() == that.httpMethod();
    }

    @Override
    public int hashCode() {
        return Objects.hash(id(), name(), returnTypeSignature(),
                            parameters(), useParameterAsRoot(),
                            exceptionTypeSignatures(), endpoints(), httpMethod());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("id", id())
                          .add("name", name())
                          .add("returnTypeSignature", returnTypeSignature())
                          .add("parameters", parameters())
                          .add("useParameterAsRoot", useParameterAsRoot())
                          .add("exceptionTypeSignatures", exceptionTypeSignatures())
                          .add("endpoints", endpoints())
                          .add("httpMethod", httpMethod())
                          .toString();
    }

    private static String createId(String serviceName, String name, int overloadId, HttpMethod httpMethod) {
        final String methodName;
        if (overloadId > 0) {
            methodName = name + '-' + overloadId;
        } else {
            methodName = name;
        }
        return serviceName + '/' + methodName + '/' + httpMethod.name();
    }
}
