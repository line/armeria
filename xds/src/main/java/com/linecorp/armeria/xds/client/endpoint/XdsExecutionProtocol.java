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

package com.linecorp.armeria.xds.client.endpoint;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.ConcurrentHashMap;

import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ExecutionProtocol;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.client.ClientBuilderParamsUtil;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.internal.XdsBootstrapRegistry;

final class XdsExecutionProtocol implements ExecutionProtocol {

    static final XdsExecutionProtocol INSTANCE = new XdsExecutionProtocol();

    private final ConcurrentHashMap<String, CachedPreprocessors> cache = new ConcurrentHashMap<>();

    private XdsExecutionProtocol() {}

    @Override
    public String uriText() {
        return "xds";
    }

    @Override
    public URI validateUri(URI uri) {
        final String path = uri.getRawPath();
        final String listenerName = (path != null && path.startsWith("/")) ?
                                    path.substring(1) : path;
        checkArgument(listenerName != null && !listenerName.isEmpty(),
                      "xDS URI must have a non-empty listener name in the path: %s", uri);

        final String authority = uri.getRawAuthority();
        if (authority == null || authority.isEmpty()) {
            try {
                return new URI(uri.getScheme(), XdsBootstrapRegistry.DEFAULT_NAME,
                               uri.getRawPath(), uri.getRawQuery(), uri.getRawFragment());
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException(e);
            }
        }
        return uri;
    }

    @Override
    public ClientBuilderParams translate(ClientBuilderParams params) {
        final String bootstrapName = params.uri().getAuthority();
        final XdsBootstrap bootstrap = XdsBootstrapRegistry.find(bootstrapName);
        requireNonNull(bootstrap,
                       "No XdsBootstrap registered with name '" + bootstrapName + "'. " +
                       "Provide an XdsBootstrapProvider via SPI before creating xDS clients.");

        final String listenerName = params.absolutePathRef().substring(1);
        final String cacheKey = System.identityHashCode(bootstrap) + "\0" + listenerName;
        final CachedPreprocessors preprocessors = cache.computeIfAbsent(cacheKey, k ->
                new CachedPreprocessors(
                        XdsHttpPreprocessor.ofListener(listenerName, bootstrap),
                        XdsRpcPreprocessor.ofListener(listenerName, bootstrap)));
        final URI preprocessorUri = ClientBuilderParamsUtil.preprocessorToUri(
                Scheme.of(params.scheme().serializationFormat(), SessionProtocol.HTTP),
                preprocessors.http, null);
        final ClientOptions newOptions = params.options().toBuilder()
                                               .preprocessor(preprocessors.http)
                                               .rpcPreprocessor(preprocessors.rpc)
                                               .build();
        return ClientBuilderParams.of(preprocessorUri, params.clientType(), newOptions);
    }

    private static final class CachedPreprocessors {
        final XdsHttpPreprocessor http;
        final XdsRpcPreprocessor rpc;

        CachedPreprocessors(XdsHttpPreprocessor http, XdsRpcPreprocessor rpc) {
            this.http = http;
            this.rpc = rpc;
        }
    }

    @Override
    public String toString() {
        return uriText();
    }
}
