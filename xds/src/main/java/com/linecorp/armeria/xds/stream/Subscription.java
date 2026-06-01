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

package com.linecorp.armeria.xds.stream;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A handle returned by {@link SnapshotStream#subscribe} that can be closed to
 * cancel the subscription. Closing a subscription stops the subscriber from
 * receiving further updates.
 */
@UnstableApi
@FunctionalInterface
public interface Subscription {

    /**
     * Returns a no-op subscription that does nothing when closed.
     */
    static Subscription noop() {
        return () -> {};
    }

    /**
     * Closes this subscription, stopping delivery of further updates to the subscriber.
     */
    void close();
}
