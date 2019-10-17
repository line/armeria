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
public class PartialHealthCheckStrategyBuilder {

    @Nullable
    private Integer maxValue;

    @Nullable
    private Double maxRatio;

    /**
     * Sets the maximum value of target selected candidates.
     * The maximum value must greater than 0.
     * You can use only one of the maximum value or maximum ratio.
     */
    public PartialHealthCheckStrategyBuilder maxValue(int maxValue) {
        if (maxRatio != null) {
            throw new IllegalArgumentException("Maximum ratio is already set.");
        }

        checkArgument(maxValue > 0, "maxValue: %s (expected: 1 - MAX_INT)", maxValue);

        this.maxValue = maxValue;
        return this;
    }

    /**
     * Sets the maximum ratio of target selected candidates.
     * The maximum ratio must greater than 0 and less or equal to 1.
     * You can use only one of the maximum value or maximum ratio.
     */
    public PartialHealthCheckStrategyBuilder maxRatio(double maxRatio) {
        if (maxValue != null) {
            throw new IllegalArgumentException("Maximum value is already set.");
        }

        checkArgument(maxRatio > 0 && maxRatio <= 1,
                      "maxRatio: %s (expected: 0.x - 1)", maxRatio);

        this.maxRatio = maxRatio;
        return this;
    }

    /**
     * Returns a newly created {@link PartialHealthCheckStrategy} based on the properties set so far.
     */
    public PartialHealthCheckStrategy build() {
        final TargetCount targetCount;
        if (maxValue != null) {
            targetCount = TargetCount.ofValue(maxValue);
        } else if (maxRatio != null) {
            targetCount = TargetCount.ofRatio(maxRatio);
        } else {
            targetCount = null;
        }

        if (targetCount == null) {
            throw new IllegalStateException("The maximum must be set.");
        }

        return new PartialHealthCheckStrategy(targetCount);
    }
}
