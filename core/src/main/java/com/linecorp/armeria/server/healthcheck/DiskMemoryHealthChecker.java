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
 * when the target free disk space or total disk space exceed a file disk usable or total memory space.
 * For example:
 * <pre>{@code
 * final DiskMemoryHealthChecker diskMemoryHealthChecker = HealthChecker.ofDisk(100, 100, "/tmp");
 *
 * // Returns false if a file disk usable/total memory space is less than 100 bytes,
 * // or true if a file disk usable/total memory space is greater than or equal to 100 bytes.
 * final boolean healthy = diskMemoryHealthChecker.isHealthy();
 * }</pre>
 */
// Forked from <a href="https://github.com/micrometer-metrics/micrometer/blob/8339d57bef8689beb8d7a18b429a166f6595f2af/micrometer-core/src/main/java/io/micrometer/core/instrument/binder/system/DiskSpaceMetrics.java">DiskSpaceMetrics.java</a> in the micrometer core.
final class DiskMemoryHealthChecker implements HealthChecker {

    private final File file;

    private final long targetFreeDiskSpace;

    private final long targetTotalDiskSpace;

    DiskMemoryHealthChecker(long targetFreeDiskSpace, long targetTotalDiskSpace, String path) {
        this(targetFreeDiskSpace, targetTotalDiskSpace, new File(requireNonNull(path, "path")));
    }

    private DiskMemoryHealthChecker(long targetFreeDiskSpace, long targetTotalDiskSpace, File file) {
        checkArgument(targetFreeDiskSpace >= 0, "freeDiskSpace: %s (expected: >= 0)", targetFreeDiskSpace);
        checkArgument(targetTotalDiskSpace > 0, "totalDiskSpace: %s (expected: > 0)", targetTotalDiskSpace);
        this.targetFreeDiskSpace = targetFreeDiskSpace;
        this.targetTotalDiskSpace = targetTotalDiskSpace;
        this.file = file;
    }

    /**
     * Returns true if the file usable and total disk space are greater or equal than the target space.
     * @return boolean
     */
    @Override
    public boolean isHealthy() {
        return file.getUsableSpace() >= targetFreeDiskSpace && file.getTotalSpace() >= targetTotalDiskSpace;
    }
}
