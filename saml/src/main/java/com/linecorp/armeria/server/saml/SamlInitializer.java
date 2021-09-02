/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.server.saml;

import org.opensaml.core.config.InitializationService;
import org.opensaml.xmlsec.config.impl.JavaCryptoValidationInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A utility class which initializes the OpenSAML library.
 * See <a href="https://wiki.shibboleth.net/confluence/display/OS30/Home">OpenSAML 3</a> for more information.
 */
final class SamlInitializer {
    private static final Logger logger = LoggerFactory.getLogger(SamlInitializer.class);

    @Nullable
    private static final Throwable UNAVAILABILITY_CAUSE;

    static {
        @Nullable Throwable cause = null;
        try {
            // To help confirm that your JCE implementations have everything needed by OpenSAML,
            // the following method is provided:
            final JavaCryptoValidationInitializer javaCryptoValidationInitializer =
                    new JavaCryptoValidationInitializer();
            javaCryptoValidationInitializer.init();

            // The configuration files must be loaded before the OpenSAML library can be used.
            // To load the default configuration files, run:
            InitializationService.initialize();

            logger.debug("OpenSAML has been initialized.");
        } catch (Throwable cause0) {
            cause = cause0;
        } finally {
            UNAVAILABILITY_CAUSE = cause;
        }
    }

    /**
     * Returns {@code true} if and only if the OpenSAML library is available.
     */
    static boolean isAvailable() {
        return unavailabilityCause() == null;
    }

    /**
     * Ensures that the OpenSAML library is available.
     *
     * @throws Error if unavailable
     */
    static void ensureAvailability() {
        if (!isAvailable()) {
            throw new Error("failed to initialize OpenSAML library", unavailabilityCause());
        }
    }

    /**
     * Returns the cause of unavailability of the OpenSAML library.
     *
     * @return the cause if unavailable, or {@code null} if available.
     */
    @Nullable
    static Throwable unavailabilityCause() {
        return UNAVAILABILITY_CAUSE;
    }

    private SamlInitializer() {}
}
