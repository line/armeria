/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import java.util.Set;

abstract class AbstractContextPathAnnotatedServiceConfigSetters
        <SELF extends AbstractContextPathAnnotatedServiceConfigSetters<SELF, T>,
                T extends AbstractContextPathServicesBuilder<?, ?>>
        extends AbstractAnnotatedServiceConfigSetters<
        AbstractContextPathAnnotatedServiceConfigSetters<SELF, T>> {

    private final T builder;
    private final Set<String> contextPaths;

    AbstractContextPathAnnotatedServiceConfigSetters(T builder) {
        this.builder = builder;
        contextPaths = builder.contextPaths();
    }

    /**
     * Registers the given service and returns the parent object.
     *
     * @param service annotated service object to handle incoming requests matching path prefix, which
     *                can be configured through {@link AnnotatedServiceBindingBuilder#pathPrefix(String)}.
     *                If path prefix is not set then this service is registered to handle requests matching
     *                {@code /}
     */
    T build(Object service) {
        requireNonNull(service, "service");
        service(service);
        contextPaths(contextPaths);
        builder.addServiceConfigSetters(this);
        return builder;
    }
}
