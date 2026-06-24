/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.armeria.xds;

import com.linecorp.armeria.common.annotation.UnstableApi;

import io.envoyproxy.envoy.config.listener.v3.Listener;

/**
 * A resource object for a {@link Listener}.
 */
@UnstableApi
public final class ListenerXdsResource extends AbstractXdsResource {

    private final Listener listener;

    ListenerXdsResource(Listener listener, String version) {
        this(listener, version, 0);
    }

    private ListenerXdsResource(Listener listener, String version, long revision) {
        super(version, revision);
        this.listener = listener;
    }

    @Override
    public XdsType type() {
        return XdsType.LISTENER;
    }

    @Override
    public Listener resource() {
        return listener;
    }

    @Override
    public String name() {
        return listener.getName();
    }

    @Override
    ListenerXdsResource withRevision(long revision) {
        if (revision == revision()) {
            return this;
        }
        return new ListenerXdsResource(listener, version(), revision);
    }
}
