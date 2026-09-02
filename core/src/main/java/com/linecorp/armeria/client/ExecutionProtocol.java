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

package com.linecorp.armeria.client;

import java.net.URI;
import java.util.Set;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Defines how a client executes requests. {@link SessionProtocol} is the most common implementation,
 * representing standard HTTP-based execution (e.g., {@code http}, {@code https}, {@code h2}).
 * Custom implementations can be registered via {@link ExecutionProtocolProvider} SPI to support
 * alternative execution mechanisms such as xDS-based service discovery.
 *
 * <p>An {@link ExecutionProtocol} is used as the right-hand side of a
 * {@link com.linecorp.armeria.common.Scheme} URI:
 * <ul>
 *   <li>{@code "gproto+http"} - gRPC over HTTP</li>
 *   <li>{@code "gproto+xds"} - gRPC over xDS (custom execution protocol)</li>
 * </ul>
 */
@UnstableApi
public interface ExecutionProtocol {

    /**
     * Returns the textual representation of this protocol for use in a
     * {@link com.linecorp.armeria.common.Scheme}.
     */
    String uriText();

    /**
     * Validates the specified {@link URI} for this protocol and returns a validated URI.
     * For example, {@link SessionProtocol}s require an authority (host) in the URI,
     * while custom protocols like xDS may remap authority-less URIs to include a
     * default authority (e.g., {@code "xds:///my-listener"} to {@code "xds://default/my-listener"}).
     *
     * @return the validated (and possibly normalized) URI
     * @throws IllegalArgumentException if the URI is not valid for this protocol
     */
    default URI validateUri(URI uri) {
        return uri;
    }

    /**
     * Translates the given {@link ClientBuilderParams} into new params suitable for
     * a standard {@link SessionProtocol}-based client factory. The default implementation
     * returns the params unchanged (identity translation).
     *
     * <p>Custom {@link ExecutionProtocol}s (e.g., xDS) override this to rewrite the URI,
     * attach preprocessors, or otherwise transform the params before the client is created.
     */
    default ClientBuilderParams translate(ClientBuilderParams params) {
        return params;
    }

    /**
     * Returns all registered {@link ExecutionProtocol}s.
     */
    static Set<ExecutionProtocol> values() {
        return ExecutionProtocolRegistry.VALUES;
    }
}
