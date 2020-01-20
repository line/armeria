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
package com.linecorp.armeria.common.thrift;

import static com.linecorp.armeria.common.thrift.ThriftListenableFuture.completedFuture;
import static com.linecorp.armeria.common.thrift.ThriftListenableFuture.exceptionallyCompletedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import org.junit.jupiter.api.Test;

import com.google.common.util.concurrent.ListenableFuture;

class ThriftListenableFutureTest {
    @Test
    void testSuccessfulListenableFuture() throws Exception {
        assumeUnshadedGuava();
        final ThriftListenableFuture<String> future = completedFuture("success");
        assertThat(future.get()).isEqualTo("success");
    }

    @Test
    void testFailedListenableFuture() throws Exception {
        assumeUnshadedGuava();
        final ThriftListenableFuture<String> future = exceptionallyCompletedFuture(new IllegalStateException());
        assertThatThrownBy(future::get).hasCauseInstanceOf(IllegalStateException.class);
    }

    private static void assumeUnshadedGuava() {
        assumeThat(ListenableFuture.class.getName())
                .withFailMessage("Can't run tests related with ListenableFuture when Guava is shaded.")
                .doesNotContain(".shaded.");
    }
}
