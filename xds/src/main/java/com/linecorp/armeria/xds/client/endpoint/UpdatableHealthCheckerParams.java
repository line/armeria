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
import com.linecorp.armeria.client.endpoint.healthcheck.HealthCheckerParams;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.HealthCheck.HttpHealthCheck;
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint.HealthCheckConfig;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;

final class UpdatableHealthCheckerParams implements Function<Endpoint, HealthCheckerParams> {

    private static final Logger logger = LoggerFactory.getLogger(UpdatableHealthCheckerParams.class);

    @Nullable
    private Cluster cluster;
    @Nullable
    private HttpHealthCheck httpHealthCheck;

    void updateHttpHealthCheck(Cluster cluster, HttpHealthCheck httpHealthCheck) {
        this.cluster = cluster;
        this.httpHealthCheck = httpHealthCheck;
    }

    @Override
    public HealthCheckerParams apply(Endpoint endpoint) {
        final Cluster cluster = this.cluster;
        final HttpHealthCheck httpHealthCheck = this.httpHealthCheck;
        if (cluster == null || httpHealthCheck == null) {
            throw new IllegalStateException();
        }
        return new EnvoyHealthCheckParams(endpoint, cluster, httpHealthCheck);
    }

    static class EnvoyHealthCheckParams implements HealthCheckerParams {

        private final Endpoint endpoint;
        private final Cluster cluster;
        private final HttpHealthCheck httpHealthCheck;
        private final HealthCheckConfig healthCheckConfig;

        EnvoyHealthCheckParams(Endpoint endpoint, Cluster cluster, HttpHealthCheck httpHealthCheck) {
            this.endpoint = endpoint;
            this.cluster = cluster;
            this.httpHealthCheck = httpHealthCheck;
            final LbEndpoint lbEndpoint = EndpointUtil.lbEndpoint(endpoint);
            healthCheckConfig = lbEndpoint.getEndpoint().getHealthCheckConfig();
        }

        @Override
        public String path() {
            return httpHealthCheck.getPath();
        }

        @Override
        public HttpMethod httpMethod() {
            final HttpMethod method = ConverterUtil.convert(httpHealthCheck.getMethod(), HttpMethod.GET);
            if (method != HttpMethod.GET && method != HttpMethod.HEAD) {
                logger.warn("Unsupported http method: {}. Only GET and HEAD are supported. " +
                            "Falling back to GET for health checks.", method);
                return HttpMethod.GET;
            }
            return method;
        }

        @Override
        @Nullable
        public String host() {
            return Strings.emptyToNull(httpHealthCheck.getHost());
        }

        @Override
        public SessionProtocol protocol() {
            // Not using httpHealthCheck.getCodecClientType() because
            // HTTP[S] covers both HTTP/1 and HTTP/2.
            if (EndpointUtil.isTls(cluster)) {
                return SessionProtocol.HTTPS;
            } else {
                return SessionProtocol.HTTP;
            }
        }

        @Override
        public Endpoint endpoint() {
            if (healthCheckConfig == HealthCheckConfig.getDefaultInstance()) {
                return endpoint;
            }
            final int port = healthCheckConfig.getPortValue();
            if (healthCheckConfig.hasAddress()) {
                return Endpoint.of(healthCheckConfig.getAddress().getSocketAddress().getAddress())
                               .withPort(port);
            }
            return endpoint.withPort(port);
        }
    }
}
