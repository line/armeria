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
 * Copyright (c) 2020 Oracle and/or its affiliates. All rights reserved.
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
 */
package com.linecorp.armeria.common.multipart;

import java.io.IOException;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class MultiConcatArrayTest {

    // Forked from https://github.com/oracle/helidon/blob/28cb3e8a34bda691c035d21f90b6278c6a42007c/common/reactive/src/test/java/io/helidon/common/reactive/MultiConcatArrayTest.java

    @Test
    void errors() {
        final TestSubscriber<Object> ts = new TestSubscriber<>(Long.MAX_VALUE);

        Multi.concatArray(Multi.singleton(1), Multi.error(new IOException()), Multi.singleton(2))
             .subscribe(ts);

        ts.assertFailure(IOException.class, 1);
    }

    @Test
    void millionSources() {
        @SuppressWarnings("unchecked")
        final Multi<Integer>[] sources = new Multi[1_000_000];
        Arrays.fill(sources, Multi.singleton(1));

        final TestSubscriber<Object> ts = new TestSubscriber<>(Long.MAX_VALUE);

        Multi.concatArray(sources)
             .subscribe(ts);

        ts.assertItemCount(1_000_000)
          .assertComplete();
    }
}
