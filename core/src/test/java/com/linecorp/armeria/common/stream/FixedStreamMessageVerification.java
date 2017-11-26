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

import java.util.stream.LongStream;

import org.testng.annotations.Test;

public class FixedStreamMessageVerification extends StreamMessageVerification<Long> {
    @Override
    public StreamMessage<Long> createPublisher(long elements) {
        return FixedStreamMessage.of(LongStream.range(0, elements).boxed().toArray(Long[]::new));
    }

    // A fixed stream cannot fail. It can be aborted, but verification does not support abortion on a closed
    // stream, while fixed streams are always closed.

    @Override
    public StreamMessage<Long> createFailedPublisher() {
        return null;
    }

    @Override
    public StreamMessage<Long> createAbortedPublisher(long elements) {
        return null;
    }

    @Override
    @Test(enabled = false)
    public void required_spec317_mustNotSignalOnErrorWhenPendingAboveLongMaxValue() throws Throwable {
        // Publishes Integer.MAX_VALUE values, which is not feasible with a FixedStreamMessage where the values
        // are all pre-allocated.
        notVerified();
    }
}
