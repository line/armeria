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

package com.linecorp.armeria.internal.testing;

import com.linecorp.armeria.common.FlagsProvider;

public final class TestTimeoutFlagsProvider implements FlagsProvider {

    @Override
    public int priority() {
        if (TimeoutDebugUtil.isTimeoutDisabled()) {
            return 1;
        }
        return Integer.MIN_VALUE;
    }

    @Override
    public Long defaultRequestTimeoutMillis() {
        return 0L;
    }

    @Override
    public Long defaultResponseTimeoutMillis() {
        return 0L;
    }

    @Override
    public Long defaultConnectTimeoutMillis() {
        return 0L;
    }

    @Override
    public Long defaultWriteTimeoutMillis() {
        return 0L;
    }

    @Override
    public Long defaultServerIdleTimeoutMillis() {
        return 0L;
    }

    @Override
    public Long defaultClientIdleTimeoutMillis() {
        return 0L;
    }
}
