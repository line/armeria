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

package com.linecorp.armeria.common.file;

import java.nio.file.Path;
import java.nio.file.WatchEvent.Kind;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A synthetic event kind that is triggered after registration.
 * There is no guarantee that this event is the first event triggered.
 * i.e. a different event may be triggered before the initial event.
 */
@UnstableApi
public final class WatcherRegisteredKind implements Kind<Path> {

    private static final WatcherRegisteredKind INSTANCE = new WatcherRegisteredKind();

    /**
     * A singleton instance of {@link WatcherRegisteredKind}.
     */
    public static WatcherRegisteredKind of() {
        return INSTANCE;
    }

    private WatcherRegisteredKind() {}

    @Override
    public String name() {
        return "WATCHER_REGISTERED";
    }

    @Override
    public Class<Path> type() {
        return Path.class;
    }
}
