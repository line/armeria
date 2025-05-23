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

package com.linecorp.armeria.client.hedging;

import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.annotation.Nullable;

public abstract class AbstractHedgingClientBuilder<O extends Response> {

    @Nullable
    private final HedgingConfigBuilder<O> hedgingConfigBuilder;

    @Nullable
    private final HedgingConfigMapping<O> mapping;

    AbstractHedgingClientBuilder(HedgingConfig<O> hedgingConfig) {
        hedgingConfigBuilder = requireNonNull(hedgingConfig, "hedgingConfig").toBuilder();
        mapping = null;
    }


    AbstractHedgingClientBuilder(HedgingConfigMapping<O> mapping) {
        this.mapping = requireNonNull(mapping, "mapping");
        hedgingConfigBuilder = null;
    }

    final HedgingConfigMapping<O> mapping() {
        if (mapping == null) {
            final HedgingConfig<O> config = hedgingConfig();
            assert config != null;
            return (ctx, req) -> config;
        }
        return mapping;
    }

    @Nullable
    final HedgingConfig<O> hedgingConfig() {
        if (hedgingConfigBuilder == null) {
            return null;
        }
        return hedgingConfigBuilder.build();
    }

    @Override
    public String toString() {
        return toStringHelper().toString();
    }

    final ToStringHelper toStringHelper() {
        if (hedgingConfigBuilder == null) {
            return MoreObjects.toStringHelper(this).add("mapping", mapping);
        }
        return hedgingConfigBuilder.toStringHelper();
    }
}
