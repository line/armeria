/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.internal.server.grpc;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.google.api.HttpRule;
import com.google.common.base.MoreObjects;
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.grpc.GrpcService;

/**
 * Specifies how to support HTTP APIs from a {@link GrpcService}.
 */
@UnstableApi
public final class HttpEndpointSpecification {
    private final int order;
    private final Route route;
    private final Set<String> pathVariables;
    private final ServiceDescriptor serviceDescriptor;
    private final MethodDescriptor methodDescriptor;
    private final Map<String, Parameter> parameters;
    private final HttpRule httpRule;

    public HttpEndpointSpecification(int order,
                                     Route route,
                                     Set<String> pathVariables,
                                     ServiceDescriptor serviceDescriptor,
                                     MethodDescriptor methodDescriptor,
                                     Map<String, Parameter> parameters,
                                     HttpRule httpRule) {
        checkArgument(order >= 0, "order: %s (>= 0)", order);
        this.order = order;
        this.route = requireNonNull(route, "route");
        this.pathVariables = requireNonNull(pathVariables, "pathVariables");
        this.serviceDescriptor = requireNonNull(serviceDescriptor, "serviceDescriptor");
        this.methodDescriptor = requireNonNull(methodDescriptor, "methodDescriptor");
        this.parameters = requireNonNull(parameters, "parameters");
        this.httpRule = requireNonNull(httpRule, "httpRule");
    }

    /**
     * Returns a new {@link HttpEndpointSpecification} with the specified {@link Route}.
     */
    HttpEndpointSpecification withRoute(Route route) {
        requireNonNull(route, "route");
        if (route == this.route) {
            return this;
        }
        return new HttpEndpointSpecification(order, route, pathVariables, serviceDescriptor,
                                             methodDescriptor, parameters, httpRule);
    }

    /**
     * Returns the order of this HTTP API.
     */
    public int order() {
        return order;
    }

    /**
     * Returns the {@link Route} of this HTTP API.
     */
    public Route route() {
        return route;
    }

    /**
     * Returns the path variables of this HTTP API.
     */
    public Set<String> pathVariables() {
        return pathVariables;
    }

    /**
     * Returns the gRPC {@link ServiceDescriptor} mapped to this HTTP API.
     */
    public ServiceDescriptor serviceDescriptor() {
        return serviceDescriptor;
    }

    /**
     * Returns the gRPC {@link MethodDescriptor} mapped to this HTTP API.
     */
    public MethodDescriptor methodDescriptor() {
        return methodDescriptor;
    }

    /**
     * Returns the names and their types of gRPC request parameters.
     */
    public Map<String, Parameter> parameters() {
        return parameters;
    }

    /**
     * Returns the service name mapped to this HTTP API.
     */
    public String serviceName() {
        return serviceDescriptor.getFullName();
    }

    /**
     * Returns the method name mapped to this HTTP API.
     */
    public String methodName() {
        return methodDescriptor.getName();
    }

    /**
     * Returns the {@link HttpRule} of this HTTP API.
     */
    public HttpRule httpRule() {
        return httpRule;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof HttpEndpointSpecification)) {
            return false;
        }

        final HttpEndpointSpecification that = (HttpEndpointSpecification) o;
        return order == that.order &&
               route.equals(that.route) &&
               pathVariables.equals(that.pathVariables) &&
               serviceDescriptor.equals(that.serviceDescriptor) &&
               methodDescriptor.equals(that.methodDescriptor) &&
               parameters.equals(that.parameters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(order, route, pathVariables, serviceDescriptor, methodDescriptor, parameters);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("order", order)
                          .add("route", route)
                          .add("pathVariables", pathVariables)
                          .add("serviceDescriptor", serviceDescriptor)
                          .add("methodDescriptor", methodDescriptor)
                          .add("parameters", parameters)
                          .toString();
    }

    /**
     * Specifies details of a parameter.
     */
    public static class Parameter {
        private final JavaType type;
        private final boolean isRepeated;

        public Parameter(JavaType type, boolean isRepeated) {
            this.type = requireNonNull(type, "type");
            this.isRepeated = isRepeated;
        }

        public JavaType type() {
            return type;
        }

        public boolean isRepeated() {
            return isRepeated;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }

            if (!(o instanceof Parameter)) {
                return false;
            }

            final Parameter that = (Parameter) o;
            return type == that.type && isRepeated == that.isRepeated;
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, isRepeated);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("type", type)
                              .add("isRepeated", isRepeated)
                              .toString();
        }
    }
}
