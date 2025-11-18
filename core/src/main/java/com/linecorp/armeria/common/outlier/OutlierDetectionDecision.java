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

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Represents the decision of outlier detection.
 */
@UnstableApi
public enum OutlierDetectionDecision {
    /**
     * The request is considered as successful. {@link OutlierDetector#onSuccess()} should be called to update
     * the outlier detection state.
     */
    SUCCESS,
    /**
     * The request is considered as a failure. The remote peer is not considered as an outlier immediately.
     * {@link OutlierDetector#onFailure()} should be called to update the outlier detection state.
     */
    FAILURE,
    /**
     * The request is fatal so the remote peer is considered as an outlier immediately.
     */
    FATAL,
    /**
     * The request or its result should be ignored in the outlier detection process.
     */
    IGNORE,
    /**
     * The outlier detection should be delegated to the next {@link OutlierRule}.
     */
    NEXT
}
