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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.BindException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.SpringBootTestContextBootstrapper;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.cache.DefaultCacheAwareContextLoaderDelegate;
import org.springframework.test.context.support.DefaultBootstrapContext;

import com.google.common.base.Throwables;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.server.ServerPortBindException;

class RetryableSpringBootContextLoaderTest {

    private static int startupAttempts;
    @Nullable
    private static RuntimeException startupFailure;

    @BeforeEach
    void setUp() {
        startupAttempts = 0;
        startupFailure = null;
    }

    @Test
    void startsWithoutRetry() throws Exception {
        try (ConfigurableApplicationContext context = loadContext()) {
            assertThat(context.isActive()).isTrue();
            assertThat(startupAttempts).isOne();
        }
    }

    @Test
    void doesNotRetryUnrelatedStartupFailures() {
        final IllegalStateException cause = new IllegalStateException("intentional startup failure");
        startupFailure = cause;
        assertThatThrownBy(RetryableSpringBootContextLoaderTest::loadContext)
                .satisfies(thrown -> assertThat(Throwables.getCausalChain(thrown)).contains(cause));
        assertThat(startupAttempts).isOne();
    }

    @Test
    void stopsAfterEightBindFailures() {
        final ServerPortBindException cause = new ServerPortBindException(
                new ServerPort(0, SessionProtocol.HTTP), new BindException("occupied test port"));
        startupFailure = cause;
        assertThatThrownBy(RetryableSpringBootContextLoaderTest::loadContext)
                .satisfies(thrown -> assertThat(Throwables.getCausalChain(thrown)).contains(cause));
        assertThat(startupAttempts).isEqualTo(8);
    }

    private static ConfigurableApplicationContext loadContext() throws Exception {
        final SpringBootTestContextBootstrapper bootstrapper = new SpringBootTestContextBootstrapper();
        bootstrapper.setBootstrapContext(new DefaultBootstrapContext(
                TestApplication.class, new DefaultCacheAwareContextLoaderDelegate()));
        return (ConfigurableApplicationContext) new RetryableSpringBootContextLoader()
                .loadContext(bootstrapper.buildMergedContextConfiguration());
    }

    @SpringBootTest(classes = EmptyConfiguration.class, webEnvironment = WebEnvironment.NONE)
    @ContextConfiguration(initializers = StartupInitializer.class)
    static class TestApplication {}

    @Configuration(proxyBeanMethods = false)
    static class EmptyConfiguration {}

    public static class StartupInitializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            startupAttempts++;
            if (startupFailure != null) {
                throw startupFailure;
            }
        }
    }
}
