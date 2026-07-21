/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.armeria.xds.filter;

import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.DecoratingHttpClientFunction;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.xds.GenericSecretSnapshot;
import com.linecorp.armeria.xds.stream.SnapshotStream;

import io.envoyproxy.envoy.extensions.filters.http.credential_injector.v3.CredentialInjector;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
import io.envoyproxy.envoy.extensions.http.injected_credentials.generic.v3.Generic;

/**
 * An {@link HttpFilterFactory} for the
 * {@code envoy.filters.http.credential_injector} filter with the Generic credential provider.
 *
 * <p>This filter injects credentials from an SDS secret into outgoing HTTP requests.
 * The credential is reactively updated when the SDS secret rotates.
 */
@UnstableApi
public final class CredentialInjectorFilterFactory implements HttpFilterFactory {

    private static final String NAME = "envoy.filters.http.credential_injector";
    private static final String TYPE_URL =
            "type.googleapis.com/envoy.extensions.filters.http.credential_injector.v3.CredentialInjector";
    private static final String GENERIC_TYPE_URL =
            "type.googleapis.com/envoy.extensions.http.injected_credentials.generic.v3.Generic";
    private static final List<String> TYPE_URLS = ImmutableList.of(TYPE_URL);
    private static final String DEFAULT_HEADER = HttpHeaderNames.AUTHORIZATION.toString();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> typeUrls() {
        return TYPE_URLS;
    }

    @Override
    @Nullable
    public XdsHttpFilter create(HttpFilter httpFilter, Any config, FactoryContext context) {
        throw new UnsupportedOperationException(
                "credential_injector requires reactive secret subscription; use createStream()");
    }

    @Override
    public SnapshotStream<XdsHttpFilter> createStream(HttpFilter httpFilter, Any config,
                                                      FactoryContext context) {
        final CredentialInjector injectorConfig = context.validator().unpack(config,
                                                                             CredentialInjector.class);
        final boolean overwrite = injectorConfig.getOverwrite();
        final boolean allowWithoutCredential = injectorConfig.getAllowRequestWithoutCredential();

        final Any typedConfig = injectorConfig.getCredential().getTypedConfig();
        if (!GENERIC_TYPE_URL.equals(typedConfig.getTypeUrl())) {
            throw new IllegalArgumentException(
                    "Unsupported credential type: " + typedConfig.getTypeUrl() +
                    "; only Generic (" + GENERIC_TYPE_URL + ") is supported");
        }

        final Generic generic = context.validator().unpack(typedConfig, Generic.class);
        final String header = generic.getHeader().isEmpty() ? DEFAULT_HEADER : generic.getHeader();

        final SnapshotStream<GenericSecretSnapshot> genericSecretStream =
                context.genericSecretStream(generic.getCredential());

        return genericSecretStream.map(snapshot -> {
            return new CredentialInjectorXdsHttpFilter(snapshot.credential(), header, overwrite,
                                                       allowWithoutCredential);
        });
    }

    private static final class CredentialInjectorXdsHttpFilter implements XdsHttpFilter {

        @Nullable
        private final String credential;
        private final String header;
        private final boolean overwrite;
        private final boolean allowWithoutCredential;

        CredentialInjectorXdsHttpFilter(@Nullable String credential, String header,
                                        boolean overwrite, boolean allowWithoutCredential) {
            this.credential = credential;
            this.header = header;
            this.overwrite = overwrite;
            this.allowWithoutCredential = allowWithoutCredential;
        }

        @Override
        public DecoratingHttpClientFunction httpDecorator() {
            return (delegate, ctx, req) -> {
                if (credential == null) {
                    if (!allowWithoutCredential) {
                        return HttpResponse.of(HttpStatus.UNAUTHORIZED);
                    }
                    return delegate.execute(ctx, req);
                }
                if (!overwrite && headerExists(ctx, req)) {
                    return delegate.execute(ctx, req);
                }
                ctx.setAdditionalRequestHeader(header, credential);
                return delegate.execute(ctx, req);
            };
        }

        private boolean headerExists(ClientRequestContext ctx, HttpRequest req) {
            return req.headers().contains(header) ||
                   ctx.defaultRequestHeaders().contains(header) ||
                   ctx.additionalRequestHeaders().contains(header);
        }
    }
}
