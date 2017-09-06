/*
 *  Copyright 2017 LINE Corporation
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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ListMultimap;

import com.linecorp.armeria.common.HttpHeaders;

/**
 * Builds a new {@link DocService}.
 */
public final class DocServiceBuilder {

    // These maps contain the entries whose key is a service name and value is a multimap.
    // The multimap contains the entries whose key is the method name.
    // An empty service name denotes 'all services'.
    // An empty method name denotes 'all methods'.
    // When a service name is empty, its method name shouldn't be empty.
    // For exampleRequests, both service name and method name shouldn't be empty.

    private final Map<String, ListMultimap<String, HttpHeaders>> exampleHttpHeaders = new HashMap<>();
    private final Map<String, ListMultimap<String, String>> exampleRequests = new HashMap<>();

    /**
     * Adds the example {@link HttpHeaders} which are applicable to any services.
     */
    public DocServiceBuilder exampleHttpHeaders(HttpHeaders... exampleHttpHeaders) {
        requireNonNull(exampleHttpHeaders, "exampleHttpHeaders");
        return exampleHttpHeaders(ImmutableList.copyOf(exampleHttpHeaders));
    }

    /**
     * Adds the example {@link HttpHeaders} which are applicable to any services.
     */
    public DocServiceBuilder exampleHttpHeaders(Iterable<? extends HttpHeaders> exampleHttpHeaders) {
        return exampleHttpHeaders0("", "", exampleHttpHeaders);
    }

    /**
     * Adds the example {@link HttpHeaders} for the service with the specified type. This method is
     * a shortcut to:
     * <pre>{@code
     * exampleHttpHeaders(serviceType.getName(), exampleHttpHeaders);
     * }</pre>
     */
    public DocServiceBuilder exampleHttpHeaders(Class<?> serviceType, HttpHeaders... exampleHttpHeaders) {
        requireNonNull(serviceType, "serviceType");
        return exampleHttpHeaders(serviceType.getName(), exampleHttpHeaders);
    }

    /**
     * Adds the example {@link HttpHeaders} for the service with the specified type. This method is
     * a shortcut to:
     * <pre>{@code
     * exampleHttpHeaders(serviceType.getName(), exampleHttpHeaders);
     * }</pre>
     */
    public DocServiceBuilder exampleHttpHeaders(Class<?> serviceType,
                                                Iterable<? extends HttpHeaders> exampleHttpHeaders) {
        requireNonNull(serviceType, "serviceType");
        return exampleHttpHeaders(serviceType.getName(), exampleHttpHeaders);
    }

    /**
     * Adds the example {@link HttpHeaders} for the service with the specified name.
     */
    public DocServiceBuilder exampleHttpHeaders(String serviceName, HttpHeaders... exampleHttpHeaders) {
        requireNonNull(exampleHttpHeaders, "exampleHttpHeaders");
        return exampleHttpHeaders(serviceName, ImmutableList.copyOf(exampleHttpHeaders));
    }

    /**
     * Adds the example {@link HttpHeaders} for the service with the specified name.
     */
    public DocServiceBuilder exampleHttpHeaders(String serviceName,
                                                Iterable<? extends HttpHeaders> exampleHttpHeaders) {
        requireNonNull(serviceName, "serviceName");
        checkArgument(!serviceName.isEmpty(), "serviceName is empty.");
        requireNonNull(exampleHttpHeaders, "exampleHttpHeaders");
        return exampleHttpHeaders0(serviceName, "", exampleHttpHeaders);
    }

    /**
     * Adds the example {@link HttpHeaders} for the method with the specified type and method name.
     * This method is a shortcut to:
     * <pre>{@code
     * exampleHttpHeaders(serviceType.getName(), methodName, exampleHttpHeaders);
     * }</pre>
     */
    public DocServiceBuilder exampleHttpHeaders(Class<?> serviceType, String methodName,
                                                HttpHeaders... exampleHttpHeaders) {
        requireNonNull(serviceType, "serviceType");
        return exampleHttpHeaders(serviceType.getName(), methodName, exampleHttpHeaders);
    }

