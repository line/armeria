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

import static com.google.common.base.Preconditions.checkArgument;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.endpoint.healthcheck.PartialHealthCheckStrategy.TargetCount;

/**
 * A builder for creating a new {@link PartialHealthCheckStrategy}.
 */
class PartialHealthCheckStrategyBuilder {

    @Nullable
    private Integer maxEndpointCount;

    @Nullable
    private Double maxEndpointRatio;

    /**
     * Sets the maximum endpoint count of target selected candidates.
     * The maximum endpoint count must greater than 0.
     * You can use only one of the maximum endpoint count or maximum endpoint ratio.
     */
    public PartialHealthCheckStrategyBuilder maxEndpointCount(int maxEndpointCount) {
        if (maxEndpointRatio != null) {
            throw new IllegalArgumentException("Maximum endpoint ratio is already set.");
        }

        checkArgument(maxEndpointCount > 0, "maxEndpointCount: %s (expected: 0 < maxEndpointCount <= MAX_INT)",
                      maxEndpointCount);

        this.maxEndpointCount = maxEndpointCount;
        return this;
    }

    /**
     * Sets the maximum endpoint ratio of target selected candidates.
     * The maximum endpoint ratio must greater than 0 and less or equal to 1.
     * You can use only one of the maximum endpoint count or maximum endpoint ratio.
     */
    public PartialHealthCheckStrategyBuilder maxEndpointRatio(double maxEndpointRatio) {
        if (maxEndpointCount != null) {
            throw new IllegalArgumentException("Maximum endpoint count is already set.");
        }

        checkArgument(maxEndpointRatio > 0 && maxEndpointRatio <= 1,
                      "maxEndpointRatio: %s (expected: 0 < maxEndpointRatio <= 1)", maxEndpointRatio);

        this.maxEndpointRatio = maxEndpointRatio;
        return this;
    }

    /**
     * Returns a newly created {@link PartialHealthCheckStrategy} based on the properties set so far.
     */
    public PartialHealthCheckStrategy build() {
        final TargetCount targetCount;
        if (maxEndpointCount != null) {
            targetCount = TargetCount.ofCount(maxEndpointCount);
        } else if (maxEndpointRatio != null) {
            targetCount = TargetCount.ofRatio(maxEndpointRatio);
        } else {
            targetCount = null;
        }

        if (targetCount == null) {
            throw new IllegalStateException("The maximum must be set.");
        }

        return new PartialHealthCheckStrategy(targetCount);
    }
}
