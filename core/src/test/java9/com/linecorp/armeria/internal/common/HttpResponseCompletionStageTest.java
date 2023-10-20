/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.internal.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.stream.StreamMessage;

class HttpResponseCompletionStageTest {

    @Test
    void shouldCompleteHttpResponseWithCompletionStage() {
        final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        final HttpResponse response = HttpResponse.of(future.minimalCompletionStage());
        future.complete(HttpResponse.of(200));
        assertThat(response.aggregate().join().status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void shouldCompleteStreamMessageWithCompletionStage() {
        final CompletableFuture<StreamMessage<Integer>> future = new CompletableFuture<>();
        final StreamMessage<Integer> streamMessage = StreamMessage.of(future.minimalCompletionStage());
        future.complete(StreamMessage.of(1));
        assertThat(streamMessage.collect().join()).containsExactly(1);
    }
}