    /**
     * Adds the example {@link HttpHeaders} for the method with the specified type and method name.
     * This method is a shortcut to:
     * <pre>{@code
     * exampleHttpHeaders(serviceType.getName(), methodName, exampleHttpHeaders);
     * }</pre>
     */
    public DocServiceBuilder exampleHttpHeaders(Class<?> serviceType, String methodName,
                                                Iterable<? extends HttpHeaders> exampleHttpHeaders) {
        requireNonNull(serviceType, "serviceType");
        return exampleHttpHeaders(serviceType.getName(), methodName, exampleHttpHeaders);
    }

    /**
     * Adds the example {@link HttpHeaders} for the method with the specified service and method name.
     */
    public DocServiceBuilder exampleHttpHeaders(String serviceName, String methodName,
                                                HttpHeaders... exampleHttpHeaders) {
        requireNonNull(exampleHttpHeaders, "exampleHttpHeaders");
        return exampleHttpHeaders(serviceName, methodName, ImmutableList.copyOf(exampleHttpHeaders));
    }

    /**
     * Adds the example {@link HttpHeaders} for the method with the specified service and method name.
     */
    public DocServiceBuilder exampleHttpHeaders(String serviceName, String methodName,
                                                Iterable<? extends HttpHeaders> exampleHttpHeaders) {
        requireNonNull(serviceName, "serviceName");
        checkArgument(!serviceName.isEmpty(), "serviceName is empty.");
        requireNonNull(methodName, "methodName");
        checkArgument(!methodName.isEmpty(), "methodName is empty.");
        requireNonNull(exampleHttpHeaders, "exampleHttpHeaders");
        return exampleHttpHeaders0(serviceName, methodName, exampleHttpHeaders);
    }

    private DocServiceBuilder exampleHttpHeaders0(String serviceName, String methodName,
                                                  Iterable<? extends HttpHeaders> exampleHttpHeaders) {
        for (HttpHeaders h : exampleHttpHeaders) {
            requireNonNull(h, "exampleHttpHeaders contains null.");
            this.exampleHttpHeaders.computeIfAbsent(serviceName, unused -> ArrayListMultimap.create())
                                   .put(methodName, HttpHeaders.copyOf(h).asImmutable());
        }
        return this;
    }

    /**
     * Adds the example requests for the method with the specified service type and method name.
     * This method is a shortcut to:
     * <pre>{@code
     * exampleRequest(serviceType.getName(), exampleRequests);
     * }</pre>
     */
    public DocServiceBuilder exampleRequestForMethod(Class<?> serviceType, String methodName,
                                                     Object... exampleRequests) {
        requireNonNull(exampleRequests, "exampleRequests");
        return exampleRequestForMethod(serviceType, methodName, ImmutableList.copyOf(exampleRequests));
    }

    /**
     * Adds the example requests for the method with the specified service type and method name.
     * This method is a shortcut to:
     * <pre>{@code
     * exampleRequest(serviceType.getName(), exampleRequests);
     * }</pre>
     */
    public DocServiceBuilder exampleRequestForMethod(Class<?> serviceType, String methodName,
                                                     Iterable<?> exampleRequests) {
        requireNonNull(serviceType, "serviceType");
        return exampleRequestForMethod(serviceType.getName(), methodName, exampleRequests);
    }

    /**
     * Adds the example requests for the method with the specified service and method name.
     */
    public DocServiceBuilder exampleRequestForMethod(String serviceName, String methodName,
                                                     Object... exampleRequests) {
        requireNonNull(exampleRequests, "exampleRequests");
        return exampleRequestForMethod(serviceName, methodName, ImmutableList.copyOf(exampleRequests));
    }

