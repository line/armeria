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

package com.linecorp.armeria.common.stream;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import reactor.test.StepVerifier;

public class ParallelAsyncMapStreamMessageTest {
    @Test
    void mapAsyncParallelPublishesEagerly() {
        final StreamMessage<Integer> streamMessage = StreamMessage.of(0, 1, 2);
        final CompletableFuture<Integer> finishFirst = CompletableFuture.completedFuture(2);
        final CompletableFuture<Integer> finishLast = new CompletableFuture<>();
        final CompletableFuture<Integer> finishSecond = new CompletableFuture<>();

        final List<CompletableFuture<Integer>> futures = ImmutableList.of(finishLast, finishSecond,
                                                                          finishFirst);
        final StreamMessage<Integer> shouldPreserveOrder = streamMessage.mapAsyncParallel(futures::get);

        StepVerifier.create(shouldPreserveOrder)
                    .expectNext(2)
                    .then(() -> finishSecond.complete(3))
                    .then(() -> finishLast.complete(1))
                    .expectNext(3, 1)
                    .verifyComplete();
    }
}
