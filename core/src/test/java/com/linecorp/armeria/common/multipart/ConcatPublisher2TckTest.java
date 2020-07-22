/*
 * Copyright 2020 LINE Corporation
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
/*
 * Copyright (c)  2020 Oracle and/or its affiliates. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.linecorp.armeria.common.multipart;

import java.util.stream.IntStream;

import org.reactivestreams.Publisher;
import org.reactivestreams.tck.PublisherVerification;
import org.reactivestreams.tck.TestEnvironment;
import org.testng.annotations.Test;

@Test
public class ConcatPublisher2TckTest extends PublisherVerification<Integer> {

    // Forked from https://github.com/oracle/helidon/blob/9d209a1a55f927e60e15b061700384e438ab5a01/common/reactive/src/test/java/io/helidon/common/reactive/ConcatPublisher2TckTest.java

    public ConcatPublisher2TckTest() {
        super(new TestEnvironment(200));
    }

    @Override
    public Publisher<Integer> createPublisher(long n) {
        final int firstHalf = (int) Math.min(n, 1); // trigger the switch to the 2nd source early
        return new ConcatPublisher<>(
                Multi.from(() -> IntStream.range(0, firstHalf).boxed().iterator()),
                Multi.from(() -> IntStream.range(firstHalf, (int) n).boxed().iterator())
        );
    }

    @Override
    public Publisher<Integer> createFailedPublisher() {
        return null;
    }

    @Override
    public long maxElementsFromPublisher() {
        return 10;
    }
}
