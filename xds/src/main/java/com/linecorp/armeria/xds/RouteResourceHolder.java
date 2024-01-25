/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.xds;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;

/**
 * A resource holder object for a {@link RouteConfiguration}.
 */
public final class RouteResourceHolder
        extends ResourceHolderWithPrimer<RouteResourceHolder, RouteConfiguration, ListenerResourceHolder> {

    private final RouteConfiguration routeConfiguration;

    @Nullable
    private final ListenerResourceHolder primer;

    RouteResourceHolder(RouteConfiguration routeConfiguration) {
        this.routeConfiguration = routeConfiguration;
        primer = null;
    }

    RouteResourceHolder(RouteConfiguration routeConfiguration, ListenerResourceHolder primer) {
        this.routeConfiguration = routeConfiguration;
        this.primer = primer;
    }

    @Override
    public XdsType type() {
        return XdsType.ROUTE;
    }

    @Override
    public RouteConfiguration resource() {
        return routeConfiguration;
    }

    @Override
    public String name() {
        return routeConfiguration.getName();
    }

    @Override
    RouteResourceHolder withPrimer(@Nullable ListenerResourceHolder primer) {
        if (primer == null) {
            return this;
        }
        return new RouteResourceHolder(routeConfiguration, primer);
    }

    @Override
    @Nullable
    ListenerResourceHolder primer() {
        return primer;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        final RouteResourceHolder that = (RouteResourceHolder) object;
        return Objects.equal(routeConfiguration, that.routeConfiguration) &&
               Objects.equal(primer, that.primer);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(routeConfiguration, primer);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("routeConfiguration", routeConfiguration)
                          .add("primer", primer)
                          .toString();
    }
}
