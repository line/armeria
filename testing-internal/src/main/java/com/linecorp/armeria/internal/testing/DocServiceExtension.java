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
package com.linecorp.armeria.internal.testing;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.docs.DocService;

/**
 * A JUnit extension that disables test timeout when {@link DocService} demo mode is enabled.
 */
public final class DocServiceExtension implements AfterTestExecutionCallback, BeforeTestExecutionCallback {

    private static final Logger logger = LoggerFactory.getLogger(DocServiceExtension.class);

    @Nullable
    private String oldTimeoutMode;

    @Override
    public void beforeTestExecution(ExtensionContext context) throws Exception {
        if (TestUtil.isDocServiceDemoMode()) {
            oldTimeoutMode = System.getProperty(Timeout.TIMEOUT_MODE_PROPERTY_NAME);
            if (!"disabled".equals(oldTimeoutMode)) {
                logger.debug("Disabling timeout for: {}#{}",
                             context.getRequiredTestClass().getName(),
                             context.getDisplayName());
                System.setProperty(Timeout.TIMEOUT_MODE_PROPERTY_NAME, "disabled");
            }
        }
    }

    @Override
    public void afterTestExecution(ExtensionContext context) throws Exception {
        if (TestUtil.isDocServiceDemoMode()) {
            if (oldTimeoutMode != null) {
                System.setProperty(Timeout.TIMEOUT_MODE_PROPERTY_NAME, oldTimeoutMode);
                oldTimeoutMode = null;
            } else {
                System.clearProperty(Timeout.TIMEOUT_MODE_PROPERTY_NAME);
            }
        }
    }
}
