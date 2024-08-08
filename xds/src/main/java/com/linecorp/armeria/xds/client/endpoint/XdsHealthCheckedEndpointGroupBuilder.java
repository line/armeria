/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.xds.client.endpoint;

import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.healthcheck.AbstractHealthCheckedEndpointGroupBuilder;
import com.linecorp.armeria.client.endpoint.healthcheck.HealthCheckerContext;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.internal.client.endpoint.healthcheck.HttpHealthChecker;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.HealthCheck.HttpHealthCheck;
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint.HealthCheckConfig;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;

final class XdsHealthCheckedEndpointGroupBuilder
        extends AbstractHealthCheckedEndpointGroupBuilder<XdsHealthCheckedEndpointGroupBuilder> {

    private static final Logger logger = LoggerFactory.getLogger(XdsHealthCheckedEndpointGroupBuilder.class);

    private final Cluster cluster;
    private final HttpHealthCheck httpHealthCheck;

    XdsHealthCheckedEndpointGroupBuilder(EndpointGroup delegate, Cluster cluster,
                                         HttpHealthCheck httpHealthCheck) {
        super(delegate);
        this.cluster = cluster;
        this.httpHealthCheck = httpHealthCheck;
    }

    @Override
    protected Function<? super HealthCheckerContext, ? extends AsyncCloseable> newCheckerFactory() {
        return ctx -> {
            final LbEndpoint lbEndpoint = EndpointUtil.lbEndpoint(ctx.originalEndpoint());
            final HealthCheckConfig healthCheckConfig = lbEndpoint.getEndpoint().getHealthCheckConfig();
            final String path = httpHealthCheck.getPath();
            final String host = Strings.emptyToNull(httpHealthCheck.getHost());

            final HttpHealthChecker checker =
                    new HttpHealthChecker(ctx, endpoint(healthCheckConfig, ctx.originalEndpoint()),
                                          path, httpMethod(httpHealthCheck) == HttpMethod.GET,
                                          protocol(cluster), host);
            checker.start();
            return checker;
        };
    }

    private static HttpMethod httpMethod(HttpHealthCheck httpHealthCheck) {
        final HttpMethod method = EndpointUtil.convert(httpHealthCheck.getMethod(), HttpMethod.GET);
        if (method != HttpMethod.GET && method != HttpMethod.HEAD) {
            logger.warn("Unsupported http method<{}> for HttpHealthCheck<{}>. Only GET and HEAD " +
                        "are supported. Falling back to GET for health checks.", method, httpHealthCheck);
            return HttpMethod.GET;
        }
        return method;
    }

    private static SessionProtocol protocol(Cluster cluster) {
        // Not using httpHealthCheck.getCodecClientType() because
        // HTTP[S] covers both HTTP/1 and HTTP/2.
        if (EndpointUtil.isTls(cluster)) {
            return SessionProtocol.HTTPS;
        } else {
            return SessionProtocol.HTTP;
        }
    }

    private static Endpoint endpoint(HealthCheckConfig healthCheckConfig, Endpoint endpoint) {
        if (healthCheckConfig == HealthCheckConfig.getDefaultInstance()) {
            return endpoint;
        }
        final int port = healthCheckConfig.getPortValue();
        if (port > 0) {
            endpoint = endpoint.withPort(port);
        }
        if (healthCheckConfig.hasAddress()) {
            return endpoint.withHost(healthCheckConfig.getAddress().getSocketAddress().getAddress());
        }
        return endpoint;
    }
}
