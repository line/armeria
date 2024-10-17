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

package com.linecorp.armeria.common.metric;

import java.util.ArrayList;
import java.util.List;

import com.linecorp.armeria.internal.common.util.ReentrantShortLock;

abstract class AbstractCloseableMeterBinder implements CloseableMeterBinder {

    private final List<Runnable> closingTasks = new ArrayList<>();
    private final ReentrantShortLock lock = new ReentrantShortLock();

    protected final void addClosingTask(Runnable closingTask) {
        lock.lock();
        try {
            closingTasks.add(closingTask);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            for (Runnable task : closingTasks) {
                task.run();
            }
            closingTasks.clear();
        } finally {
            lock.unlock();
        }
    }
}
