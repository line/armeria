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

import static java.util.Objects.requireNonNull;

import java.util.regex.Pattern;

/**
 * A filter which includes or excludes service methods when building a {@link DocService}.
 * You can compose as many filters as you want to include or exclude service methods using
 * {@link DocServiceBuilder#include(DocServiceFilter)} and {@link DocServiceBuilder#exclude(DocServiceFilter)}.
 * For example:
 *
 * <pre>{@code
 * // Include Thrift and gRPC only.
 * DocServiceBuilder builder = DocService.builder();
 * DocServiceFilter filter = DocServiceFilter.ofThrift().or(DocServiceFilter.ofGrpc());
 * builder.include(filter);
 *
 * // Include only "Foo" service in Thrift.
 * DocServiceFilter filter = DocServiceFilter.ofThrift().and(DocServiceFilter.ofServiceName("com.example.Foo"));
 * builder.include(filter);
 *
 * // Include all except annotated service and methods whose name is "bar".
 * DocServiceFilter filter = DocServiceFilter.ofAnnotated().or(DocServiceFilter.ofMethodName("bar"));
 * builder.exclude(filter);
 * }</pre>
 */
@FunctionalInterface
public interface DocServiceFilter {

    /**
     * Returns a {@link DocServiceFilter} which returns {@code true} only for the services detected by the
     * Thrift plugin.
     */
    static DocServiceFilter ofThrift() {
        return ofPluginName("thrift");
    }

    /**
     * Returns a {@link DocServiceFilter} which returns {@code true} only for the services detected by the
     * gRPC plugin.
     */
    static DocServiceFilter ofGrpc() {
        return ofPluginName("grpc");
    }

    /**
     * Returns a {@link DocServiceFilter} which returns {@code true} only for the services detected by the
     * annotated service plugin.
     */
    static DocServiceFilter ofAnnotated() {
        return ofPluginName("annotated");
    }

    /**
     * Returns a {@link DocServiceFilter} which returns {@code true} only for the services detected by the
     * annotated service plugin.
     */
    static DocServiceFilter ofHttp() {
        return ofPluginName("http");
    }

    /**
     * Returns a {@link DocServiceFilter} which returns {@code true} when the name of the plugin matches the
     * specified {@code pluginName}. For Thrift, gRPC and Annotated service, use {@link #ofThrift()},
     * {@link #ofGrpc()} and {@link #ofAnnotated()}, respectively.
     */
    static DocServiceFilter ofPluginName(String pluginName) {
        requireNonNull(pluginName, "pluginName");
        return (plugin, service, method) -> pluginName.equals(plugin);
    }

    /**
     * Returns a {@link DocServiceFilter} which returns {@code true} when the name of the service matches the
     * specified {@code serviceName}.
     */
    static DocServiceFilter ofServiceName(String serviceName) {
        requireNonNull(serviceName, "serviceName");
        return (plugin, service, method) -> serviceName.equals(service);
    }

    /**
     * Returns a {@link DocServiceFilter} which returns {@code true} when the name of the plugin and service
     * matches the specified {@code pluginName} and {@code serviceName}.
     */
    static DocServiceFilter ofServiceName(String pluginName, String serviceName) {
        requireNonNull(pluginName, "pluginName");
        requireNonNull(serviceName, "serviceName");
        return (plugin, service, method) -> pluginName.equals(plugin) && serviceName.equals(service);
    }

    /**
     * Returns a {@link DocServiceFilter} which returns {@code true} when the name of the method matches the
     * specified {@code methodName}.
     */
    static DocServiceFilter ofMethodName(String methodName) {
        requireNonNull(methodName, "methodName");
        return (plugin, service, method) -> methodName.equals(method);
    }

    /**
     * Returns a {@link DocServiceFilter} which returns {@code true} when the name of the service and method
     * matches the specified {@code serviceName} and {@code methodName}.
     */
    static DocServiceFilter ofMethodName(String serviceName, String methodName) {
        return (plugin, service, method) -> serviceName.equals(service) && methodName.equals(method);
    }

    /**
     * Returns a {@link DocServiceFilter} which returns {@code true} when the name of the plugin, service and
     * method matches the specified {@code pluginName}, {@code serviceName} and {@code methodName}.
     */
    static DocServiceFilter ofMethodName(String pluginName, String serviceName, String methodName) {
        return (plugin, service, method) -> pluginName.equals(plugin) && serviceName.equals(service) &&
                                            methodName.equals(method);
    }

    /**
     * Returns a {@link DocServiceFilter} which returns {@code true} when the concatenated name of the plugin,
     * service and method matches the specified {@code regex}.
     * The concatenated name will be {@code "pluginName + ':' + serviceName + '#' + methodName"}. For example:
     * <pre>{@code
     * grpc:com.example.grpc.FooService#EmptyCall // gRPC.
     * thrift:com.example.thrift.BarService#myMethod // Thrift.
     * annotated:com.example.annotated.BazService#myMethod // Annotated service.
     * }</pre>
     */
    static DocServiceFilter ofRegex(String regex) {
        requireNonNull(regex, "regex");
        return ofRegex(Pattern.compile(regex));
    }

    /**
     * Returns a {@link DocServiceFilter} which returns {@code true} when the concatenated name of the plugin,
     * service and method matches the specified {@link Pattern}.
     * The concatenated name will be {@code "pluginName + ':' + serviceName + '#' + methodName"}. For example:
     * <pre>{@code
     * grpc:armeria.grpc.FooService#EmptyCall // gRPC.
     * thrift:com.linecorp.armeria.service.thrift.BarService#myMethod // Thrift.
     * annotated:com.linecorp.armeria.annotated.BazService#myMethod // Annotated service.
     * }</pre>
     */
    static DocServiceFilter ofRegex(Pattern pattern) {
        requireNonNull(pattern, "pattern");
        return (plugin, service, method) -> {
            final String concatenatedName = plugin + ':' + service + '#' + method;
            return pattern.matcher(concatenatedName).find();
        };
    }

    /**
     * Evaluates this {@link DocServiceFilter} on the specified {@code pluginName}, {@code serviceName} and
     * {@code methodName}.
     */
    boolean test(String pluginName, String serviceName, String methodName);

    /**
     * Returns a composite {@link DocServiceFilter} that represents a short-circuiting logical {@code OR} of
     * this filter and {@code other}. When evaluating the composite filter, if this filter returns {@code true},
     * then the {@code other} filter is not evaluated.
     */
    default DocServiceFilter or(DocServiceFilter other) {
        requireNonNull(other, "other");
        return (pluginName, serviceName, methodName) -> test(pluginName, serviceName, methodName) ||
                                                        other.test(pluginName, serviceName, methodName);
    }

    /**
     * Returns a composite {@link DocServiceFilter} that represents a short-circuiting logical {@code AND} of
     * this filter and {@code other}. When evaluating the composite filter, if this filter returns
     * {@code false}, then the {@code other} filter is not evaluated.
     */
    default DocServiceFilter and(DocServiceFilter other) {
        requireNonNull(other, "other");
        return (pluginName, serviceName, methodName) -> test(pluginName, serviceName, methodName) &&
                                                        other.test(pluginName, serviceName, methodName);
    }
}
