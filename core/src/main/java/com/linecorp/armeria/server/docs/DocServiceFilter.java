/*
 *  Copyright 2019 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.server.docs;

import static com.linecorp.armeria.server.docs.DocServiceBuilder.ALL_SERVICES;
import static com.linecorp.armeria.server.docs.DocServiceBuilder.NO_SERVICE;
import static java.util.Objects.requireNonNull;

import java.util.regex.Pattern;

import com.linecorp.armeria.internal.annotation.AnnotatedHttpDocServicePlugin;

/**
 * A filter which is used when building a {@link DocService}.
 *
 * @see DocServiceBuilder#include(DocServiceFilter)
 * @see DocServiceBuilder#exclude(DocServiceFilter)
 */
@FunctionalInterface
public interface DocServiceFilter {

    /**
     * Returns a {@link DocServiceFilter} which always returns {@code true}.
     */
    static DocServiceFilter allServices() {
        return ALL_SERVICES;
    }

    /**
     * Returns a {@link DocServiceFilter} which always returns {@code false}.
     */
    static DocServiceFilter noService() {
        return NO_SERVICE;
    }

    /**
     * Returns a {@link DocServiceFilter} which returns {@code true} when the filter is invoked in the Thrift
     * plugin.
     */
    static DocServiceFilter thriftOnly() {
        // Hardcode the class name because this does not have Thrift dependency.
        return pluginName("com.linecorp.armeria.server.thrift.ThriftDocServicePlugin");
    }

    /**
     * Returns a {@link DocServiceFilter} which returns {@code true} when the filter is invoked in the gRPC
     * plugin.
     */
    static DocServiceFilter grpcOnly() {
        // Hardcode the class name because this does not have gRPC dependency.
        return pluginName("com.linecorp.armeria.server.grpc.GrpcDocServicePlugin");
    }

    /**
     * Returns a {@link DocServiceFilter} which returns {@code true} when the filter is invoked in the annotated
     * HTTP plugin.
     */
    static DocServiceFilter annotatedHttpOnly() {
        return pluginName(AnnotatedHttpDocServicePlugin.class.getName());
    }

    /**
     * Returns a {@link DocServiceFilter} which returns {@code true} when the name of the plugin matches the
     * specified {@code pluginName}.
     */
    static DocServiceFilter pluginName(String pluginName) {
        requireNonNull(pluginName, "pluginName");
        return (plugin, service, method) -> pluginName.equals(plugin);
    }

    /**
     * Returns a {@link DocServiceFilter} which returns {@code true} when the name of the service matches the
     * specified {@code serviceName}.
     */
    static DocServiceFilter serviceName(String serviceName) {
        requireNonNull(serviceName, "serviceName");
        return (plugin, service, method) -> serviceName.equals(service);
    }

    /**
     * Returns a {@link DocServiceFilter} which returns {@code true} when the name of the plugin and service
     * matches the specified {@code pluginName} and {@code serviceName}.
     */
    static DocServiceFilter serviceName(String pluginName, String serviceName) {
        requireNonNull(pluginName, "pluginName");
        requireNonNull(serviceName, "serviceName");
        return (plugin, service, method) -> pluginName.equals(plugin) && serviceName.equals(service);
    }

    /**
     * Returns a {@link DocServiceFilter} which returns {@code true} when the name of the method matches the
     * specified {@code methodName}.
     */
    static DocServiceFilter methodName(String methodName) {
        requireNonNull(methodName, "methodName");
        return (plugin, service, method) -> methodName.equals(method);
    }

    /**
     * Returns a {@link DocServiceFilter} which returns {@code true} when the name of the service and method
     * matches the specified {@code serviceName} and {@code methodName}.
     */
    static DocServiceFilter methodName(String serviceName, String methodName) {
        return (plugin, service, method) -> serviceName.equals(service) && methodName.equals(method);
    }

    /**
     * Returns a {@link DocServiceFilter} which returns {@code true} when the name of the plugin, service and
     * method matches the specified {@code pluginName}, {@code serviceName} and {@code methodName}.
     */
    static DocServiceFilter methodName(String pluginName, String serviceName, String methodName) {
        return (plugin, service, method) -> pluginName.equals(plugin) && serviceName.equals(service) &&
                                            methodName.equals(method);
    }

    /**
     * Returns a {@link DocServiceFilter} which returns {@code true} when the name of the service matches the
     * specified {@code regex}.
     */
    static DocServiceFilter servicePattern(String regex) {
        requireNonNull(regex, "regex");
        final Pattern pattern = Pattern.compile(regex);
        return (plugin, service, method) -> pattern.matcher(service).matches();
    }

    /**
     * Returns a {@link DocServiceFilter} which returns {@code true} when the name of the method matches the
     * specified {@code regex}.
     */
    static DocServiceFilter methodPattern(String regex) {
        requireNonNull(regex, "regex");
        final Pattern pattern = Pattern.compile(regex);
        return (plugin, service, method) -> pattern.matcher(method).matches();
    }

    /**
     * Evaluates this {@link DocServiceFilter} on the specified {@code pluginName}, {@code serviceName} and
     * {@code methodName}.
     */
    boolean filter(String pluginName, String className, String methodName);

    /**
     * Returns a composed {@link DocServiceFilter} that represents a short-circuiting logical {@code OR} of
     * this filter and {@code other}. When evaluating the composed filter, if this filter returns {@code true},
     * then the {@code other} filter is not evaluated.
     */
    default DocServiceFilter or(DocServiceFilter other) {
        requireNonNull(other, "other");
        return (pluginName, className, methodName) -> filter(pluginName, className, methodName) ||
                                                      other.filter(pluginName, className, methodName);
    }

    /**
     * Returns a composed {@link DocServiceFilter} that represents a short-circuiting logical {@code AND} of
     * this filter and {@code other}. When evaluating the composed filter, if this filter returns {@code false},
     * then the {@code other} filter is not evaluated.
     */
    default DocServiceFilter and(DocServiceFilter other) {
        requireNonNull(other, "other");
        return (pluginName, className, methodName) -> filter(pluginName, className, methodName) &&
                                                      other.filter(pluginName, className, methodName);
    }
}
