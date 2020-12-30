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

import org.reactivestreams.Publisher;
import org.reactivestreams.tck.PublisherVerification;
import org.reactivestreams.tck.TestEnvironment;
import org.testng.annotations.Test;

import com.linecorp.armeria.common.stream.PublisherBasedStreamMessage;
import com.linecorp.armeria.common.stream.StreamMessage;

import reactor.core.publisher.Flux;

@Test
public class ConcatArrayStreamMessageTck1Test extends PublisherVerification<Integer> {

    // Forked from https://github.com/oracle/helidon/blob/28cb3e8a34bda691c035d21f90b6278c6a42007c/common
    // /reactive/src/test/java/io/helidon/common/reactive/MultiConcatArrayTck1Test.java

    public ConcatArrayStreamMessageTck1Test() {
        super(new TestEnvironment(200));
    }

    @Override
    public Publisher<Integer> createPublisher(long l) {
        @SuppressWarnings("unchecked")
        final StreamMessage<Integer>[] streamMessages = new StreamMessage[2];
        streamMessages[0] = new PublisherBasedStreamMessage<>(Flux.range(0, (int) l / 2));
        streamMessages[1] = new PublisherBasedStreamMessage<>(Flux.range((int) l / 2, (int) (l - l / 2)));
        return new ConcatArrayStreamMessage<>(streamMessages);
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
