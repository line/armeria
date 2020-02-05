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
import static com.linecorp.armeria.internal.server.docs.DocServiceUtil.unifyFilter;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ListMultimap;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Builds a new {@link DocService}.
 */
public final class DocServiceBuilder {

    static final DocServiceFilter ALL_SERVICES = (plugin, service, method) -> true;

    static final DocServiceFilter NO_SERVICE = (plugin, service, method) -> false;

    private DocServiceFilter includeFilter = ALL_SERVICES;

    private DocServiceFilter excludeFilter = NO_SERVICE;

    // These maps contain the entries whose key is a service name and value is a multimap.
    // The multimap contains the entries whose key is the method name.
    // An empty service name denotes 'all services'.
    // An empty method name denotes 'all methods'.
    // When a service name is empty, its method name shouldn't be empty.
    // For exampleRequests, both service name and method name shouldn't be empty.

    private final Map<String, ListMultimap<String, HttpHeaders>> exampleHttpHeaders = new HashMap<>();
    private final Map<String, ListMultimap<String, String>> exampleRequests = new HashMap<>();
    private final List<BiFunction<ServiceRequestContext, HttpRequest, String>> injectedScriptSuppliers =
            new ArrayList<>();

    /**
     * Creates a new {@link DocServiceBuilder}.
     *
     * @deprecated Use {@link DocService#builder()}.
     */
    @Deprecated
    public DocServiceBuilder() {}

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
                                   .put(methodName, h);
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

    /**
     * Adds the {@link DocServiceFilter} that checks whether a method will be <b>included</b> while building
     * {@link DocService}. The {@link DocServiceFilter} will be invoked with the plugin, service and
     * method name. The rule is as follows:
     * <ul>
     *   <li>No {@link #include(DocServiceFilter)} and {@link #exclude(DocServiceFilter)} is called -
     *       include all methods.</li>
     *   <li>Only {@link #exclude(DocServiceFilter)} is called -
     *       include all methods except the methods which the exclusion filter returns {@code true}.</li>
     *   <li>Only {@link #include(DocServiceFilter)} is called -
     *       include the methods which the inclusion filter returns {@code true}.</li>
     *   <li>{@link #include(DocServiceFilter)} and {@link #exclude(DocServiceFilter)} is called -
     *       include the methods which the inclusion filter returns {@code true} and the exclusion filter
     *       returns {@code false}.</li>
     * </ul>
     *
     * <P>Note that this can be called multiple times and the {@link DocServiceFilter}s are composed using
     * {@link DocServiceFilter#or(DocServiceFilter)} and {@link DocServiceFilter#and(DocServiceFilter)}.
     *
     * @see #exclude(DocServiceFilter)
     * @see DocService to find out how DocService generates documentaion
     */
    public DocServiceBuilder include(DocServiceFilter filter) {
        requireNonNull(filter, "filter");
        if (includeFilter == ALL_SERVICES) {
            includeFilter = filter;
        } else {
            includeFilter = includeFilter.or(filter);
        }
        return this;
    }

    /**
     * Adds the {@link DocServiceFilter} that checks whether a method will be <b>excluded</b> while building
     * {@link DocService}. The {@link DocServiceFilter} will be invoked with the plugin, service and
     * method name. The rule is as follows:
     * <ul>
     *   <li>No {@link #include(DocServiceFilter)} and {@link #exclude(DocServiceFilter)} is called -
     *       include all methods.</li>
     *   <li>Only {@link #exclude(DocServiceFilter)} is called -
     *       include all methods except the methods which the exclusion filter returns {@code true}.</li>
     *   <li>Only {@link #include(DocServiceFilter)} is called -
     *       include the methods which the inclusion filter returns {@code true}.</li>
     *   <li>{@link #include(DocServiceFilter)} and {@link #exclude(DocServiceFilter)} is called -
     *       include the methods which the inclusion filter returns {@code true} and the exclusion filter
     *       returns {@code false}.</li>
     * </ul>
     *
     * <P>Note that this can be called multiple times and the {@link DocServiceFilter}s are composed using
     * {@link DocServiceFilter#or(DocServiceFilter)} and {@link DocServiceFilter#and(DocServiceFilter)}.
     *
     * @see #exclude(DocServiceFilter)
     * @see DocService to find out how DocService generates documentaion
     */
    public DocServiceBuilder exclude(DocServiceFilter filter) {
        requireNonNull(filter, "filter");
        if (excludeFilter == NO_SERVICE) {
            excludeFilter = filter;
        } else {
            excludeFilter = excludeFilter.or(filter);
        }
        return this;
    }

