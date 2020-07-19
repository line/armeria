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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;

class MultiOnCompleteResumeWithTest {

    // Forked from https://github.com/oracle/helidon/blob/0325cae20e68664da0f518ea2d803b9dd211a7b5/common/reactive/src/test/java/io/helidon/common/reactive/MultiOnCompleteResumeWithTest.java

    @Test
    void nullFallbackPublisher() {
        final TestSubscriber<Integer> ts = new TestSubscriber<>();

        assertThatThrownBy(() -> Multi.<Integer>empty()
                .onCompleteResumeWith(null)
                .subscribe(ts))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void fallbackToError() {
        final TestSubscriber<Integer> ts = new TestSubscriber<>();

        Multi.<Integer>empty()
                .onCompleteResumeWith(Multi.error(new IllegalArgumentException()))
                .subscribe(ts);

        ts.assertFailure(IllegalArgumentException.class);
    }

    @Test
    void errorSource() {
        final TestSubscriber<Integer> ts = new TestSubscriber<>();

        Multi.<Integer>error(new IOException())
                .onCompleteResume(666)
                .subscribe(ts);

        ts.assertItemCount(0);
        ts.assertError(IOException.class);
    }

    @Test
    void emptyFallback() {
        final TestSubscriber<Integer> ts = new TestSubscriber<>();

        Multi.<Integer>empty()
                .onCompleteResumeWith(Multi.empty())
                .subscribe(ts);

        ts.assertResult();
    }

    @Test
    void appendAfterItems() {
        final TestSubscriber<Integer> ts = new TestSubscriber<>(Long.MAX_VALUE);

        Multi.concat(Flux.range(1, 3), Multi.empty())
                .onCompleteResumeWith(Flux.range(4, 2))
                .subscribe(ts);

        ts.assertResult(1, 2, 3, 4, 5);
    }

    @Test
    void appendAfterItemsBackpressure() {
        final TestSubscriber<Integer> ts = new TestSubscriber<>();

        Multi.concat(Flux.range(1, 3), Multi.empty())
                .onCompleteResumeWith(Flux.range(4, 2))
                .subscribe(ts);

        ts.assertEmpty()
                .request(3)
                .assertValuesOnly(1, 2, 3)
                .request(2)
                .assertResult(1, 2, 3, 4, 5);
    }

    @Test
    void multipleAppendAfterItemsBackpressure() {
        final TestSubscriber<Integer> ts = new TestSubscriber<>();

        Multi.concat(Multi.empty(), Multi.just(1, 2, 3))
                .onCompleteResumeWith(Multi.just(4, 5))
                .onCompleteResumeWith(Multi.just(6, 7))
                .subscribe(ts);

        ts.assertEmpty()
                .request(3)
                .assertValuesOnly(1, 2, 3)
                .request(2)
                .assertValuesOnly(1, 2, 3, 4, 5)
                .request(1)
                .assertValuesOnly(1, 2, 3, 4, 5, 6)
                .request(1)
                .assertResult(1, 2, 3, 4, 5, 6, 7);
    }

    @Test
    void appendChain() {
        final TestSubscriber<Integer> ts = new TestSubscriber<>();

        Multi.just(1, 2, 3)
                .onCompleteResume(4)
                .onCompleteResume(5)
                .onCompleteResumeWith(Multi.just(6, 7))
                .onCompleteResume(8)
                .subscribe(ts);

        ts.assertEmpty()
                .requestMax()
                .assertComplete()
                .assertResult(1, 2, 3, 4, 5, 6, 7, 8);
    }
}
