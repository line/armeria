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

package com.linecorp.armeria.testing.junit5.common;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * A base class for JUnit5 extensions that allows implementations to control whether the callbacks are run
 * around the entire class, like {@link BeforeAll} or {@link AfterAll}, or around each test method, like
 * {@link BeforeEach} or {@link AfterEach}. By default, the extension will run around the entire class -
 * implementations that want to run around each test method instead should override
 * {@link #runForEachTest()}.
 */
public abstract class AbstractAllOrEachExtension
        implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback {

    /**
     * A method that should be run at the beginning of a test lifecycle. If {@link #runForEachTest()}
     * returns {@code false}, this is run once before all tests, otherwise it is run before each test
     * method.
     */
    protected abstract void before(ExtensionContext context) throws Exception;

    /**
     * A method that should be run at the end of a test lifecycle. If {@link #runForEachTest()}
     * returns {@code false}, this is run once after all tests, otherwise it is run after each test
     * method.
     */
    protected abstract void after(ExtensionContext context) throws Exception;

    @Override
    public final void beforeAll(ExtensionContext context) throws Exception {
        if (!runForEachTest()) {
            before(context);
        }
    }

    @Override
    public final void afterAll(ExtensionContext context) throws Exception {
        if (!runForEachTest()) {
            after(context);
        }
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        if (runForEachTest()) {
            before(context);
        }
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        if (runForEachTest()) {
            after(context);
        }
    }

    /**
     * Returns whether this extension should run around each test method instead of the entire test class.
     * Implementations should override this method to return {@code true} to run around each test method.
     */
    protected boolean runForEachTest() {
        return false;
    }
}
