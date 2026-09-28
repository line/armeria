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

package com.linecorp.armeria.internal.common.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.channel.EventLoop;

@Timeout(10)
class CallExecutorTest {

    @Test
    void eventLoopSubmissionWaitsForCurrentTaskAndQueuedWork() throws Exception {
        final EventLoop eventLoop = CommonPools.workerGroup().next();
        final CallExecutor executor = CallExecutor.of(eventLoop);
        final List<String> events = new ArrayList<>();
        final CompletableFuture<Void> complete = new CompletableFuture<>();
        eventLoop.submit(() -> {
            eventLoop.execute(() -> events.add("already queued"));
            executor.execute(() -> {
                events.add("call");
                complete.complete(null);
            });
            events.add("submitter returned");
        }).get(5, TimeUnit.SECONDS);
        complete.get(5, TimeUnit.SECONDS);
        assertThat(events).containsExactly("submitter returned", "already queued", "call");
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void reentrantTasksDoNotNestOrOvertakeQueuedTasks(boolean blocking) throws Exception {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final CallExecutor executor = newExecutor(ctx, blocking);
        final List<String> events = new ArrayList<>();
        final CompletableFuture<Void> complete = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            executor.execute(() -> {
                events.add("first");
                executor.execute(() -> {
                    events.add("reentrant");
                    complete.complete(null);
                });
                events.add("first returned");
            });
            executor.execute(() -> events.add("second"));
        }, executor).get(5, TimeUnit.SECONDS);
        complete.get(5, TimeUnit.SECONDS);
        assertThat(events).containsExactly("first", "first returned", "second", "reentrant");
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void delegatePropagatesContextAndTracksOnlyThisExecutor(boolean blocking) throws Exception {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final CallExecutor executor = newExecutor(ctx, blocking);
        final CallExecutor other = newExecutor(ctx, blocking);
        assertThat(executor.inExecutor()).isFalse();
        CompletableFuture.runAsync(() -> {
            assertThat(RequestContext.<ServiceRequestContext>current()).isSameAs(ctx);
            assertThat(executor.inExecutor()).isTrue();
            assertThat(other.inExecutor()).isFalse();
        }, executor).get(5, TimeUnit.SECONDS);
        assertThat(executor.inExecutor()).isFalse();
    }

    @Test
    void clearsExecutionStateAfterTaskFailure() throws Exception {
        final EventLoop eventLoop = CommonPools.workerGroup().next();
        final CallExecutor executor = CallExecutor.of(eventLoop);
        final RuntimeException failure = new IllegalStateException("task failure");
        executor.execute(() -> {
            throw failure;
        });
        eventLoop.submit(() -> assertThat(executor.inExecutor()).isFalse()).get(5, TimeUnit.SECONDS);
        CompletableFuture.runAsync(() -> assertThat(executor.inExecutor()).isTrue(), executor)
                         .get(5, TimeUnit.SECONDS);
    }

    @Test
    void rejectionDoesNotMarkExecutorActive() {
        final Executor rejectingExecutor = task -> {
            throw new RejectedExecutionException();
        };
        final CallExecutor executor = CallExecutor.sequential(rejectingExecutor);
        assertThatThrownBy(() -> executor.execute(() -> {
            throw new AssertionError("Rejected task must not run");
        })).isInstanceOf(RejectedExecutionException.class);
        assertThat(executor.inExecutor()).isFalse();
    }

    private static CallExecutor newExecutor(ServiceRequestContext ctx, boolean blocking) {
        return blocking ? CallExecutor.sequential(ctx.blockingTaskExecutor())
                        : CallExecutor.of(ctx.eventLoop());
    }
}
