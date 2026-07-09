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

package com.linecorp.armeria.xds.filter.athenz;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;

import com.linecorp.armeria.client.athenz.ZtsBaseClient;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.athenz.AthenzTokenHeader;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.athenz.AccessCheckStatus;
import com.linecorp.armeria.server.athenz.AthenzAuthorizer;
import com.linecorp.armeria.server.athenz.AthenzPolicyConfig;
import com.linecorp.armeria.xds.filter.FactoryContext;
import com.linecorp.armeria.xds.filter.HttpFilterFactory;
import com.linecorp.armeria.xds.filter.XdsHttpFilter;
import com.linecorp.armeria.xds.internal.XdsStringMatcher;
import com.linecorp.armeria.xds.stream.SnapshotStream;
import com.linecorp.armeria.xds.stream.Subscription;

import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
import jp.co.lycorp.ftd.athenz.v1.AthenzAccessToken.AccessTokenConstraint;
import jp.co.lycorp.ftd.athenz.v1.AthenzAccessToken.AssertionMappingRule;
import jp.co.lycorp.ftd.athenz.v1.AthenzAccessToken.EndpointAttribute;
import jp.co.lycorp.ftd.athenz.v1.AthenzAccessToken.EndpointAttribute.AttributeCase;
import jp.co.lycorp.ftd.athenz.v1.AthenzAccessToken.EndpointAttributeMatch;
import jp.co.lycorp.ftd.athenz.v1.AthenzAccessToken.WellKnownEndpointAttribute;
import jp.co.lycorp.ftd.athenz.v1.AthenzFilterConfig.AccessTokenConstraintConfig;

/**
 * An {@link HttpFilterFactory} that authorizes inbound requests using Athenz access tokens.
 */
public final class AccessTokenConstraintFilterFactory implements HttpFilterFactory {

    AccessTokenConstraintFilterFactory() {}

