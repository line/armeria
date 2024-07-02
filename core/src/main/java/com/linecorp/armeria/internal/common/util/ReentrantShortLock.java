/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.internal.common.util;

import java.util.concurrent.locks.ReentrantLock;

import com.linecorp.armeria.common.CoreBlockHoundIntegration;

/**
 * A short lock which is whitelisted by {@link CoreBlockHoundIntegration}.
 * This lock may be preferred over {@link ReentrantLock} when it is known that the
 * lock won't block the event loop over long periods of time.
 */
public class ReentrantShortLock extends ReentrantLock {
    private static final long serialVersionUID = 8999619612996643502L;

    public ReentrantShortLock() {}

    public ReentrantShortLock(boolean fair) {
        super(fair);
    }

    @Override
    public void lock() {
        super.lock();
    }
}
