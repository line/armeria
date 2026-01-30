/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.armeria.common.file;

import java.nio.file.Path;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.Cancellable;

final class WatchKey implements Cancellable {

    private final Path path;
    private final DirectoryWatchService registry;
    private final PathWatcher callback;

    WatchKey(Path path, DirectoryWatchService registry, PathWatcher callback) {
        this.path = path;
        this.registry = registry;
        this.callback = callback;
    }

    PathWatcher callback() {
        return callback;
    }

    Path path() {
        return path;
    }

    /**
     * Cancels the watch registration, stopping the callback from receiving further events.
     */
    @Override
    public void cancel() {
        registry.unregister(this);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("path", path)
                          .add("callback", callback)
                          .toString();
    }
}
