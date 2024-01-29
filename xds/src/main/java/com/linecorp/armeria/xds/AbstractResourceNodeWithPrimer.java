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

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;

abstract class AbstractResourceNodeWithPrimer<T extends XdsResourceWithPrimer<T>>
        extends AbstractResourceNode<T> {

    @Nullable
    private final XdsResource primer;

    AbstractResourceNodeWithPrimer(XdsBootstrapImpl xdsBootstrap, @Nullable ConfigSource configSource,
                                   XdsType type, String resourceName, @Nullable XdsResource primer,
                                   SnapshotWatcher<? extends Snapshot<T>> parentWatcher,
                                   ResourceNodeType resourceNodeType) {
        super(xdsBootstrap, configSource, type, resourceName, parentWatcher, resourceNodeType);
        this.primer = primer;
    }

    @Override
    public void onChanged(T update) {
        assert update.type() == type();
        super.onChanged(update.withPrimer(primer));
    }
}
