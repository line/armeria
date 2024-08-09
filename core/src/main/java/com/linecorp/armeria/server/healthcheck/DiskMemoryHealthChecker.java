/*
 * Copyright 2024 LY Corporation
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
package com.linecorp.armeria.server.healthcheck;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.io.File;

/**
 * A {@link HealthChecker} that reports as unhealthy
 * when the target free disk space exceeds a file disk usable space.
 * For example:
 * <pre>{@code
 * final DiskMemoryHealthChecker diskMemoryHealthChecker = HealthChecker.ofDisk(0.8, new File("/tmp"));
 *
 * // Returns false if a file disk usable memory space is less than 80%,
 * // or true if a file disk usable memory space is greater than or equal to 80%.
 * final boolean healthy = diskMemoryHealthChecker.isHealthy();
 * }</pre>
 */
// Forked from <a href="https://github.com/micrometer-metrics/micrometer/blob/8339d57bef8689beb8d7a18b429a166f6595f2af/micrometer-core/src/main/java/io/micrometer/core/instrument/binder/system/DiskSpaceMetrics.java">DiskSpaceMetrics.java</a> in the micrometer core.
final class DiskMemoryHealthChecker implements HealthChecker {

    private final double freeDiskSpacePercentage;

    private final File path;

    DiskMemoryHealthChecker(double freeDiskSpacePercentage, File path) {
        checkArgument(freeDiskSpacePercentage >= 0 && freeDiskSpacePercentage <= 1,
                      "freeDiskSpacePercentage: %s (expected >= 0 and expected <= 1)", freeDiskSpacePercentage);
        requireNonNull(path);
        this.freeDiskSpacePercentage = freeDiskSpacePercentage;
        this.path = path;
    }

    /**
     * Returns true if the file usable space is greater or equal than the target space.
     * @return boolean
     */
    @Override
    public boolean isHealthy() {
        return freeDiskSpacePercentage * path.getTotalSpace() <= path.getUsableSpace();
    }
}
