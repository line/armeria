/*
 * Copyright 2019 LINE Corporation
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

public final class TestUtil {

    private static final boolean isDocServiceDemoMode = "true".equals(System.getenv("DOC_SERVICE_DEMO"));

    /**
     * Indicates doc service tests should be run on fixed ports to be able to demo or develop DocService.
     */
    public static boolean isDocServiceDemoMode() {
        return isDocServiceDemoMode;
    }

    private TestUtil() {}
}
