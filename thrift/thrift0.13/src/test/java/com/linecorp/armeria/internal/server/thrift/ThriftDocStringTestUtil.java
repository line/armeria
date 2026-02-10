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

import java.util.Map;

/**
 * Utility methods for Thrift doc string tests.
 */
final class ThriftDocStringTestUtil {

    private static final ThriftDocStringExtractor extractor = new ThriftDocStringExtractor();

    /**
     * Skips the current test if Thrift docstrings are not available.
     * Thrift JSON generation is disabled for {@code thrift0.9} because Thrift 0.9 does not support the
     * JSON target. Additionally, older Thrift versions (0.9.x) do not include docstrings in the JSON output
     * even when generated. Tests that depend on docstrings extracted from JSON files should call this method.
     */
    static void assumeDocStringsAvailable() {
        final Map<String, String> docStrings = extractor.getAllDocStrings(
                ThriftDocStringTestUtil.class.getClassLoader());
        // Check for a known docstring that should exist if docstrings are properly generated.
        // HelloService.hello has "@return a greeting message" in main.thrift.
        assumeTrue("Thrift docstrings are not available (JSON generation may be disabled or " +
                   "Thrift version does not support docstrings in JSON output)",
                   docStrings.get("testing.thrift.main.HelloService/hello:return") != null);
    }

    private ThriftDocStringTestUtil() {}
}
