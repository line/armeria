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

package com.linecorp.armeria.client.endpoint.healthcheck;

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.client.endpoint.healthcheck.HealthCheckedEndpointGroup.DEFAULT_HEALTHCHECK_RETRY_INTERVAL;
import static java.util.Objects.requireNonNull;

import java.time.Duration;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.SessionProtocol;

/**
 * A builder for creating new {@link HttpHealthCheckedEndpointGroup}s.
 */
public class HttpHealthCheckedEndpointGroupBuilder {

    private final EndpointGroup delegate;
    private final String healthCheckPath;

    private SessionProtocol healthCheckProtocol = SessionProtocol.HTTP;
    private Duration healthCheckRetryInterval = DEFAULT_HEALTHCHECK_RETRY_INTERVAL;
    private ClientFactory clientFactory = ClientFactory.DEFAULT;

    /**
     * Creates a new {@link HttpHealthCheckedEndpointGroupBuilder}. Health check requests for the delegate
     * {@link EndpointGroup} will be made against {@code healthCheckPath}.
     */
    public HttpHealthCheckedEndpointGroupBuilder(EndpointGroup delegate, String healthCheckPath) {
        this.delegate = requireNonNull(delegate, "delegate");
        this.healthCheckPath = requireNonNull(healthCheckPath, "healthCheckPath");
    }

    /**
     * Sets the {@link SessionProtocol} to be used when making health check requests.
     */
    public HttpHealthCheckedEndpointGroupBuilder healthCheckProtocol(SessionProtocol healthCheckProtocol) {
        this.healthCheckProtocol = requireNonNull(healthCheckProtocol, "healthCheckProtocol");
        return this;
    }

    /**
     * Sets the interval between health check requests. Must be positive.
     */
    public HttpHealthCheckedEndpointGroupBuilder healthCheckRetryInterval(Duration healthCheckRetryInterval) {
        requireNonNull(healthCheckRetryInterval, "healthCheckRetryInterval");
        checkArgument(!healthCheckRetryInterval.isNegative() && !healthCheckRetryInterval.isZero(),
                      "healthCheckRetryInterval must be positive.");
        this.healthCheckRetryInterval = healthCheckRetryInterval;
        return this;
    }

    /**
     * Sets the {@link ClientFactory} to use when making health check requests. This should generally be the
     * same as the {@link ClientFactory} used when creating a {@link Client} stub using the
     * {@link EndpointGroup}.
     */
    public HttpHealthCheckedEndpointGroupBuilder clientFactory(ClientFactory clientFactory) {
        this.clientFactory = requireNonNull(clientFactory, "clientFactory");
        return this;
    }

    /**
     * Returns a newly created {@link HttpHealthCheckedEndpointGroup} based on the contents of the
     * {@link HttpHealthCheckedEndpointGroupBuilder}.
     */
    public HttpHealthCheckedEndpointGroup build() {
        return new HttpHealthCheckedEndpointGroup(clientFactory, delegate, healthCheckProtocol, healthCheckPath,
                                                  healthCheckRetryInterval);
    }
}
