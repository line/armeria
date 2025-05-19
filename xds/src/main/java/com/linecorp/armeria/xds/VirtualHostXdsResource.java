/*
 * Copyright 2025 LINE Corporation
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

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.route.v3.VirtualHost;

/**
 * A resource object for a {@link VirtualHost}.
 */
public final class VirtualHostXdsResource extends XdsResourceWithPrimer<VirtualHostXdsResource> {

    private final VirtualHost virtualHost;
    @Nullable
    private final XdsResource primer;

    VirtualHostXdsResource(VirtualHost virtualHost) {
        this.virtualHost = virtualHost;
        primer = null;
    }

    private VirtualHostXdsResource(VirtualHost virtualHost, @Nullable XdsResource primer) {
        this.virtualHost = virtualHost;
        this.primer = primer;
    }

    @Override
    public XdsType type() {
        return XdsType.VIRTUAL_HOST;
    }

    @Override
    public VirtualHost resource() {
        return virtualHost;
    }

    @Override
    public String name() {
        return virtualHost.getName();
    }

    @Override
    VirtualHostXdsResource withPrimer(@Nullable XdsResource primer) {
        return new VirtualHostXdsResource(virtualHost, primer);
    }

    @Nullable
    @Override
    XdsResource primer() {
        return primer;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("virtualHost", virtualHost)
                          .add("primer", primer)
                          .toString();
    }
}
