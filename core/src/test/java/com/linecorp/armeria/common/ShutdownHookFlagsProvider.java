/*
 * Copyright 2026 LINE Corporation
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

import com.linecorp.armeria.client.ClientFactory;

public final class ShutdownHookFlagsProvider implements FlagsProvider {

    public static final String ENABLE_PROPERTY = "com.linecorp.armeria.testShutdownHookFlagsProvider";

    @Override
    public int priority() {
        return Integer.MAX_VALUE;
    }

    @Override
    public ClientFactory defaultClientFactory() {
        if (!Boolean.getBoolean(ENABLE_PROPERTY)) {
            return null;
        }
        return new ShutdownHookTestClientFactory();
    }
}
