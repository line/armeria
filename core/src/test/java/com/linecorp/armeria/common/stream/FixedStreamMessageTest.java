/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.common.stream;

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.util.Collection;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.google.common.collect.ImmutableList;

@RunWith(Parameterized.class)
public class FixedStreamMessageTest extends AbstractStreamMessageTest {

    @Parameters(name = "{index}: num={0}")
    public static Collection<Integer> parameters() {
        return ImmutableList.of(0, 1, 2, 10);
    }

    private final int num;

    public FixedStreamMessageTest(int num) {
        this.num = num;
    }

    @Override
    @SuppressWarnings({ "unchecked", "SuspiciousArrayCast" })
    <T> StreamMessage<T> newStream(List<T> inputs) {
        return StreamMessage.of((T[]) inputs.toArray());
    }

    @Override
    List<Integer> streamValues() {
        return IntStream.range(0, num).boxed().collect(toImmutableList());
    }
}
