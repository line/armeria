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

package com.linecorp.armeria.common.outlier;

import static com.linecorp.armeria.common.outlier.OutlierDetectionBuilder.DEFAULT_DETECTION;
import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * An outlier detection provides a mechanism for identifying outliers using the specified
 * {@link OutlierRule} and {@link OutlierDetector}.
 */
@UnstableApi
public interface OutlierDetection {

    /**
     * Returns a new {@link OutlierDetection} with the specified {@link OutlierRule}.
     */
    static OutlierDetection of(OutlierRule rule) {
        requireNonNull(rule, "rule");

        if (rule == OutlierRule.of()) {
            return of();
        }
        return builder(rule).build();
    }

    /**
     * Returns a new {@link OutlierDetection} with the default {@link OutlierRule}.
     */
    static OutlierDetection of() {
        return DEFAULT_DETECTION;
    }

    /**
     * Returns a new {@link OutlierDetectionBuilder} with the specified {@link OutlierRule}.
     */
    static OutlierDetectionBuilder builder(OutlierRule rule) {
        requireNonNull(rule, "rule");
        return new OutlierDetectionBuilder(rule);
    }

    /**
     * Returns an {@link OutlierDetection} that does not detect any outliers.
     */
    static OutlierDetection disabled() {
        return DisabledOutlierDetection.INSTANCE;
    }

    /**
     * Returns the rule used for detecting failures.
     */
    OutlierRule rule();

    /**
     * Creates a new detector for detecting outliers.
     */
    OutlierDetector newDetector();
}
