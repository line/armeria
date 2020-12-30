/*
 * Copyright 2020 LINE Corporation
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
/*
 * Copyright (c)  2020 Oracle and/or its affiliates. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.linecorp.armeria.common.multipart;

import org.reactivestreams.Subscription;

/**
 * Helper enum with a singleton cancellation indicator and utility methods to perform
 * atomic actions on {@link Subscription}s.
 */
enum CancelledSubscription implements Subscription {

    // Forked from https://github.com/oracle/helidon/blob/ab23ce10cb55043e5e4beea1037a65bb8968354b/common/reactive/src/main/java/io/helidon/common/reactive/SubscriptionHelper.java

    /**
     * The singleton instance indicating a cancelled subscription.
     */
    CANCELLED;

    @Override
    public void request(long n) {
        // deliberately no-op.
    }

    @Override
    public void cancel() {
        // deliberately no-op
    }
}
