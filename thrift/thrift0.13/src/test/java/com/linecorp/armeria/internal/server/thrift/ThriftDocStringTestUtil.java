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

package com.linecorp.armeria.internal.server.thrift;

import static org.junit.Assume.assumeTrue;

/**
 * Utility methods for Thrift doc string tests.
 */
final class ThriftDocStringTestUtil {

    /**
     * Skips the current test if Thrift JSON metadata is not available.
     * Thrift JSON generation is disabled for thrift0.9, so tests that depend on
     * docstrings extracted from JSON files should call this method.
     */
    static void assumeThriftJsonEnabled() {
        assumeTrue("Thrift JSON metadata is not available (JSON generation may be disabled)",
                   ThriftDocStringTestUtil.class.getClassLoader()
                                                .getResource("META-INF/armeria/thrift/main.json") != null);
    }

    private ThriftDocStringTestUtil() {}
}
