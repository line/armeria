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

package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.Test;

import com.linecorp.armeria.common.util.Exceptions;

public class RpcResponseTest {

    private static final Object RESULT = new Object();
    private static final Throwable CAUSE = Exceptions.clearTrace(new Throwable());

    @Test
    public void successfulFrom() {
        final CompletableFuture<Object> future = new CompletableFuture<>();
        final RpcResponse res = RpcResponse.from(future);
        assertThat(res.isDone()).isFalse();
        future.complete(RESULT);
        assertThat(res.isDone()).isTrue();
        assertThat(res.join()).isSameAs(RESULT);
    }

    @Test
    public void failedFrom() {
        final CompletableFuture<Object> future = new CompletableFuture<>();
        final RpcResponse res = RpcResponse.from(future);
        assertThat(res.isDone()).isFalse();
        future.completeExceptionally(CAUSE);
        assertThat(res.isDone()).isTrue();
        assertThatThrownBy(res::join).isInstanceOf(CompletionException.class).hasCause(CAUSE);
    }
}
