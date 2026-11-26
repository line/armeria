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
package com.linecorp.armeria.spring.actuate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootContextLoader;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.MergedContextConfiguration;

import com.google.common.base.Throwables;

import com.linecorp.armeria.server.ServerPortBindException;

/**
 * A test context loader that recreates server settings and services after port binding failures.
 */
public final class RetryableSpringBootContextLoader extends SpringBootContextLoader {

    private static final Logger logger = LoggerFactory.getLogger(RetryableSpringBootContextLoader.class);
    private static final int MAX_ATTEMPTS = 8;

    @Override
    public ApplicationContext loadContext(MergedContextConfiguration config) throws Exception {
        for (int attempt = 1;; attempt++) {
            try {
                return super.loadContext(config);
            } catch (Exception e) {
                if (attempt == MAX_ATTEMPTS ||
                    Throwables.getCausalChain(e).stream()
                              .noneMatch(ServerPortBindException.class::isInstance)) {
                    throw e;
                }
                logger.debug("Failed to bind a test server. Recreating the context (attempts so far: {})",
                             attempt, e);
            }
        }
    }
}
