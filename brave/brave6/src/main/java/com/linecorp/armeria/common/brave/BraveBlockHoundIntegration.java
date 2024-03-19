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

package com.linecorp.armeria.common.brave;

import com.linecorp.armeria.common.annotation.UnstableApi;

import reactor.blockhound.BlockHound.Builder;
import reactor.blockhound.integration.BlockHoundIntegration;

/**
 * A {@link BlockHoundIntegration} for the brave module.
 */
@UnstableApi
public final class BraveBlockHoundIntegration implements BlockHoundIntegration {

    @Override
    public void applyTo(Builder builder) {
        // zipkin-reporter v2
        builder.allowBlockingCallsInside("zipkin2.reporter.AsyncReporter$BoundedAsyncReporter", "report");
        // zipkin-reporter v3
        builder.allowBlockingCallsInside(
            "zipkin2.reporter.internal.AsyncReporter$BoundedAsyncReporter", "report");
    }
}
