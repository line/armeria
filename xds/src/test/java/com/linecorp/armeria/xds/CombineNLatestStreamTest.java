/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.xds;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;

@SuppressWarnings("CheckReturnValue")
class CombineNLatestStreamTest {

    @Test
    void combineNLatestPropagatesErrorAndResumes() {
        final TestStream<String> stream1 = new TestStream<>();
        final TestStream<String> stream2 = new TestStream<>();
        final SnapshotStream<List<String>> combined =
                SnapshotStream.combineNLatest(ImmutableList.of(stream1, stream2));

        final List<List<String>> values = new ArrayList<>();
        final List<Throwable> errors = new ArrayList<>();
        combined.subscribe((snapshot, error) -> {
            if (snapshot != null) {
                values.add(snapshot);
            }
            if (error != null) {
                errors.add(error);
            }
        });

        final RuntimeException error = new RuntimeException("boom");
        stream1.emit(null, error);
        assertThat(errors).containsExactly(error);

        stream2.emit("b", null);
        assertThat(values).isEmpty();

        stream1.emit("a", null);
        assertThat(values).containsExactly(ImmutableList.of("a", "b"));

        stream2.emit("b2", null);
        assertThat(values).containsExactly(
                ImmutableList.of("a", "b"),
                ImmutableList.of("a", "b2")
        );
    }

    static final class TestStream<T> extends RefCountedStream<T> {
        @Override
        protected Subscription onStart(SnapshotWatcher<T> watcher) {
            return Subscription.noop();
        }

        @Override
        public void emit(@Nullable T value, @Nullable Throwable error) {
            super.emit(value, error);
        }
    }
}
