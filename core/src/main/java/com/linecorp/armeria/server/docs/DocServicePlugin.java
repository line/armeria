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

import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.util.UnstableApi;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceConfig;

/**
 * Generates the {@link ServiceSpecification}s of the supported {@link Service}s.
 */
@UnstableApi
public interface DocServicePlugin {

    /**
     * Returns the name of this plugin.
     */
    String name();

    // Methods related with generating a ServiceSpecification.

    /**
     * Returns the {@link Set} of the {@link Service} types supported by this plugin.
     */
    Set<Class<? extends Service<?, ?>>> supportedServiceTypes();

    /**
     * Generates a new {@link ServiceSpecification} that provides the information about the supported
     * {@link Service}s.
     *
     * @param serviceConfigs the {@link ServiceConfig}s of the {@link Service}s that are instances of the
     *                       {@link #supportedServiceTypes()}
     * @param filter the {@link DocServiceFilter} that checks whether a method will be included while
     *               building {@link DocService}
     */
    ServiceSpecification generateSpecification(Set<ServiceConfig> serviceConfigs, DocServiceFilter filter);

    // Methods related with extracting documentation strings.
    // TODO(trustin): Define the docstring format.
    // TODO(trustin): How do we specify the docstring of an exception thrown by a method?
    // TODO(trustin): How do we specify the docstring of a method return value?

    /**
     * Loads the documentation strings that describes services and their methods, enums and their values and
     * structs/exceptions and their fields. The {@link Map} returned by this method will contain the
     * documentation strings identified by the key strings that conforms to one of the following formats:
     * <ul>
     *   <li>{@code "{service name}"} - a docstring that describes a service</li>
     *   <li>{@code "{service name}/{method name}"} - a docstring that describes a service method</li>
     *   <li>{@code "{service name}/{method name}/{parameter name}"} - a docstring that describes
     *       a service method parameter</li>
     *   <li>{@code "{type name}"} - a docstring that describes an enum, a struct or an exception</li>
     *   <li>{@code "{type name}/{field name}"} - a docstring that describes a field of an enum, a struct or
     *       an exception</li>
     * </ul>
     */
    default Map<String, String> loadDocStrings(Set<ServiceConfig> serviceConfigs) {
        return ImmutableMap.of();
    }

    // Methods related with serializing example requests.

    /**
     * Returns the {@link Set} of the example request types supported by this plugin.
     */
    default Set<Class<?>> supportedExampleRequestTypes() {
        return ImmutableSet.of();
    }

    /**
     * Guesses the name of the service who handles the specified example request.
     *
     * @param exampleRequest the example request object which is an instance of one of the {@link Class}es
     *                       returned by {@link #supportedExampleRequestTypes()}
     *
     * @return the service name, or {@code null} if failed to guess.
     */
    @Nullable
    default String guessServiceName(Object exampleRequest) {
        return null;
    }

    /**
     * Guesses the name of the service method who handles the specified example request.
     *
     * @param exampleRequest the example request object which is an instance of one of the {@link Class}es
     *                       returned by {@link #supportedExampleRequestTypes()}
     *
     * @return the service method name, or {@code null} if failed to guess.
     */
    @Nullable
    default String guessServiceMethodName(Object exampleRequest) {
        return null;
    }

    /**
     * Serializes the specified example request into a string.
     *
     * @param serviceName the name of the service
     * @param methodName the name of the method
     * @param exampleRequest the example request
     *
     * @return the serialized example, or {@code null} if not able to serialize.
     */
    @Nullable
    default String serializeExampleRequest(
            String serviceName, String methodName, Object exampleRequest) {
        return null;
    }
}
