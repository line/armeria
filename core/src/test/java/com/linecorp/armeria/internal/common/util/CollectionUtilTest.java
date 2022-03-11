/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.internal.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

class CollectionUtilTest {

    @Test
    void testTruncate() {
        final ImmutableList<Integer> ints = ImmutableList.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        List<Integer> truncated = CollectionUtil.truncate(ints, 5);
        assertThat(truncated).containsExactly(1, 2, 3, 4, 5);
        truncated = CollectionUtil.truncate(ints, 10);
        assertThat(truncated).isSameAs(ints);
    }
}
