/*
 * Copyright 2021 LINE Corporation
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
 * under the Licenses
 */

package com.linecorp.armeria.common.rxjava2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.opentest4j.AssertionFailedError;

import io.reactivex.plugins.RxJavaPlugins;

/**
 * For detecting exception thrown in the chain.
 */
class RxErrorDetectExtension implements BeforeEachCallback, AfterEachCallback {
    private final List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        errors.clear();
        RxJavaPlugins.setErrorHandler(errors::add);
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        RxJavaPlugins.setErrorHandler(null);
        if (errors.isEmpty()) {
            return;
        }
        throw new AssertionFailedError("Unexpected exceptions caught by global handler: " + errors);
    }
}
