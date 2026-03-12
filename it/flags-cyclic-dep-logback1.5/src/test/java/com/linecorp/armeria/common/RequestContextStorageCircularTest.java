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

package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientFactory;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.status.ErrorStatus;
import ch.qos.logback.core.status.Status;

class RequestContextStorageCircularTest {

    private static final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

    @Test
    void testCyclicDependencyInRequestContextUtil() {
        try (ClientFactory ignored = ClientFactory.builder().build()) {
            final List<Status> statuses = context.getStatusManager().getCopyOfStatusList();
            assertThat(statuses).allSatisfy(status -> {
                if (status instanceof ErrorStatus) {
                    status.getThrowable().printStackTrace();
                }
                assertThat(status.getMessage()).doesNotContain("Appender [RCEA] failed to append");
            });
        }
    }
}
