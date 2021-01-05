/*
 * Copyright 2021 LINE Corporation
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

import org.reactivestreams.Publisher;
import org.reactivestreams.tck.PublisherVerification;
import org.reactivestreams.tck.TestEnvironment;
import org.testng.annotations.Test;

@Test
public class ConcatArrayStreamMessageTck2Test extends PublisherVerification<Integer> {

    // Forked from https://github.com/oracle/helidon/blob/28cb3e8a34bda691c035d21f90b6278c6a42007c/common/reactive/src/test/java/io/helidon/common/reactive/MultiConcatArrayTck2Test.java

    public ConcatArrayStreamMessageTck2Test() {
        super(new TestEnvironment(200));
    }

    @Override
    public Publisher<Integer> createPublisher(long l) {
        if (l == 0) {
            return StreamMessage.of();
        }
        @SuppressWarnings("unchecked")
        final StreamMessage<Integer>[] sources = new StreamMessage[(int)l];
        for (int i = 0; i < l; i++) {
            sources[i] = StreamMessage.of(i);
        }
        return StreamMessage.of(sources);
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
