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

package com.linecorp.armeria.xds.it.athenz;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;

import com.linecorp.armeria.client.DecoratingHttpClientFunction;
import com.linecorp.armeria.client.HttpPreprocessor;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.athenz.AthenzTokenClient;
import com.linecorp.armeria.client.athenz.ZtsBaseClient;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.athenz.AthenzTokenHeader;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.athenz.AccessCheckStatus;
import com.linecorp.armeria.server.athenz.AthenzAuthorizer;
import com.linecorp.armeria.server.athenz.AthenzPolicyConfig;
import com.linecorp.armeria.xds.api.AthenzAccessTokenProto.AccessTokenConstraint;
import com.linecorp.armeria.xds.api.AthenzAccessTokenProto.AccessTokenTarget;
import com.linecorp.armeria.xds.api.AthenzAccessTokenProto.AssertionMappingRule;
import com.linecorp.armeria.xds.api.AthenzAccessTokenProto.AthenzFilterConfig;
import com.linecorp.armeria.xds.api.AthenzAccessTokenProto.AthenzFilterConfig.AthenzConfigCase;
import com.linecorp.armeria.xds.api.AthenzAccessTokenProto.EndpointAttribute;
import com.linecorp.armeria.xds.api.AthenzAccessTokenProto.EndpointAttribute.AttributeCase;
import com.linecorp.armeria.xds.api.AthenzAccessTokenProto.EndpointAttributeMatch;
import com.linecorp.armeria.xds.api.AthenzAccessTokenProto.WellKnownEndpointAttribute;
import com.linecorp.armeria.xds.filter.FactoryContext;
import com.linecorp.armeria.xds.filter.HttpFilterFactory;
import com.linecorp.armeria.xds.filter.XdsHttpFilter;
import com.linecorp.armeria.xds.internal.XdsStringMatcher;
import com.linecorp.armeria.xds.stream.SnapshotStream;
import com.linecorp.armeria.xds.stream.Subscription;

import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;

/**
 * An {@link HttpFilterFactory} that supports Athenz access token injection (outbound) and
 * authorization (inbound) via the {@code AthenzFilterConfig}.
 */
public final class AthenzAccessTokenFilterFactory implements HttpFilterFactory {

