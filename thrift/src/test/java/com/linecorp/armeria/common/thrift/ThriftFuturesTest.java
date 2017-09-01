/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.common.thrift;

import static com.linecorp.armeria.common.thrift.ThriftFutures.failedCompletedFuture;
import static com.linecorp.armeria.common.thrift.ThriftFutures.failedListenableFuture;
import static com.linecorp.armeria.common.thrift.ThriftFutures.successfulCompletedFuture;
import static com.linecorp.armeria.common.thrift.ThriftFutures.successfulListenableFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.Assume.assumeFalse;

import org.junit.Test;

import com.google.common.util.concurrent.ListenableFuture;

public class ThriftFuturesTest {

    @Test
    public void testSuccessfulCompletedFuture() throws Exception {
        ThriftCompletableFuture<String> future = successfulCompletedFuture("success");
        assertThat(future.get()).isEqualTo("success");
    }

    @Test
    public void testFailedCompletedFuture() throws Exception {
        ThriftCompletableFuture<String> future = failedCompletedFuture(new IllegalStateException());
        assertThat(catchThrowable(future::get)).hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    public void testSuccessfulListenableFuture() throws Exception {
        assumeUnshadedGuava();
        ThriftListenableFuture<String> future = successfulListenableFuture("success");
        assertThat(future.get()).isEqualTo("success");
    }

    @Test
    public void testFailedListenableFuture() throws Exception {
        assumeUnshadedGuava();
        ThriftListenableFuture<String> future = failedListenableFuture(new IllegalStateException());
        assertThat(catchThrowable(future::get)).hasCauseInstanceOf(IllegalStateException.class);
    }

    private static void assumeUnshadedGuava() {
        assumeFalse("Can't run tests related with ListenableFuture when Guava is shaded.",
                    ListenableFuture.class.getName().startsWith("com.linecorp.armeria.internal.shaded."));
    }
}
