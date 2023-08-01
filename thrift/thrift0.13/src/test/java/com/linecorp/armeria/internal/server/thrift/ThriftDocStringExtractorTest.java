/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.internal.server.thrift;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;

/**
 * Tests {@link ThriftDocStringExtractor}.
 */
class ThriftDocStringExtractorTest {

    private final ThriftDocStringExtractor extractor = new ThriftDocStringExtractor();

    @Test
    void testThriftTestJson() throws Exception {
        final Map<String, String> docStrings = extractor.getDocStringsFromFiles(
                ImmutableMap.of(
                        "META-INF/armeria/thrift/ThriftTest.json",
                        Resources.toByteArray(Resources.getResource(
                                "META-INF/armeria/thrift/ThriftTest.json"))));
        assertThat(docStrings.get("thrift.test.Numberz")).isEqualTo("Docstring!");
        assertThat(docStrings.get("thrift.test.ThriftTest/testVoid")).isEqualTo(
                "Prints \"testVoid()\" and returns nothing.");
    }

    @Test
    void testCassandraJson() throws Exception {
        final Map<String, String> docStrings = extractor.getDocStringsFromFiles(
                ImmutableMap.of(
                        "META-INF/armeria/thrift/ThriftTest.json",
                        Resources.toByteArray(Resources.getResource(
                                "META-INF/armeria/thrift/cassandra.json"))));
        assertThat(docStrings.get("testing.thrift.cassandra.Compression"))
                  .isEqualTo("CQL query compression");
        assertThat(
                docStrings.get("testing.thrift.cassandra.CqlResultType")).isNull();
    }

    @Test
    void testGetAllDocStrings() throws IOException {
        final Map<String, String> docStrings = extractor.getAllDocStrings(getClass().getClassLoader());
        assertThat(docStrings.containsKey("thrift.test.Numberz")).isTrue();
        assertThat(
                docStrings.containsKey("testing.thrift.cassandra.Compression"))
                  .isTrue();
    }
}