    /**
     * Adds the example requests for the method with the specified service and method name.
     */
    public DocServiceBuilder exampleRequestForMethod(String serviceName, String methodName,
                                                     Iterable<?> exampleRequests) {
        requireNonNull(serviceName, "serviceName");
        requireNonNull(methodName, "methodName");
        requireNonNull(exampleRequests, "exampleRequests");

        for (Object e : exampleRequests) {
            requireNonNull(e, "exampleRequests contains null.");
            exampleRequest0(serviceName, methodName,
                            serializeExampleRequest(serviceName, methodName, e));
        }
        return this;
    }

    /**
     * Adds the example requests which are applicable to the method denoted by the specified example requests.
     * Please note that this method may fail if the specified requests object do not provide the information
     * about their service and method names.
     *
     * @throws IllegalArgumentException if failed to get the service and method name from an example request
     */
    public DocServiceBuilder exampleRequest(Object... exampleRequests) {
        requireNonNull(exampleRequests, "exampleRequests");
        return exampleRequest(ImmutableList.copyOf(exampleRequests));
    }

    /**
     * Adds the example requests which are applicable to the method denoted by the specified example requests.
     * Please note that this method may fail if the specified requests object do not provide the information
     * about their service and method names.
     *
     * @throws IllegalArgumentException if failed to get the service and method name from an example request
     */
    public DocServiceBuilder exampleRequest(Iterable<?> exampleRequests) {
        requireNonNull(exampleRequests, "exampleRequests");
        for (Object e : exampleRequests) {
            requireNonNull(e, "exampleRequests contains null.");
            final String[] result = guessAndSerializeExampleRequest(e);
            exampleRequest0(result[0], result[1], result[2]);
        }
        return this;
    }

    private void exampleRequest0(String serviceName, String methodName, String serializedExampleRequest) {
        exampleRequests.computeIfAbsent(serviceName, unused -> ArrayListMultimap.create())
                       .put(methodName, serializedExampleRequest);
    }

    private static String serializeExampleRequest(
            String serviceName, String methodName, Object exampleRequest) {

        if (exampleRequest instanceof CharSequence) {
            return exampleRequest.toString();
        }

        for (DocServicePlugin generator : DocService.plugins) {
            final Optional<String> result =
                    generator.serializeExampleRequest(serviceName, methodName, exampleRequest);
            if (result.isPresent()) {
                return result.get();
            }
        }

        throw new IllegalArgumentException("could not find a plugin that can serialize: " + exampleRequest);
    }

    /**
     * Returns a tuple of a service name, a method name and a serialized example request.
     */
    private static String[] guessAndSerializeExampleRequest(Object exampleRequest) {
        checkArgument(!(exampleRequest instanceof CharSequence),
                      "can't guess service or method name from a string: ", exampleRequest);

        boolean guessed = false;
        for (DocServicePlugin plugin : DocService.plugins) {
            // Skip if the plugin does not support it.
            if (plugin.supportedExampleRequestTypes().stream()
                      .noneMatch(type -> type.isInstance(exampleRequest))) {
                continue;
            }

            final Optional<String> serviceName = plugin.guessServiceName(exampleRequest);
            final Optional<String> methodName = plugin.guessServiceMethodName(exampleRequest);

            // Skip if the plugin cannot guess the service and method name.
            if (!serviceName.isPresent() || !methodName.isPresent()) {
                continue;
            }

            guessed = true;
            final String s = serviceName.get();
            final String f = methodName.get();
            Optional<String> serialized = plugin.serializeExampleRequest(s, f, exampleRequest);
            if (serialized.isPresent()) {
                return new String[] { s, f, serialized.get() };
            }
        }

        if (guessed) {
            throw new IllegalArgumentException(
                    "could not find a plugin that can serialize: " + exampleRequest);
        } else {
            throw new IllegalArgumentException(
                    "could not find a plugin that can guess the service and method name from: " +
                    exampleRequest);
        }
    }

    /**
     * Returns a newly-created {@link DocService} based on the properties of this builder.
     */
    public DocService build() {
        return new DocService(exampleHttpHeaders, exampleRequests);
    }
}
