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

package com.linecorp.armeria.client.endpoint.healthcheck;

import java.util.function.Function;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;

final class TestHealthCheckParamsFactory implements Function<Endpoint, HealthCheckerParams> {

    static final Function<Endpoint, HealthCheckerParams> INSTANCE = new TestHealthCheckParamsFactory();

    private TestHealthCheckParamsFactory() {}

    @Override
    public HealthCheckerParams apply(Endpoint endpoint) {
        return new HealthCheckerParams() {
            @Override
            public String path() {
                return "/";
            }

            @Override
            public HttpMethod httpMethod() {
                return HttpMethod.GET;
            }

            @Override
            @Nullable
            public String host() {
                return null;
            }

            @Override
            public SessionProtocol protocol() {
                return SessionProtocol.HTTP;
            }

            @Override
            public Endpoint endpoint() {
                return endpoint;
            }
        };
    }
}
