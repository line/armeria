/*
 * Copyright 2019 LINE Corporation
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

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.function.Function;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOptionsBuilder;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.SessionProtocol;

/**
 * A builder for creating a new {@link HealthCheckedEndpointGroup} that sends HTTP health check requests.
 */
public class HealthCheckedEndpointGroupBuilder extends AbstractHealthCheckedEndpointGroupBuilder {

    HealthCheckedEndpointGroupBuilder(EndpointGroup delegate, String path) {
        super(delegate, ctx -> {
            final HttpHealthChecker checker = new HttpHealthChecker(ctx, path);
            checker.start();
            return checker;
        });
        requireNonNull(path, "path");
    }

    @Override
    public HealthCheckedEndpointGroupBuilder clientFactory(ClientFactory clientFactory) {
        return (HealthCheckedEndpointGroupBuilder) super.clientFactory(clientFactory);
    }

    @Override
    public HealthCheckedEndpointGroupBuilder protocol(SessionProtocol protocol) {
        return (HealthCheckedEndpointGroupBuilder) super.protocol(protocol);
    }

    @Override
    public HealthCheckedEndpointGroupBuilder healthCheckPort(int port) {
        return (HealthCheckedEndpointGroupBuilder) super.healthCheckPort(port);
    }

    @Override
    public HealthCheckedEndpointGroupBuilder port(int port) {
        return (HealthCheckedEndpointGroupBuilder) super.port(port);
    }

    @Override
    public HealthCheckedEndpointGroupBuilder retryInterval(Duration retryInterval) {
        return (HealthCheckedEndpointGroupBuilder) super.retryInterval(retryInterval);
    }

    @Override
    public HealthCheckedEndpointGroupBuilder retryBackoff(Backoff retryBackoff) {
        return (HealthCheckedEndpointGroupBuilder) super.retryBackoff(retryBackoff);
    }

    @Override
    public HealthCheckedEndpointGroupBuilder withClientOptions(
            Function<? super ClientOptionsBuilder, ClientOptionsBuilder> configurator) {
        return (HealthCheckedEndpointGroupBuilder) super.withClientOptions(configurator);
    }
}
