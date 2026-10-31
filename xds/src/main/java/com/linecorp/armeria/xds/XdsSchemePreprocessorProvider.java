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
import com.linecorp.armeria.client.PreClient;
import com.linecorp.armeria.client.PreClientRequestContext;
import com.linecorp.armeria.client.RpcPreprocessor;
import com.linecorp.armeria.client.SchemePreprocessorProvider;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.annotation.Nullable;
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
        final String bootstrap = bootstrapName(uri);
        final String listener = listenerName(uri);
        return new DeferringHttpPreprocessor(bootstrap, listener);
    }

    @Override
    public RpcPreprocessor rpcPreprocessor(URI uri) {
        final String bootstrap = bootstrapName(uri);
        final String listener = listenerName(uri);
        return new DeferringRpcPreprocessor(bootstrap, listener);
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

    private static final class DeferringHttpPreprocessor implements HttpPreprocessor {

        private final String bootstrapName;
        private final String listenerName;
        @Nullable
        private HttpPreprocessor delegate;

        DeferringHttpPreprocessor(String bootstrapName, String listenerName) {
            this.bootstrapName = bootstrapName;
            this.listenerName = listenerName;
        }

        @Override
        public HttpResponse execute(PreClient<HttpRequest, HttpResponse> delegate,
                                    PreClientRequestContext ctx, HttpRequest req) throws Exception {
            return resolveDelegate().execute(delegate, ctx, req);
        }

        private HttpPreprocessor resolveDelegate() {
            HttpPreprocessor resolved = delegate;
            if (resolved == null) {
                resolved = XdsBootstrapRegistry.httpPreprocessor(bootstrapName, listenerName);
                delegate = resolved;
            }
            return resolved;
        }
    }

    private static final class DeferringRpcPreprocessor implements RpcPreprocessor {

        private final String bootstrapName;
        private final String listenerName;
        @Nullable
        private RpcPreprocessor delegate;

        DeferringRpcPreprocessor(String bootstrapName, String listenerName) {
            this.bootstrapName = bootstrapName;
            this.listenerName = listenerName;
        }

        @Override
        public RpcResponse execute(PreClient<RpcRequest, RpcResponse> delegate,
                                   PreClientRequestContext ctx, RpcRequest req) throws Exception {
            return resolveDelegate().execute(delegate, ctx, req);
        }

        private RpcPreprocessor resolveDelegate() {
            RpcPreprocessor resolved = delegate;
            if (resolved == null) {
                resolved = XdsBootstrapRegistry.rpcPreprocessor(bootstrapName, listenerName);
                delegate = resolved;
            }
            return resolved;
        }
    }
}
