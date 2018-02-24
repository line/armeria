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

package com.linecorp.armeria.server.thrift;

import java.io.IOException;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.Test;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;

/**
 * Tests {@link ThriftDocStringExtractor}.
 */
public class ThriftDocStringExtractorTest {

    private ThriftDocStringExtractor extractor = new ThriftDocStringExtractor();

    @Test
    public void testThriftTestJson() throws Exception {
        Map<String, String> docStrings = extractor.getDocStringsFromFiles(
                ImmutableMap.of(
                        "META-INF/armeria/thrift/ThriftTest.json",
                        Resources.toByteArray(Resources.getResource(
                                "META-INF/armeria/thrift/ThriftTest.json"))));
        Assertions.assertThat(docStrings.get("thrift.test.Numberz")).isEqualTo("Docstring!");
        Assertions.assertThat(docStrings.get("thrift.test.ThriftTest/testVoid")).isEqualTo(
                "Prints \"testVoid()\" and returns nothing.");
    }

    @Test
    public void testCassandraJson() throws Exception {
        Map<String, String> docStrings = extractor.getDocStringsFromFiles(
                ImmutableMap.of(
                        "META-INF/armeria/thrift/ThriftTest.json",
                        Resources.toByteArray(Resources.getResource(
                                "META-INF/armeria/thrift/cassandra.json"))));
        Assertions.assertThat(docStrings.get("com.linecorp.armeria.service.test.thrift.cassandra.Compression"))
                  .isEqualTo("CQL query compression");
        Assertions.assertThat(
                docStrings.get("com.linecorp.armeria.service.test.thrift.cassandra.CqlResultType")).isNull();
    }

    @Test
    public void testGetAllDocStrings() throws IOException {
        Map<String, String> docStrings = extractor.getAllDocStrings(getClass().getClassLoader());
        Assertions.assertThat(docStrings.containsKey("thrift.test.Numberz")).isTrue();
        Assertions.assertThat(
                docStrings.containsKey("com.linecorp.armeria.service.test.thrift.cassandra.Compression"))
                  .isTrue();
    }
}
