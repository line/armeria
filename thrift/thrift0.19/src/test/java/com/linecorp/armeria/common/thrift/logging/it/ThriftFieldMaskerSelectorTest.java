/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.armeria.common.thrift.logging.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.apache.thrift.meta_data.FieldMetaData;
import org.apache.thrift.meta_data.FieldValueMetaData;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.logging.FieldMasker;
import com.linecorp.armeria.common.thrift.logging.ThriftFieldInfo;
import com.linecorp.armeria.common.thrift.logging.ThriftFieldMaskerSelector;

class ThriftFieldMaskerSelectorTest {

    static class TestThriftFieldInfo implements ThriftFieldInfo {

        private final FieldMetaData fieldMetaData;

        TestThriftFieldInfo(Map<String, String> annotations) {
            fieldMetaData = new FieldMetaData("test", (byte) 0, new FieldValueMetaData((byte) 0),
                                              annotations);
        }

        @Override
        public FieldMetaData fieldMetaData() {
            return fieldMetaData;
        }
    }

    @Test
    void noRules() {
        final ThriftFieldMaskerSelector selector = ThriftFieldMaskerSelector.builder().build();
        final FieldMasker fieldMasker = selector.fieldMasker(new TestThriftFieldInfo(ImmutableMap.of()));
        assertThat(fieldMasker).isSameAs(FieldMasker.fallthrough());
    }

    @Test
    void presenceRule() {
        final ThriftFieldMaskerSelector selector = ThriftFieldMaskerSelector
                .builder()
                .onFieldAnnotation("a", FieldMasker.nullify())
                .build();
        assertThat(selector.fieldMasker(new TestThriftFieldInfo(ImmutableMap.of())))
                .isSameAs(FieldMasker.fallthrough());
        assertThat(selector.fieldMasker(new TestThriftFieldInfo(ImmutableMap.of("a", "aval"))))
                .isSameAs(FieldMasker.nullify());
        assertThat(selector.fieldMasker(new TestThriftFieldInfo(ImmutableMap.of("b", "bval"))))
                .isSameAs(FieldMasker.fallthrough());
    }

    @Test
    void exactMatchRule() {
        final ThriftFieldMaskerSelector selector = ThriftFieldMaskerSelector
                .builder()
                .onFieldAnnotation("a", "a", FieldMasker.nullify())
                .build();
        assertThat(selector.fieldMasker(new TestThriftFieldInfo(ImmutableMap.of())))
                .isSameAs(FieldMasker.fallthrough());
        assertThat(selector.fieldMasker(new TestThriftFieldInfo(ImmutableMap.of("b", "a"))))
                .isSameAs(FieldMasker.fallthrough());
        assertThat(selector.fieldMasker(new TestThriftFieldInfo(ImmutableMap.of("a", "a"))))
                .isSameAs(FieldMasker.nullify());
        assertThat(selector.fieldMasker(new TestThriftFieldInfo(ImmutableMap.of("a", "b"))))
                .isSameAs(FieldMasker.fallthrough());
    }

    @Test
    void addPrecedence() {
        final FieldMasker exactFirstMasker = ThriftFieldMaskerSelector
                .builder()
                .onFieldAnnotation("a", "a", FieldMasker.nullify())
                .onFieldAnnotation("a", "a", FieldMasker.fallthrough())
                .onFieldAnnotation("a", FieldMasker.fallthrough())
                .build().fieldMasker(new TestThriftFieldInfo(ImmutableMap.of("a", "a")));
        assertThat(exactFirstMasker).isSameAs(FieldMasker.nullify());

        final FieldMasker presenceFirstMasker = ThriftFieldMaskerSelector
                .builder()
                .onFieldAnnotation("a", FieldMasker.nullify())
                .onFieldAnnotation("a", FieldMasker.fallthrough())
                .onFieldAnnotation("a", "a", FieldMasker.fallthrough())
                .onFieldAnnotation("a", "a", FieldMasker.fallthrough())
                .build().fieldMasker(new TestThriftFieldInfo(ImmutableMap.of("a", "a")));
        assertThat(presenceFirstMasker).isSameAs(FieldMasker.nullify());
    }
}
