/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.testing.internal;

import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A JUnit5 extension that will cause assertion errors to be logged without failing the build on CI. Useful for
 * integration tests that have a high chance of flakiness due to timing issues.
 */
public class FailureLoggingExtension implements TestExecutionExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(FailureLoggingExtension.class);

    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        if (System.getenv("CI") != null &&
            (throwable instanceof AssertionError || throwable instanceof ConditionTimeoutException)) {
            logger.warn("Assertion in " + context.getDisplayName() + " failed but continuing anyways.",
                        throwable);
        } else {
            throw throwable;
        }
    }
}
