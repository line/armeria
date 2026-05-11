/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.xds;

import java.util.List;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Base interface for xDS extension factories, resolved by the {@code XdsExtensionRegistry}.
 *
 * <p>Subtype-specific behavior (e.g. HTTP filter creation) lives on sub-interfaces
 * such as {@link com.linecorp.armeria.xds.filter.HttpFilterFactory}.
 */
@UnstableApi
public interface XdsExtensionFactory {

    /**
     * Returns the extension name for registry resolution (required, non-nullable).
     * For example, {@code "envoy.filters.http.router"} or {@code "envoy.transport_sockets.tls"}.
     */
    String name();

    /**
     * Returns the type URLs for registry resolution.
     * For example,
     * {@code List.of("type.googleapis.com/envoy.extensions.filters.http.router.v3.Router")}.
     * Returns an empty list if this factory has no type-URL-based registration.
     */
    default List<String> typeUrls() {
        return ImmutableList.of();
    }
}