    private static final String NAME = "athenz.access_token";
    private static final String TYPE_URL =
            "type.googleapis.com/com.linecorp.armeria.xds.api.AthenzFilterConfig";
    private static final List<String> TYPE_URLS = ImmutableList.of(TYPE_URL);

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
                "athenz.access_token requires reactive cluster subscription; use createStream()");
    }

    @Override
    public SnapshotStream<XdsHttpFilter> createStream(HttpFilter httpFilter, Any config,
                                                      FactoryContext context) {
        final AthenzFilterConfig filterConfig = context.validator().unpack(config,
                                                                           AthenzFilterConfig.class);
        if (filterConfig.getAthenzConfigCase() == AthenzConfigCase.ACCESS_TOKEN_TARGET) {
            return createOutboundStream(filterConfig, context);
        } else {
            return createInboundStream(filterConfig, context);
        }
    }

    // --- Outbound: inject Bearer token into requests ---

    private SnapshotStream<XdsHttpFilter> createOutboundStream(AthenzFilterConfig filterConfig,
                                                               FactoryContext context) {
        final String ztsClusterName = filterConfig.getZtsClusterName();
        final AccessTokenTarget target = filterConfig.getAccessTokenTarget();

        return context.clusterStream(ztsClusterName).map(clusterSnapshot -> {
            final WebClient webClient = WebClient.of(clusterSnapshot.preprocessor(), "/zts/v1");
            final ZtsBaseClient ztsBaseClient = new ZtsBaseClient() {

                @Override
                public WebClient webClient() {
                    return webClient;
                }

                @Override
                public void close() {}
            };
            final AthenzTokenClient tokenClient =
                    AthenzTokenClient.builder(ztsBaseClient)
                                     .domainName(target.getTargetDomain())
                                     .roleNames(target.getTargetRolesList())
                                     .build();
            return new AthenzOutboundXdsHttpFilter(tokenClient);
        });
    }

    // --- Inbound: authorize requests using AthenzAuthorizer ---

    private SnapshotStream<XdsHttpFilter> createInboundStream(AthenzFilterConfig filterConfig,
                                                              FactoryContext context) {
        final String ztsClusterName = filterConfig.getZtsClusterName();
        final AccessTokenConstraint constraint = filterConfig.getAccessTokenConstraint();
        final String domain = constraint.getConstraintDomain();

        return context.clusterStream(ztsClusterName).switchMapEager(clusterSnapshot -> {
            // AthenzAuthorizer.build() is blocking (publicKeyStore.init() + zpeClient.init()),
            // so we offload to the blocking executor.
            return watcher -> {
                CommonPools.blockingTaskExecutor().execute(() -> {
                    try {
                        final WebClient webClient =
                                WebClient.of(clusterSnapshot.preprocessor(), "/zts/v1");
                        final ZtsBaseClient ztsBaseClient = new ZtsBaseClient() {

                            @Override
                            public WebClient webClient() {
                                return webClient;
                            }

                            @Override
                            public void close() {}
                        };
                        final AthenzAuthorizer authorizer =
                                AthenzAuthorizer.builder(ztsBaseClient)
                                                .policyConfig(new AthenzPolicyConfig(domain))
                                                .build();
                        watcher.onUpdate(new AthenzInboundXdsHttpFilter(authorizer, constraint),
                                         null);
                    } catch (Exception e) {
                        watcher.onUpdate(null, e);
                    }
                });
                return Subscription.noop();
            };
        });
    }

    private static final class AthenzOutboundXdsHttpFilter implements XdsHttpFilter {

        private final AthenzTokenClient tokenClient;

        AthenzOutboundXdsHttpFilter(AthenzTokenClient tokenClient) {
            this.tokenClient = tokenClient;
        }

        @Override
        public HttpPreprocessor httpPreprocessor() {
            return (delegate, ctx, req) -> HttpResponse.of(
                    tokenClient.getToken().thenApply(token -> {
                        ctx.setAdditionalRequestHeader("authorization", "Bearer " + token);
                        try {
                            return delegate.execute(ctx, req);
                        } catch (Exception e) {
                            return Exceptions.throwUnsafely(e);
                        }
                    }));
        }

        @Override
        public DecoratingHttpClientFunction httpDecorator() {
            return (delegate, ctx, req) -> HttpResponse.of(
                    tokenClient.getToken().thenApply(token -> {
                        ctx.setAdditionalRequestHeader("authorization", "Bearer " + token);
                        try {
                            return delegate.execute(ctx, req);
                        } catch (Exception e) {
                            return Exceptions.throwUnsafely(e);
                        }
                    }));
        }
    }

    static final class AthenzInboundXdsHttpFilter implements XdsHttpFilter {

        private final AthenzAuthorizer authorizer;
        private final boolean hasMapping;
        private final List<ParsedRule> parsedRules;

        AthenzInboundXdsHttpFilter(AthenzAuthorizer authorizer, AccessTokenConstraint constraint) {
            this.authorizer = authorizer;
            hasMapping = constraint.hasAssertionMapping();
            if (hasMapping) {
                final ImmutableList.Builder<ParsedRule> builder = ImmutableList.builder();
                for (AssertionMappingRule rule : constraint.getAssertionMapping().getRulesList()) {
                    builder.add(new ParsedRule(rule));
                }
                parsedRules = builder.build();
            } else {
                parsedRules = ImmutableList.of();
            }
        }

        @Override
        public DecoratingHttpServiceFunction serviceDecorator() {
            return (delegate, ctx, req) -> {
                final AthenzTokenHeader tokenHeader = AthenzTokenHeader.ofAccessToken();
                final String token = req.headers().get(tokenHeader.headerName(), "");
                if (token.isEmpty()) {
                    return HttpResponse.of(HttpStatus.UNAUTHORIZED);
                }
                if (!hasMapping) {
                    // No mapping — any non-empty token passes.
                    try {
                        return delegate.serve(ctx, req);
                    } catch (Exception e) {
                        return Exceptions.throwUnsafely(e);
                    }
                }
                final String[] actionResource = evaluateRules(req);
                if (actionResource == null) {
                    return HttpResponse.of(HttpStatus.FORBIDDEN);
                }
                return HttpResponse.of(
                        authorizer.authorizeAsync(token, actionResource[1], actionResource[0])
                                  .thenApply(status -> {
                                      if (status == AccessCheckStatus.ALLOW) {
                                          try {
                                              return delegate.serve(ctx, req);
                                          } catch (Exception e) {
                                              return Exceptions.<HttpResponse>throwUnsafely(e);
                                          }
                                      }
                                      return HttpResponse.of(HttpStatus.FORBIDDEN);
                                  }));
            };
        }

        @Nullable
        private String[] evaluateRules(HttpRequest req) {
            for (ParsedRule rule : parsedRules) {
                final String[] result = rule.evaluate(req);
                if (result != null) {
                    return result;
                }
            }
            return null;
        }
    }

    static final class ParsedRule {

        private final List<ParsedCondition> conditions;
        private final MappingTemplate actionTemplate;
        private final MappingTemplate resourceTemplate;

        ParsedRule(AssertionMappingRule rule) {
            final ImmutableList.Builder<ParsedCondition> builder = ImmutableList.builder();
            for (EndpointAttributeMatch condition : rule.getConditionsList()) {
                builder.add(new ParsedCondition(condition));
            }
            conditions = builder.build();
            actionTemplate = MappingTemplate.of(rule.getAction());
            resourceTemplate = MappingTemplate.of(rule.getResource());
        }

        @Nullable
        String[] evaluate(HttpRequest req) {
            final Map<String, List<String>> captures = new HashMap<>();
            for (ParsedCondition condition : conditions) {
                if (!condition.matches(req, captures)) {
                    return null;
                }
            }
            final String action = actionTemplate.resolve(req, captures);
            final String resource = resourceTemplate.resolve(req, captures);
            if (action == null || resource == null) {
                return null;
            }
            return new String[] { action, resource };
        }
    }

    static final class ParsedCondition {

        private final WellKnownEndpointAttribute wellKnown;
        private final XdsStringMatcher matcher;
        @Nullable
        private final String captureName;
        @Nullable
        private final Pattern capturePattern;

        ParsedCondition(EndpointAttributeMatch condition) {
            final EndpointAttribute attr = condition.getAttribute();
            if (attr.getAttributeCase() == AttributeCase.WELL_KNOWN) {
                wellKnown = attr.getWellKnown();
            } else {
                wellKnown = WellKnownEndpointAttribute.WELL_KNOWN_ENDPOINT_ATTRIBUTE_UNSPECIFIED;
            }
            matcher = new XdsStringMatcher(condition.getMatcher());
            if (condition.getMatcher().hasSafeRegex() && !condition.getName().isEmpty()) {
                captureName = condition.getName();
                capturePattern = Pattern.compile(condition.getMatcher().getSafeRegex().getRegex());
            } else {
                captureName = null;
                capturePattern = null;
            }
        }

        boolean matches(HttpRequest req, Map<String, List<String>> captures) {
            final String value = resolveWellKnown(wellKnown, req);
            if (value == null || !matcher.match(value)) {
                return false;
            }
            if (captureName != null) {
                assert capturePattern != null;
                final Matcher m = capturePattern.matcher(value);
                if (m.matches()) {
                    final ImmutableList.Builder<String> groups = ImmutableList.builder();
                    for (int i = 0; i <= m.groupCount(); i++) {
                        groups.add(m.group(i) != null ? m.group(i) : "");
                    }
                    captures.put(captureName, groups.build());
                }
            }
            return true;
        }

        @Nullable
        private static String resolveWellKnown(WellKnownEndpointAttribute wellKnown, HttpRequest req) {
            switch (wellKnown) {
                case WELL_KNOWN_ENDPOINT_ATTRIBUTE_HOST:
                    return req.authority();
                case WELL_KNOWN_ENDPOINT_ATTRIBUTE_METHOD:
                    return req.method().name();
                case WELL_KNOWN_ENDPOINT_ATTRIBUTE_PATH:
                    final String path = req.path();
                    final int queryIdx = path.indexOf('?');
                    return queryIdx >= 0 ? path.substring(0, queryIdx) : path;
                default:
                    return null;
            }
        }
    }
}
