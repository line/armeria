/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.server.saml;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.ServiceWithPathMappings;

import io.netty.handler.codec.http.QueryStringDecoder;

/**
 * A {@link Service} which handles SAML APIs, such as consuming an assertion, retrieving a metadata
 * or handling a logout request from an identity provider.
 */
final class SamlService implements ServiceWithPathMappings<HttpRequest, HttpResponse> {

    private final SamlServiceProvider sp;
    private final SamlPortConfigAutoFiller portConfigHolder;

    @Nullable
    private Server server;

    private final Map<String, SamlServiceFunction> serviceMap;
    private final Set<PathMapping> pathMappings;

    SamlService(SamlServiceProvider sp) {
        this.sp = requireNonNull(sp, "sp");
        portConfigHolder = sp.portConfigAutoFiller();

        final ImmutableMap.Builder<String, SamlServiceFunction> builder = new Builder<>();
        sp.acsConfigs().forEach(
                cfg -> builder.put(cfg.endpoint().uri().getPath(),
                                   new SamlAssertionConsumerFunction(cfg,
                                                                     sp.entityId(),
                                                                     sp.idpConfigs(),
                                                                     sp.defaultIdpConfig(),
                                                                     sp.requestIdManager(),
                                                                     sp.ssoHandler())));
        sp.sloEndpoints().forEach(
                cfg -> builder.put(cfg.uri().getPath(),
                                   new SamlSingleLogoutFunction(cfg,
                                                                sp.entityId(),
                                                                sp.signingCredential(),
                                                                sp.signatureAlgorithm(),
                                                                sp.idpConfigs(),
                                                                sp.defaultIdpConfig(),
                                                                sp.requestIdManager(),
                                                                sp.sloHandler())));
        final PathMapping metadata = sp.metadataPath();
        metadata.exactPath().ifPresent(
                path -> builder.put(path,
                                    new SamlMetadataServiceFunction(sp.entityId(),
                                                                    sp.signingCredential(),
                                                                    sp.encryptionCredential(),
                                                                    sp.idpConfigs(),
                                                                    sp.acsConfigs(),
                                                                    sp.sloEndpoints())));
        serviceMap = builder.build();
        pathMappings = serviceMap.keySet().stream().map(PathMapping::ofExact).collect(toImmutableSet());
    }

    @Override
    public void serviceAdded(ServiceConfig cfg) throws Exception {
        if (server != null) {
            if (server != cfg.server()) {
                throw new IllegalStateException("cannot be added to more than one server");
            } else {
                return;
            }
        }

        server = cfg.server();

        // Auto-detect the primary port number and its session protocol after the server started.
        server.addListener(portConfigHolder);
    }

    @Override
    public Set<PathMapping> pathMappings() {
        return pathMappings;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final SamlServiceFunction func = serviceMap.get(req.path());
        if (func == null) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST);
        }

        final CompletionStage<AggregatedHttpMessage> f;
        if (portConfigHolder.isDone()) {
            f = req.aggregate();
        } else {
            f = portConfigHolder.future().thenCompose(unused -> req.aggregate());
        }
        return HttpResponse.from(f.handle((msg, cause) -> {
            if (cause != null) {
                return HttpResponse.of(HttpStatus.BAD_REQUEST);
            }
            final SamlPortConfig portConfig = portConfigHolder.config().get();
            if (portConfig.scheme().isTls() != ctx.sessionProtocol().isTls()) {
                return HttpResponse.of(HttpStatus.BAD_REQUEST);
            }

            // Use user-specified hostname if it exists.
            // If there's no hostname set by a user, the default virtual hostname will be used.
            final String defaultHostname = firstNonNull(sp.hostname(), ctx.virtualHost().defaultHostname());
            return func.serve(ctx, msg, defaultHostname, portConfig);
        }));
    }

    /**
     * A wrapper class which holds parameters resolved from a query string.
     */
    static final class SamlParameters {
        private final Map<String, List<String>> parameters;

        /**
         * Creates a {@link SamlParameters} instance with the specified {@link AggregatedHttpMessage}.
         */
        SamlParameters(AggregatedHttpMessage msg) {
            requireNonNull(msg, "msg");
            final MediaType contentType = msg.contentType();

            final QueryStringDecoder decoder;
            if (contentType != null && contentType.belongsTo(MediaType.FORM_DATA)) {
                final String query = msg.content(contentType.charset().orElse(StandardCharsets.UTF_8));
                decoder = new QueryStringDecoder(query, false);
            } else {
                final String path = msg.path();
                assert path != null : "path";
                decoder = new QueryStringDecoder(path, true);
            }

            parameters = decoder.parameters();
        }

        /**
         * Returns the first value of the parameter with the specified {@code name}.
         *
         * @throws SamlException if a parameter with the specified {@code name} does not exist
         */
        String getFirstValue(String name) {
            final String value = getFirstValueOrNull(name);
            if (value == null) {
                throw new SamlException("failed to get the value of a parameter: " + name);
            }
            return value;
        }

        /**
         * Returns the first value of the parameter with the specified {@code name}. If it does not exist,
         * {@code null} is returned.
         */
        @Nullable
        String getFirstValueOrNull(String name) {
            requireNonNull(name, "name");
            final List<String> values = parameters.get(name);
            if (values == null || values.isEmpty()) {
                return null;
            }
            final String value = values.get(0);
            if (value.isEmpty()) {
                return null;
            }
            return value;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("parameters", parameters)
                              .toString();
        }
    }
}