    /**
     * Adds Javascript scripts to inject into the {@code <head />} of the debug page HTML. This can be used to
     * customize the debug page (e.g., to provide a HeaderProvider for enabling authentication based on local
     * storage). All scripts are concatenated into the content of a single script tag.
     *
     * <p>A common use case is to provide authentication of debug requests using a local storage access token,
     * e.g., <pre>{@code
     *   armeria.registerHeaderProvider(function() {
     *     // Replace with fetching accesstoken using your favorite auth library.
     *     return Promise.resolve({ authorization: 'accesstoken' });
     *   });
     * }</pre>
     */
    public DocServiceBuilder injectedScript(String... scripts) {
        requireNonNull(scripts, "scripts");
        return injectedScript(ImmutableList.copyOf(scripts));
    }

    /**
     * Adds Javascript scripts to inject into the {@code <head />} of the debug page HTML. This can be used to
     * customize the debug page (e.g., to provide a HeaderProvider for enabling authentication based on local
     * storage). All scripts are concatenated into the content of a single script tag.
     *
     * <p>A common use case is to provide authentication of debug requests using a local storage access token,
     * e.g., <pre>{@code
     *   armeria.registerHeaderProvider(function() {
     *     // Replace with fetching accesstoken using your favorite auth library.
     *     return Promise.resolve({ authorization: 'accesstoken' });
     *   });
     * }</pre>
     */
    public DocServiceBuilder injectedScript(Iterable<String> scripts) {
        requireNonNull(scripts, "scripts");
        for (String s : scripts) {
            requireNonNull(s, "scripts contains null.");
            injectedScriptSuppliers.add((unused1, unused2) -> s);
        }
        return this;
    }

    /**
     * Adds a supplier for Javascript scripts to inject into the {@code <head />} of the debug page HTML.
     * The supplier will be called every request for the initial {@link DocService} HTML. This can be used to
     * customize the debug page per-request (e.g., to provide a HeaderProvider for enabling authentication based
     * on an injected timestamped token). All scripts are concatenated into the content of a single script tag.
     */
    public DocServiceBuilder injectedScriptSupplier(
            BiFunction<ServiceRequestContext, HttpRequest, String> supplier) {
        injectedScriptSuppliers.add(requireNonNull(supplier, "supplier"));
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

        for (DocServicePlugin plugin : DocService.plugins) {
            // Skip if the plugin does not support it.
            if (plugin.supportedExampleRequestTypes().stream()
                      .noneMatch(type -> type.isInstance(exampleRequest))) {
                continue;
            }

            final String result = plugin.serializeExampleRequest(serviceName, methodName, exampleRequest);
            if (result != null) {
                return result;
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

            final String serviceName = plugin.guessServiceName(exampleRequest);
            final String methodName = plugin.guessServiceMethodName(exampleRequest);

            // Skip if the plugin cannot guess the service and method name.
            if (serviceName == null || methodName == null) {
                continue;
            }

            guessed = true;
            final String serialized = plugin.serializeExampleRequest(serviceName, methodName, exampleRequest);
            if (serialized != null) {
                return new String[] { serviceName, methodName, serialized };
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
        return new DocService(exampleHttpHeaders, exampleRequests, injectedScriptSuppliers,
                              unifyFilter(includeFilter, excludeFilter));
    }
}
