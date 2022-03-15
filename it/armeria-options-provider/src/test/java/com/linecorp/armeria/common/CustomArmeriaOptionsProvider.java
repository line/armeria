/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.common;

public final class CustomArmeriaOptionsProvider implements ArmeriaOptionsProvider {

    /**
     * @see ArmeriaOptionsProviderTest#overrideDefaultArmeriaOptionsProvider
     */
    @Override
    public boolean useOpenSsl() {
        return false;
    }

    /**
     * @see ArmeriaOptionsProviderTest#overrideDefaultArmeriaOptionsProvider
     */
    @Override
    public int numCommonBlockingTaskThreads() {
        return 100;
    }

    /**
     * @see ArmeriaOptionsProviderTest#spiInvalidFallbackToDefault
     */
    @Override
    public long defaultRequestTimeoutMillis() {
        return -10L; //invalid value
    }

    /**
     * @see ArmeriaOptionsProviderTest#spiInvalidFallbackToDefault
     */
    @Override
    public String defaultBackoffSpec() {
        return "invalid backoff spec";
    }

    /**
     * @see ArmeriaOptionsProviderTest#jvmOptionInvalidFallbackToSpi
     */
    @Override
    public int defaultMaxTotalAttempts() {
        return 5;
    }

    /**
     * @see ArmeriaOptionsProviderTest#jvmOptionPriorityHigherThanSpi
     */
    @Override
    public long defaultMaxClientConnectionAgeMillis() {
        return 10;
    }
}
