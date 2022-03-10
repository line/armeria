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

final class DnsQuestionContext {

    private final long queryTimeoutMillis;
    private final boolean isRefreshing;
    private final long refreshIntervalMillis;
    private final CompletableFuture<Void> whenCancelled = new CompletableFuture<>();

    DnsQuestionContext(EventExecutor executor, long queryTimeoutMillis,
                       boolean isRefreshing, long refreshIntervalMillis) {
        this.queryTimeoutMillis = queryTimeoutMillis;
        this.isRefreshing = isRefreshing;
        this.refreshIntervalMillis = refreshIntervalMillis;
        executor.schedule(() -> whenCancelled.cancel(true), queryTimeoutMillis, TimeUnit.MILLISECONDS);
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

    boolean isRefreshing() {
        return isRefreshing;
    }

    long refreshIntervalMillis() {
        return refreshIntervalMillis;
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
        return queryTimeoutMillis == that.queryTimeoutMillis && isRefreshing == that.isRefreshing &&
               refreshIntervalMillis == that.refreshIntervalMillis &&
               whenCancelled.equals(that.whenCancelled);
    }

    @Override
    public int hashCode() {
        int result = whenCancelled.hashCode();
        result = 31 * result + (int) queryTimeoutMillis;
        result = 31 * result + (isRefreshing ? 1 : 0);
        result = 31 * result + (int) refreshIntervalMillis;
        return result;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("queryTimeoutMillis", queryTimeoutMillis)
                          .add("isRefreshing", isRefreshing)
                          .add("refreshIntervalMillis", refreshIntervalMillis)
                          .add("whenCancelled", whenCancelled)
                          .toString();
    }
}
