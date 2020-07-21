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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;

@TestInstance(Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class AbstractAllOrEachExtensionTest {
    private static final AtomicInteger BEFORE_ALL_NUM_INVOCATIONS = new AtomicInteger();

    @RegisterExtension
    @Order(Integer.MAX_VALUE)
    static final AbstractAllOrEachExtension BEFORE_ALL_EXTENSION = new AbstractAllOrEachExtension() {
        @Override
        protected void before(ExtensionContext context) throws Exception {
            BEFORE_ALL_NUM_INVOCATIONS.incrementAndGet();
        }

        @Override
        protected void after(ExtensionContext context) throws Exception {
            BEFORE_ALL_NUM_INVOCATIONS.incrementAndGet();
        }
    };

    private static final AtomicInteger BEFORE_EACH_NUM_INVOCATIONS = new AtomicInteger();

    @RegisterExtension
    @Order(Integer.MAX_VALUE)
    static final AbstractAllOrEachExtension BEFORE_EACH_EXTENSION = new AbstractAllOrEachExtension() {
        @Override
        protected void before(ExtensionContext context) throws Exception {
            BEFORE_EACH_NUM_INVOCATIONS.incrementAndGet();
        }

        @Override
        protected void after(ExtensionContext context) throws Exception {
            BEFORE_EACH_NUM_INVOCATIONS.incrementAndGet();
        }

        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    @RegisterExtension
    @Order(1)
    static final BeforeAllCallback CHECK_START = context -> {
        assertThat(BEFORE_ALL_NUM_INVOCATIONS).hasValue(0);
        assertThat(BEFORE_EACH_NUM_INVOCATIONS).hasValue(0);
    };

    @RegisterExtension
    @Order(1)
    static final AfterAllCallback CHECK_END = context -> {
        assertThat(BEFORE_ALL_NUM_INVOCATIONS).hasValue(2);
        assertThat(BEFORE_EACH_NUM_INVOCATIONS).hasValue(4);
    };

    @Test
    @Order(1)
    void first() {
        assertThat(BEFORE_ALL_NUM_INVOCATIONS).hasValue(1);
        assertThat(BEFORE_EACH_NUM_INVOCATIONS).hasValue(1);
    }

    @Test
    @Order(2)
    void second() {
        assertThat(BEFORE_ALL_NUM_INVOCATIONS).hasValue(1);
        assertThat(BEFORE_EACH_NUM_INVOCATIONS).hasValue(3);
    }
}
