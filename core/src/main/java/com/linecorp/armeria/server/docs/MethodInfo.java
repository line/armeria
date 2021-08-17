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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.PathAndQuery;
import com.linecorp.armeria.server.Service;

/**
 * Metadata about a function of a {@link Service}.
 */
@UnstableApi
public final class MethodInfo {

    // FIXME(trustin): Return types and exception types should also have docstrings like params have them.

    private final String name;
    private final TypeSignature returnTypeSignature;
    private final List<FieldInfo> parameters;
    private final Set<TypeSignature> exceptionTypeSignatures;
    private final Set<EndpointInfo> endpoints;
    private final List<HttpHeaders> exampleHeaders;
    private final List<String> exampleRequests;
    private final List<String> examplePaths;
    private final List<String> exampleQueries;
    private final HttpMethod httpMethod;
    @Nullable
    private final String docString;

    /**
     * Creates a new instance.
     */
    public MethodInfo(String name,
                      TypeSignature returnTypeSignature,
                      Iterable<FieldInfo> parameters,
                      Iterable<TypeSignature> exceptionTypeSignatures,
                      Iterable<EndpointInfo> endpoints,
                      HttpMethod httpMethod,
                      @Nullable String docString) {
        this(name, returnTypeSignature, parameters, exceptionTypeSignatures, endpoints,
             /* exampleHeaders */ ImmutableList.of(), /* exampleRequests */ ImmutableList.of(),
             /* examplePaths */ ImmutableList.of(), /* exampleQueries */ ImmutableList.of(),
             httpMethod, docString);
    }

    /**
     * Creates a new instance.
     */
    public MethodInfo(String name,
                      TypeSignature returnTypeSignature,
                      Iterable<FieldInfo> parameters,
                      Iterable<TypeSignature> exceptionTypeSignatures,
                      Iterable<EndpointInfo> endpoints,
                      Iterable<HttpHeaders> exampleHeaders,
                      Iterable<String> exampleRequests,
                      Iterable<String> examplePaths,
                      Iterable<String> exampleQueries,
                      HttpMethod httpMethod,
                      @Nullable String docString) {
        this.name = requireNonNull(name, "name");

        this.returnTypeSignature = requireNonNull(returnTypeSignature, "returnTypeSignature");
        this.parameters = ImmutableList.copyOf(requireNonNull(parameters, "parameters"));
        this.exceptionTypeSignatures =
                ImmutableSortedSet.copyOf(
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
            final PathAndQuery pathAndQuery = PathAndQuery.parse(path);
            checkArgument(pathAndQuery != null, "examplePaths contains an invalid path: %s", path);
            examplePathsBuilder.add(pathAndQuery.path());
        }
        this.examplePaths = examplePathsBuilder.build();

        requireNonNull(exampleQueries, "exampleQueries");
        final ImmutableList.Builder<String> exampleQueriesBuilder =
                ImmutableList.builderWithExpectedSize(Iterables.size(exampleQueries));
        for (String query : exampleQueries) {
            final PathAndQuery pathAndQuery = PathAndQuery.parse('?' + query);
            checkArgument(pathAndQuery != null, "exampleQueries contains an invalid query string: %s", query);
            exampleQueriesBuilder.add(pathAndQuery.query());
        }
        this.exampleQueries = exampleQueriesBuilder.build();

        this.httpMethod = requireNonNull(httpMethod, "httpMethod");
        this.docString = Strings.emptyToNull(docString);
    }

    /**
     * Returns the name of the function.
     */
    @JsonProperty
    public String name() {
        return name;
    }

    /**
     * Returns the signature of the return type of the function.
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
    public List<FieldInfo> parameters() {
        return parameters;
    }

    /**
     * Returns the metadata about the exceptions declared by the function.
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

    /**
     * Returns the documentation string of the function.
     */
    @JsonProperty
    @JsonInclude(Include.NON_NULL)
    @Nullable
    public String docString() {
        return docString;
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
        return name().equals(that.name()) &&
               returnTypeSignature().equals(that.returnTypeSignature()) &&
               parameters().equals(that.parameters()) &&
               exceptionTypeSignatures().equals(that.exceptionTypeSignatures()) &&
               endpoints().equals(that.endpoints()) &&
               httpMethod() == that.httpMethod();
    }

    @Override
    public int hashCode() {
        return Objects.hash(name(), returnTypeSignature(), parameters(), exceptionTypeSignatures(),
                            endpoints(), httpMethod());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("name", name())
                          .add("returnTypeSignature", returnTypeSignature())
                          .add("parameters", parameters())
                          .add("exceptionTypeSignatures", exceptionTypeSignatures())
                          .add("endpoints", endpoints())
                          .add("httpMethod", httpMethod())
                          .toString();
    }
}
