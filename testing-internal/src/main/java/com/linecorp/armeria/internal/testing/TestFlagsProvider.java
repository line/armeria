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

package com.linecorp.armeria.internal.testing;

import com.linecorp.armeria.common.FlagsProvider;

public final class TestFlagsProvider implements FlagsProvider {
    @Override
    public int priority() {
        return 0;
    }

    @Override
    public Long defaultClientFactoryGracefulShutdownTimeoutMillis() {
        // Disable the graceful shutdown timeout for rapid iterative testing.
        // It's not recommended to disable the graceful shutdown timeout in production.
        return 0L;
    }
}
