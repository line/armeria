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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.Iterator;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

class MultiFromIterableTest {

    // Forked from https://github.com/oracle/helidon/blob/9d209a1a55f927e60e15b061700384e438ab5a01/common/reactive/src/test/java/io/helidon/common/reactive/MultiFromIterableTest.java

    @Test
    void emptyIterable() {
        final TestSubscriber<Object> ts = new TestSubscriber<>();

        Multi.from(ImmutableList.of())
             .subscribe(ts);

        assertThat(ts.getItems()).isEmpty();
        assertThat(ts.isComplete()).isTrue();
        assertThat(ts.getLastError()).isNull();
    }

    @Test
    void singletonIterableUnboundedRequest() {
        final TestSubscriber<Integer> ts = new TestSubscriber<>();

        Multi.from(ImmutableList.of(1))
             .subscribe(ts);

        assertThat(ts.getItems()).isEmpty();
        assertThat(ts.isComplete()).isFalse();
        assertThat(ts.getLastError()).isNull();
        assertThat(ts.getSubscription()).isNotNull();

        ts.requestMax();

        assertThat(ts.getItems()).containsExactly(1);
        assertThat(ts.isComplete()).isTrue();
        assertThat(ts.getLastError()).isNull();
    }

    @Test
    void singletonIterableBoundedRequest() {
        final TestSubscriber<Integer> ts = new TestSubscriber<>();

        Multi.from(ImmutableList.of(1))
             .subscribe(ts);

        assertThat(ts.getItems()).isEmpty();
        assertThat(ts.isComplete()).isFalse();
        assertThat(ts.getLastError()).isNull();
        assertThat(ts.getSubscription()).isNotNull();

        ts.request1();

        assertThat(ts.getItems()).containsExactly(1);
        assertThat(ts.isComplete()).isTrue();
        assertThat(ts.getLastError()).isNull();
    }

    @Test
    void iteratorNull() {
        final TestSubscriber<Integer> ts = new TestSubscriber<>();

        Multi.from(Collections.singleton((Integer) null))
             .subscribe(ts);

        ts.request1();

        assertThat(ts.getItems()).isEmpty();
        assertThat(ts.isComplete()).isFalse();
        assertThat(ts.getLastError()).isInstanceOf(NullPointerException.class);
    }

    @Test
    void iteratorNextCrash() {
        final TestSubscriber<Integer> ts = new TestSubscriber<>();

        Multi.from(() -> new Iterator<Integer>() {

            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public Integer next() {
                throw new IllegalArgumentException();
            }
        }).subscribe(ts);

        ts.request1();

        assertThat(ts.getItems()).isEmpty();
        assertThat(ts.isComplete()).isFalse();
        assertThat(ts.getLastError()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void iteratorNextCancel() {
        final TestSubscriber<Integer> ts = new TestSubscriber<>();

        Multi.from(() -> new Iterator<Integer>() {

            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public Integer next() {
                ts.getSubscription().cancel();
                return 1;
            }
        }).subscribe(ts);

        ts.request1();

        assertThat(ts.getItems()).isEmpty();
        assertThat(ts.isComplete()).isFalse();
        assertThat(ts.getLastError()).isNull();
    }

    @Test
    void iteratorHasNextCrash2ndCall() {
        final TestSubscriber<Integer> ts = new TestSubscriber<>();

        Multi.from(() -> new Iterator<Integer>() {
            int calls;

            @Override
            public boolean hasNext() {
                if (++calls == 2) {
                    throw new IllegalArgumentException();
                }
                return true;
            }

            @Override
            public Integer next() {
                return 1;
            }
        }).subscribe(ts);

        ts.request1();

        assertThat(ts.getItems()).containsExactly(1);
        assertThat(ts.isComplete()).isFalse();
        assertThat(ts.getLastError()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void iteratorHasNextCancel2ndCall() {
        final TestSubscriber<Integer> ts = new TestSubscriber<>();

        Multi.from(() -> new Iterator<Integer>() {
            int calls;

            @Override
            public boolean hasNext() {
                if (++calls == 2) {
                    ts.getSubscription().cancel();
                }
                return true;
            }

            @Override
            public Integer next() {
                return 1;
            }
        }).subscribe(ts);

        ts.request1();

        assertThat(ts.getItems()).containsExactly(1);
        assertThat(ts.isComplete()).isFalse();
        assertThat(ts.getLastError()).isNull();
    }

    @Test
    void cancelInOnNext() {
        final TestSubscriber<Integer> ts = new TestSubscriber<Integer>() {
            @Override
            public void onNext(Integer item) {
                super.onNext(item);
                getSubscription().cancel();
                onComplete();
            }
        };

        Multi.from(ImmutableList.of(1))
             .subscribe(ts);

        ts.requestMax();
        assertThat(ts.getItems()).containsExactly(1);
        assertThat(ts.isComplete()).isTrue();
        assertThat(ts.getLastError()).isNull();
    }
}
