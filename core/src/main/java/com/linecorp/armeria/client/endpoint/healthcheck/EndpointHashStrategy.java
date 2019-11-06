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

import java.util.Objects;

import com.linecorp.armeria.client.Endpoint;

import it.unimi.dsi.fastutil.Hash;

/**
 * A special {@link Hash.Strategy} which takes only {@link Endpoint#authority()} and
 * {@link Endpoint#ipAddr()} into account.
 */
final class EndpointHashStrategy implements Hash.Strategy<Endpoint> {

    static final EndpointHashStrategy INSTANCE = new EndpointHashStrategy();

    private EndpointHashStrategy() {}

    @Override
    public int hashCode(Endpoint e) {
        return e.authority().hashCode() * 31 + Objects.hashCode(e.ipAddr());
    }

    @Override
    public boolean equals(Endpoint a, Endpoint b) {
        return a.authority().equals(b.authority()) &&
               Objects.equals(a.ipAddr(), b.ipAddr());
    }
}
