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

package com.linecorp.armeria.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

import io.netty.util.concurrent.Promise;

class UnmodifiableFutureTest {

    @RegisterExtension
    static final EventLoopExtension eventLoop = new EventLoopExtension();

    @Test
    void shouldPropagateSuccess() {
        final Promise<String> promise = eventLoop.get().newPromise();
        final CompletableFuture<String> future = UnmodifiableFuture.fromNetty(promise);
        assertThat(future.isDone()).isFalse();
        promise.setSuccess("foo");
        assertThat(future.join()).isEqualTo("foo");
    }

    @Test
    void shouldPropagateFailure() {
        final Promise<String> promise = eventLoop.get().newPromise();
        final CompletableFuture<String> future = UnmodifiableFuture.fromNetty(promise);
        assertThat(future.isDone()).isFalse();
        final AnticipatedException cause = new AnticipatedException();
        promise.setFailure(cause);
        assertThatThrownBy(future::join)
                .isInstanceOf(CompletionException.class)
                .hasCause(cause);
    }
}
