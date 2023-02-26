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

package com.linecorp.armeria.common.util;

/**
 * Provides an executor interface which
 * is used for recognizing the status of the count of the queued tasks meets the given limit.
 *
 * <p>To throttle using the limit, please refer {@code BlockingTaskLimitingThrottlingStrategy}.
 */
public interface LimitedBlockingTaskExecutor extends BlockingTaskExecutor {

    /**
     * Wraps {@link BlockingTaskExecutor} with fixed limit.
     *
     * @param blockingTaskExecutor The executor which executes blocking tasks
     * @param limit Fixed threshold of the count of the queued tasks.
     */
    static LimitedBlockingTaskExecutor wrap(BlockingTaskExecutor blockingTaskExecutor, int limit) {
        return new DefaultLimitedBlockingTaskExecutor(blockingTaskExecutor, SettableIntSupplier.of(limit));
    }

    /**
     * Wraps {@link BlockingTaskExecutor} with dynamic limit using {@link SettableIntSupplier}.
     *
     * @param blockingTaskExecutor The executor which executes blocking tasks
     * @param limitSupplier Dynamic threshold of the count of the queued tasks.
     */
    static LimitedBlockingTaskExecutor wrap(BlockingTaskExecutor blockingTaskExecutor,
                                            SettableIntSupplier limitSupplier) {
        return new DefaultLimitedBlockingTaskExecutor(blockingTaskExecutor, limitSupplier);
    }

    /**
     * Returns whether the count of the tasks hits the given limit or not.
     */
    boolean hitLimit();
}
