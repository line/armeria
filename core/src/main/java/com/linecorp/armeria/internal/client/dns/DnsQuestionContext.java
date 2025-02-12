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

package com.linecorp.armeria.internal.client.dns;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.google.common.base.MoreObjects;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ScheduledFuture;

final class DnsQuestionContext {

    private final long queryTimeoutMillis;
    private final CompletableFuture<Void> whenCancelled = new CompletableFuture<>();
    private final ScheduledFuture<?> scheduledFuture;
    private boolean complete;

    DnsQuestionContext(EventExecutor executor, long queryTimeoutMillis) {
        this.queryTimeoutMillis = queryTimeoutMillis;
        scheduledFuture = executor.schedule(() -> whenCancelled.cancel(true),
                                            queryTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    long queryTimeoutMillis() {
        return queryTimeoutMillis;
    }

    CompletableFuture<Void> whenCancelled() {
        return whenCancelled;
    }

    boolean isCancelled() {
        return whenCancelled.isCompletedExceptionally();
    }

    void cancelScheduler() {
        if (!scheduledFuture.isDone()) {
            scheduledFuture.cancel(false);
        }
    }

    void setComplete() {
        complete = true;
        cancelScheduler();
    }

    boolean isCompleted() {
        return complete;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DnsQuestionContext)) {
            return false;
        }

        final DnsQuestionContext that = (DnsQuestionContext) o;
        return queryTimeoutMillis == that.queryTimeoutMillis &&
               complete == that.complete &&
               whenCancelled.equals(that.whenCancelled) &&
               scheduledFuture.equals(that.scheduledFuture);
    }

    @Override
    public int hashCode() {
        int result = whenCancelled.hashCode();
        result = 31 * result + scheduledFuture.hashCode();
        result = 31 * result + (int) queryTimeoutMillis;
        result = 31 * result + (complete ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("queryTimeoutMillis", queryTimeoutMillis)
                          .add("whenCancelled", whenCancelled)
                          .add("scheduledFuture", scheduledFuture)
                          .toString();
    }
}
