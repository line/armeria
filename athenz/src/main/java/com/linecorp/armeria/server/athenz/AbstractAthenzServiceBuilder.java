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

package com.linecorp.armeria.server.athenz;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.client.athenz.ZtsBaseClient;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.metric.MeterIdPrefix;

/**
 * A base builder for creating an Athenz service that checks access permissions using Athenz policies.
 */
@UnstableApi
public abstract class AbstractAthenzServiceBuilder<SELF extends AbstractAthenzServiceBuilder<SELF>>
        extends AbstractAthenzAuthorizerBuilder<SELF> {

    static final MeterIdPrefix DEFAULT_METER_ID_PREFIX = new MeterIdPrefix("armeria.server.athenz");

    private MeterIdPrefix meterIdPrefix = DEFAULT_METER_ID_PREFIX;

    AbstractAthenzServiceBuilder(ZtsBaseClient ztsBaseClient) {
        super(ztsBaseClient);
    }

    /**
     * Sets the {@link MeterIdPrefix} of the metrics collected through {@link AthenzService}.
     * If not set, a default {@link MeterIdPrefix} with the name {@code "armeria.server.athenz"} is used.
     */
    public SELF meterIdPrefix(MeterIdPrefix meterIdPrefix) {
        this.meterIdPrefix = requireNonNull(meterIdPrefix, "meterIdPrefix");
        return self();
    }

    MeterIdPrefix meterIdPrefix() {
        return meterIdPrefix;
    }

    @SuppressWarnings("unchecked")
    private SELF self() {
        return (SELF) this;
    }
}
