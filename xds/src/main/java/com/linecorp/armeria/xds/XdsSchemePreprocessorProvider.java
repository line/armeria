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

import static com.google.common.base.Preconditions.checkArgument;

import java.net.URI;

import com.linecorp.armeria.client.HttpPreprocessor;
import com.linecorp.armeria.client.RpcPreprocessor;
import com.linecorp.armeria.client.SchemePreprocessorProvider;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A {@link SchemePreprocessorProvider} for the {@code "xds"} scheme.
 */
@UnstableApi
public final class XdsSchemePreprocessorProvider implements SchemePreprocessorProvider {

    @Override
    public String scheme() {
        return "xds";
    }

    @Override
    public HttpPreprocessor preprocessor(URI uri) {
        return XdsBootstrapRegistry.httpPreprocessor(bootstrapName(uri), listenerName(uri));
    }

    @Override
    public RpcPreprocessor rpcPreprocessor(URI uri) {
        return XdsBootstrapRegistry.rpcPreprocessor(bootstrapName(uri), listenerName(uri));
    }

    private static String bootstrapName(URI uri) {
        final String authority = uri.getRawAuthority();
        if (authority == null || authority.isEmpty()) {
            return XdsBootstrapRegistry.defaultName();
        }
        return authority;
    }

    private static String listenerName(URI uri) {
        String pathPart = uri.getRawPath();
        if (pathPart != null && pathPart.startsWith("/")) {
            pathPart = pathPart.substring(1);
        }
        checkArgument(pathPart != null && !pathPart.isEmpty(),
                      "xDS URI must have a non-empty listener name in the path: %s", uri);

        final StringBuilder sb = new StringBuilder(pathPart);
        if (uri.getRawQuery() != null) {
            sb.append('?').append(uri.getRawQuery());
        }
        if (uri.getRawFragment() != null) {
            sb.append('#').append(uri.getRawFragment());
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return scheme();
    }
}
