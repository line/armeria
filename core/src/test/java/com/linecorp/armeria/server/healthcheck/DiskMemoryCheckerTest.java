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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;

import org.junit.jupiter.api.Test;

class DiskMemoryCheckerTest {

    @Test
    void throwExceptionWhenTargetFreeDiskSpaceLowerThanZero() {
        final double targetFreeDiskSpace = -1.0;
        assertThatThrownBy(() -> HealthChecker.ofDisk(targetFreeDiskSpace, new File("/tmp")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("freeDiskSpacePercentage: %.1f (expected >= 0 and expected <= 1)",
                                      targetFreeDiskSpace);
    }

    @Test
    void throwExceptionWhenPathIsNull() {
        final double targetFreeDiskSpace = 1.0;
        assertThatThrownBy(() -> HealthChecker.ofDisk(targetFreeDiskSpace, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldReturnTrueWhenDiskSpaceIsHealthy() {
        final DiskMemoryHealthChecker diskMemoryHealthChecker =
                (DiskMemoryHealthChecker) HealthChecker.ofDisk(
                        0.01, new File(".")
                );
        assertThat(diskMemoryHealthChecker.isHealthy()).isTrue();
    }
}