    private static final String NAME = "athenz.access_token_constraint";
    private static final String TYPE_URL =
            "type.googleapis.com/jp.co.lycorp.ftd.athenz.v1.AccessTokenConstraintConfig";
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
                NAME + " requires reactive cluster subscription; use createStream()");
    }

    @Override
    public SnapshotStream<XdsHttpFilter> createStream(HttpFilter httpFilter, Any config,
                                                      FactoryContext context) {
        final AccessTokenConstraintConfig filterConfig =
                context.validator().unpack(config, AccessTokenConstraintConfig.class);
        final String ztsClusterName = filterConfig.getZtsClusterName();
        final AccessTokenConstraint constraint = filterConfig.getAccessTokenConstraint();
        final String domain = constraint.getConstraintDomain();

        return context.clusterStream(ztsClusterName).switchMapEager(clusterSnapshot -> {
            // AthenzAuthorizer.build() is blocking (publicKeyStore.init() + zpeClient.init()),
            // so we offload to the blocking executor.
            return watcher -> {
                CommonPools.blockingTaskExecutor().execute(() -> {
                    try {
                        final ZtsBaseClient ztsBaseClient =
                                new XdsZtsBaseClient(clusterSnapshot.preprocessor());
                        final AthenzAuthorizer authorizer =
                                AthenzAuthorizer.builder(ztsBaseClient)
                                                .policyConfig(new AthenzPolicyConfig(domain))
                                                .build();
                        watcher.onUpdate(new InboundXdsHttpFilter(authorizer, constraint), null);
                    } catch (Exception e) {
                        watcher.onUpdate(null, e);
                    }
                });
                return Subscription.noop();
            };
        });
    }

    private static final class InboundXdsHttpFilter implements XdsHttpFilter {

        private final AthenzAuthorizer authorizer;
        private final List<ParsedRule> parsedRules;

        InboundXdsHttpFilter(AthenzAuthorizer authorizer, AccessTokenConstraint constraint) {
            this.authorizer = authorizer;
            if (constraint.hasAssertionMapping()) {
                parsedRules = constraint.getAssertionMapping().getRulesList().stream()
                                        .map(ParsedRule::new)
                                        .collect(ImmutableList.toImmutableList());
            } else {
                // Default mapping: action = lower(method), resource = request path.
                parsedRules = ImmutableList.of(ParsedRule.defaultRule());
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
                final ActionResource actionResource = evaluateRules(ctx);
                if (actionResource == null) {
                    return HttpResponse.of(HttpStatus.FORBIDDEN);
                }
                return HttpResponse.of(
                        authorizer.authorizeAsync(token, actionResource.resource,
                                                  actionResource.action)
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
        private ActionResource evaluateRules(ServiceRequestContext ctx) {
            for (ParsedRule rule : parsedRules) {
                final ActionResource result = rule.evaluate(ctx);
                if (result != null) {
                    return result;
                }
            }
            return null;
        }
    }

    private static final class ParsedRule {

        private final List<ParsedCondition> conditions;
        @Nullable
        private final MappingTemplate actionTemplate;
        @Nullable
        private final MappingTemplate resourceTemplate;

        ParsedRule(AssertionMappingRule rule) {
            conditions = rule.getConditionsList().stream()
                             .map(ParsedCondition::new)
                             .collect(ImmutableList.toImmutableList());
            actionTemplate = MappingTemplate.of(rule.getAction());
            resourceTemplate = MappingTemplate.of(rule.getResource());
        }

        private ParsedRule() {
            conditions = ImmutableList.of();
            actionTemplate = null;
            resourceTemplate = null;
        }

        static ParsedRule defaultRule() {
            return new ParsedRule();
        }

        @Nullable
        ActionResource evaluate(ServiceRequestContext ctx) {
            if (actionTemplate == null || resourceTemplate == null) {
                // Default mapping: action = lower(method), resource = path.
                return new ActionResource(ctx.method().name().toLowerCase(Locale.ENGLISH), ctx.path());
            }

            final Map<String, List<String>> captures = new HashMap<>();
            for (ParsedCondition condition : conditions) {
                if (!condition.matchAndCapture(ctx, captures)) {
                    return null;
                }
            }
            final String action = actionTemplate.resolve(ctx, captures);
            final String resource = resourceTemplate.resolve(ctx, captures);
            if (action == null || resource == null) {
                return null;
            }
            return new ActionResource(action, resource);
        }
    }

    private static final class ParsedCondition {

        private final WellKnownEndpointAttribute wellKnown;
        private final XdsStringMatcher matcher;
        @Nullable
        private final String captureName;
        @Nullable
        private final Pattern capturePattern;

        ParsedCondition(EndpointAttributeMatch condition) {
            final EndpointAttribute attr = condition.getAttribute();
            if (attr.getAttributeCase() != AttributeCase.WELL_KNOWN) {
                throw new IllegalArgumentException(
                        "Unsupported attribute case: " + attr.getAttributeCase());
            }
            wellKnown = attr.getWellKnown();
            matcher = new XdsStringMatcher(condition.getMatcher());
            if (condition.getMatcher().hasSafeRegex() && !condition.getName().isEmpty()) {
                captureName = condition.getName();
                capturePattern = Pattern.compile(condition.getMatcher().getSafeRegex().getRegex());
            } else {
                captureName = null;
                capturePattern = null;
            }
        }

        boolean matchAndCapture(ServiceRequestContext ctx, Map<String, List<String>> captures) {
            final String value = resolveWellKnown(wellKnown, ctx);
            if (value == null || !matcher.match(value)) {
                return false;
            }
            if (captureName != null) {
                assert capturePattern != null;
                final Matcher m = capturePattern.matcher(value);
                if (!m.matches()) {
                    return false;
                }
                final ImmutableList.Builder<String> groups = ImmutableList.builder();
                for (int i = 0; i <= m.groupCount(); i++) {
                    if (m.group(i) == null) {
                        return false;
                    }
                    groups.add(m.group(i));
                }
                captures.put(captureName, groups.build());
            }
            return true;
        }

        @Nullable
        private static String resolveWellKnown(WellKnownEndpointAttribute wellKnown,
                                               ServiceRequestContext ctx) {
            switch (wellKnown) {
                case WELL_KNOWN_ENDPOINT_ATTRIBUTE_HOST:
                    return ctx.request().authority();
                case WELL_KNOWN_ENDPOINT_ATTRIBUTE_METHOD:
                    return ctx.method().name();
                case WELL_KNOWN_ENDPOINT_ATTRIBUTE_PATH:
                    return ctx.path();
                default:
                    throw new IllegalArgumentException(
                            "Unsupported well-known attribute: " + wellKnown);
            }
        }
    }

    private static final class ActionResource {
        final String action;
        final String resource;

        ActionResource(String action, String resource) {
            this.action = action;
            this.resource = resource;
        }
    }
}
