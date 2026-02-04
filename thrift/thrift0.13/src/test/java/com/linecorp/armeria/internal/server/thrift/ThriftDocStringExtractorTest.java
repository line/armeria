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

import static com.linecorp.armeria.internal.server.thrift.ThriftDocStringTestUtil.assumeDocStringsAvailable;
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
    void testReturnDocString() throws Exception {
        final Map<String, String> docStrings = extractor.getDocStringsFromFiles(
                ImmutableMap.of(
                        "META-INF/armeria/thrift/ThriftTest.json",
                        Resources.toByteArray(Resources.getResource(
                                "META-INF/armeria/thrift/ThriftTest.json"))));
        // Test that @return docstrings are extracted
        // The testString method has: @return string - returns the string 'thing'
        // Since return type is known from method definition, the entire content is captured
        assertThat(docStrings.get("thrift.test.ThriftTest/testString:return"))
                .isEqualTo("string - returns the string 'thing'");
        // The testStruct method has: @return Xtruct - returns the Xtruct 'thing'
        assertThat(docStrings.get("thrift.test.ThriftTest/testStruct:return"))
                .isEqualTo("Xtruct - returns the Xtruct 'thing'");
    }

    @Test
    void testThrowsDocString() throws Exception {
        final Map<String, String> docStrings = extractor.getDocStringsFromFiles(
                ImmutableMap.of(
                        "META-INF/armeria/thrift/ThriftTest.json",
                        Resources.toByteArray(Resources.getResource(
                                "META-INF/armeria/thrift/ThriftTest.json"))));
        // Test that @throws docstrings are extracted
        // The testException method has: @throws Xception - when arg is "Xception"
        assertThat(docStrings.get("thrift.test.ThriftTest/testException:throws/thrift.test.Xception"))
                .isEqualTo("when arg is \"Xception\"");

        // The testMultiException method has:
        // @throws Xception - when arg0 is "Xception"
        // @throws Xception2 - when arg0 is "Xception2"
        assertThat(docStrings.get("thrift.test.ThriftTest/testMultiException:throws/thrift.test.Xception"))
                .isEqualTo("when arg0 is \"Xception\"");
        assertThat(docStrings.get("thrift.test.ThriftTest/testMultiException:throws/thrift.test.Xception2"))
                .isEqualTo("when arg0 is \"Xception2\"");
    }

    @Test
    void testParamDocString() throws Exception {
        assumeDocStringsAvailable();
        final Map<String, String> docStrings = extractor.getDocStringsFromFiles(
                ImmutableMap.of(
                        "META-INF/armeria/thrift/hbase.json",
                        Resources.toByteArray(Resources.getResource(
                                "META-INF/armeria/thrift/hbase.json"))));
        // Test that parameter docstrings from Thrift IDL are extracted with :param/ format
        // The enableTable method has a tableName parameter with doc "name of the table"
        assertThat(docStrings.get("testing.thrift.hbase.Hbase/enableTable:param/tableName"))
                .isEqualTo("name of the table");
        // The disableTable method also has a tableName parameter with doc "name of the table"
        assertThat(docStrings.get("testing.thrift.hbase.Hbase/disableTable:param/tableName"))
                .isEqualTo("name of the table");
        // Verify the old incorrect format is not present
        assertThat(docStrings.containsKey("testing.thrift.hbase.Hbase/enableTable/tableName")).isFalse();
    }

    @Test
    void testGetAllDocStrings() throws IOException {
        final Map<String, String> docStrings = extractor.getAllDocStrings(getClass().getClassLoader());
        assertThat(docStrings.containsKey("thrift.test.Numberz")).isTrue();
    }

    @Test
    void testMissingThrowsDescription() throws IOException {
        assumeDocStringsAvailable();
        // Test that @throws with only type name (no description) does not create a :throws entry
        final Map<String, String> docStrings = extractor.getAllDocStrings(getClass().getClassLoader());
        // MidLineTagTestService.throwsTypeOnly has "@throws FooServiceException" without description
        assertThat(docStrings.get("testing.thrift.main.MidLineTagTestService/throwsTypeOnly"))
                .contains("@throws FooServiceException");
        // The :throws entry should not exist because there's no description
        final String throwsKey =
                "testing.thrift.main.MidLineTagTestService/throwsTypeOnly:throws/" +
                "testing.thrift.main.FooServiceException";
        assertThat(docStrings.containsKey(throwsKey)).isFalse();
    }

    @Test
    void testMidLineTagsIgnored() throws IOException {
        assumeDocStringsAvailable();
        // Test that @return and @throws appearing in the middle of a line are ignored
        final Map<String, String> docStrings = extractor.getAllDocStrings(getClass().getClassLoader());

        // The method has mid-line "@return" and "@throws FooServiceException" which should be ignored
        // But it also has a valid "@return valid return description" at line start
        final String methodDoc = docStrings.get(
                "testing.thrift.main.MidLineTagTestService/midLineTagsIgnored");
        assertThat(methodDoc).contains("mid-line @return should be ignored");
        assertThat(methodDoc).contains("mid-line @throws FooServiceException");

        // The :return entry should only capture the valid line-start @return tag
        assertThat(docStrings.get(
                "testing.thrift.main.MidLineTagTestService/midLineTagsIgnored:return"))
                .isEqualTo("valid return description");
    }
}
