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

package com.linecorp.armeria.xds;

import static java.util.Objects.requireNonNull;

import java.util.List;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;

/**
 * A snapshot of a {@link RouteConfiguration} resource.
 */
@UnstableApi
public final class RouteSnapshot implements Snapshot<RouteXdsResource> {

    private final RouteXdsResource routeXdsResource;
    private final List<VirtualHostSnapshot> virtualHostSnapshots;
    private final VirtualHostMatcher virtualHostMatcher;

    RouteSnapshot(RouteXdsResource routeXdsResource, List<VirtualHostSnapshot> virtualHostSnapshots) {
        this.routeXdsResource = routeXdsResource;
        this.virtualHostSnapshots = ImmutableList.copyOf(virtualHostSnapshots);
        virtualHostMatcher = new VirtualHostMatcher(
                virtualHostSnapshots, routeXdsResource.resource().getIgnorePortInHostMatching());
    }

    @Override
    public RouteXdsResource xdsResource() {
        return routeXdsResource;
    }

    /**
     * The virtual hosts represented by {@link RouteConfiguration#getVirtualHostsList()}.
     */
    public List<VirtualHostSnapshot> virtualHostSnapshots() {
        return virtualHostSnapshots;
    }

    /**
     * Selects the first matching {@link RouteEntry} for the given {@link RequestContext}
     * by matching virtual host by authority, then route by path/headers/query.
     *
     * @return the matched {@link RouteEntry}, or {@code null} if no route matches
     */
    @Nullable
    public RouteEntry select(RequestContext ctx) {
        requireNonNull(ctx, "ctx");
        final String authority;
        if (ctx instanceof ClientRequestContext) {
            authority = ((ClientRequestContext) ctx).authority();
        } else {
            final HttpRequest request = ctx.request();
            authority = request != null ? request.authority() : null;
        }
        final VirtualHostSnapshot vh = virtualHostMatcher.find(authority);
        if (vh == null) {
            return null;
        }
        for (RouteEntry entry : vh.routeEntries()) {
            if (entry.matches(ctx)) {
                return entry;
            }
        }
        return null;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        final RouteSnapshot that = (RouteSnapshot) object;
        return Objects.equal(routeXdsResource, that.routeXdsResource) &&
               Objects.equal(virtualHostSnapshots, that.virtualHostSnapshots);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(routeXdsResource, virtualHostSnapshots);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("routeXdsResource", routeXdsResource)
                          .add("virtualHostSnapshots", virtualHostSnapshots)
                          .toString();
    }

    @Override
    public String toDebugString() {
        return MoreObjects.toStringHelper(this)
                          .add("route", routeXdsResource.resource())
                          .add("virtualHostSnapshots",
                               SnapshotUtil.debugStrings(virtualHostSnapshots,
                                                         VirtualHostSnapshot::toDebugString))
                          .toString();
    }
}
