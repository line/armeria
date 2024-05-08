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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.HttpResponseException;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.HttpServiceWithRoutes;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.RoutePathType;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * An {@link HttpService} which handles SAML APIs, such as consuming an assertion, retrieving a metadata
 * or handling a logout request from an identity provider.
 */
final class SamlService implements HttpServiceWithRoutes {

    private static final HttpData DATA_INCORRECT_PATH =
            HttpData.ofUtf8(HttpStatus.BAD_REQUEST + "\nSAML request with an incorrect path");

    private static final HttpData DATA_AGGREGATION_FAILURE =
            HttpData.ofUtf8(HttpStatus.BAD_REQUEST + "\nSAML request aggregation failure");

    private static final HttpData DATA_NOT_TLS =
            HttpData.ofUtf8(HttpStatus.BAD_REQUEST + "\nSAML request not from a TLS connection");

    private static final HttpData DATA_NOT_CLEARTEXT =
            HttpData.ofUtf8(HttpStatus.BAD_REQUEST + "\nSAML request not from a cleartext connection");

    private static final Logger logger = LoggerFactory.getLogger(SamlService.class);

    private final SamlServiceProvider sp;
    private final SamlPortConfigAutoFiller portConfigHolder;

    @Nullable
    private Server server;

    private final Map<String, SamlServiceFunction> serviceMap;
    private final Set<Route> routes;

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
                                                                     sp.ssoHandler(),
                                                                     sp.isSignatureRequired())));
        sp.sloEndpoints().forEach(
                cfg -> builder.put(cfg.uri().getPath(),
                                   new SamlSingleLogoutFunction(cfg,
                                                                sp.entityId(),
                                                                sp.signingCredential(),
                                                                sp.signatureAlgorithm(),
                                                                sp.idpConfigs(),
                                                                sp.defaultIdpConfig(),
                                                                sp.requestIdManager(),
                                                                sp.sloHandler(),
                                                                sp.isSignatureRequired())));
        final Route route = sp.metadataRoute();
        if (route.pathType() == RoutePathType.EXACT) {
            builder.put(route.paths().get(0),
                        new SamlMetadataServiceFunction(sp.entityId(),
                                                        sp.signingCredential(),
                                                        sp.encryptionCredential(),
                                                        sp.idpConfigs(),
                                                        sp.acsConfigs(),
                                                        sp.sloEndpoints()));
        }
        serviceMap = builder.build();
        routes = serviceMap.keySet()
                           .stream()
                           .map(path -> Route.builder().exact(path).build())
                           .collect(toImmutableSet());
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
    public Set<Route> routes() {
        return routes;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final SamlServiceFunction func = serviceMap.get(req.path());
        if (func == null) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT_UTF_8,
                                   DATA_INCORRECT_PATH);
        }

        final CompletionStage<AggregatedHttpRequest> f;
        if (portConfigHolder.isDone()) {
            f = req.aggregate();
        } else {
            f = portConfigHolder.future().thenCompose(unused -> req.aggregate());
        }
        return HttpResponse.of(f.handleAsync((aggregatedReq, cause) -> {
            if (cause != null) {
                cause = Exceptions.peel(cause);
                if (cause instanceof HttpStatusException || cause instanceof HttpResponseException) {
                    return HttpResponse.ofFailure(cause);
                }
                logger.warn("{} Failed to aggregate a SAML request.", ctx, cause);
                return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT_UTF_8,
                                       DATA_AGGREGATION_FAILURE);
            }

            final SamlPortConfig portConfig = portConfigHolder.config();
            final boolean isTls = ctx.sessionProtocol().isTls();
            if (portConfig.scheme().isTls() != isTls) {
                if (isTls) {
                    logger.warn("{} Received a SAML request via a TLS connection.", ctx);
                    return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT_UTF_8,
                                           DATA_NOT_CLEARTEXT);
                } else {
                    logger.warn("{} Received a SAML request via a cleartext connection.", ctx);
                    return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT_UTF_8,
                                           DATA_NOT_TLS);
                }
            }

            // Use user-specified hostname if it exists.
            // If there's no hostname set by a user, the default virtual hostname will be used.
            final String defaultHostname =
                    firstNonNull(sp.hostname(), ctx.config().virtualHost().defaultHostname());
            // assertion, logout requests incur blocking calls
            return func.serve(ctx, aggregatedReq, defaultHostname, portConfig);
        }, ctx.blockingTaskExecutor()));
    }

    /**
     * A wrapper class which holds parameters resolved from a query string.
     */
    static final class SamlParameters {
        private final QueryParams params;

        /**
         * Creates a {@link SamlParameters} instance with the specified {@link AggregatedHttpRequest}.
         */
        SamlParameters(AggregatedHttpRequest req) {
            requireNonNull(req, "req");
            final MediaType contentType = req.contentType();

            if (contentType != null && contentType.belongsTo(MediaType.FORM_DATA)) {
                final String query = req.content(contentType.charset(StandardCharsets.UTF_8));
                params = QueryParams.fromQueryString(query);
            } else {
                final String path = req.path();
                final int queryStartIdx = path.indexOf('?');
                if (queryStartIdx < 0) {
                    params = QueryParams.of();
                } else {
                    params = QueryParams.fromQueryString(path.substring(queryStartIdx + 1));
                }
            }
        }

        /**
         * Returns the first value of the parameter with the specified {@code name}.
         *
         * @throws InvalidSamlRequestException if a parameter with the specified {@code name} does not exist
         */
        String getFirstValue(String name) {
            final String value = getFirstValueOrNull(name);
            if (value == null) {
                throw new InvalidSamlRequestException("failed to get the value of a parameter: " + name);
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
            final String value = params.get(name);
            return Strings.emptyToNull(value);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("parameters", params)
                              .toString();
        }
    }
}
