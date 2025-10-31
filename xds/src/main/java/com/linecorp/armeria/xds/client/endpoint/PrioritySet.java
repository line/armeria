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

package com.linecorp.armeria.xds.client.endpoint;

import java.util.Map;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.xds.ClusterSnapshot;

/**
 * A set of {@link HostSet}s organized by priority levels.
 */
@UnstableApi
interface PrioritySet {

    /**
     * Returns the host sets mapped by priority level.
     */
    Map<Integer, HostSet> hostSets();

    /**
     * Returns the cluster snapshot associated with this priority set.
     */
    ClusterSnapshot clusterSnapshot();
}
