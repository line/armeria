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
/*
 * Copyright 2017 Pivotal Software, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linecorp.armeria.common.metric;

import java.util.concurrent.Executor;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

/**
 * An {@link Executor} that is timed.
 */
class TimedExecutor implements Executor {

    // Forked from Micrometer 1.3.6
    // https://github.com/micrometer-metrics/micrometer/blob/e6ff3c2fe9542608a33a62b10fdf1222cd60feae/micrometer-core/src/main/java/io/micrometer/core/instrument/internal/TimedExecutor.java

    private final MeterRegistry registry;
    private final Executor delegate;
    private final Timer executionTimer;
    private final Timer idleTimer;

    /**
     * Creates a new instance.
     */
    TimedExecutor(MeterRegistry registry, Executor delegate,
                  String executorName, String metricPrefix, Iterable<Tag> tags) {
        this.registry = registry;
        this.delegate = delegate;
        final Tags finalTags = Tags.concat(tags, "name", executorName);
        executionTimer = registry.timer(metricPrefix + ".execution", finalTags);
        idleTimer = registry.timer(metricPrefix + ".idle", finalTags);
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(new TimedRunnable(registry, executionTimer, idleTimer, command));
    }
}
