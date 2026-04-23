/*
 * Copyright 2026 LY Corporation
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

import java.util.Set;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.SafeCloseable;

/**
 * A subscription that delivers xDS resources from a custom config source.
 *
 * @see SotwConfigSourceSubscriptionFactory
 */
@UnstableApi
public interface ConfigSourceSubscription extends SafeCloseable {

    /**
     * Updates the resource names that this subscription is interested in.
     */
    void updateInterests(XdsType type, Set<String> resourceNames);
}
