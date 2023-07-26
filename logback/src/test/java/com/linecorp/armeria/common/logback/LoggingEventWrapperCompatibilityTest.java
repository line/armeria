/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.common.logback;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;

import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.spi.LoggingEvent;

/**
 * Ensure compatibility with logback 1.2
 */
public class LoggingEventWrapperCompatibilityTest {

    @Test
    void testGetInstant() {
        final LoggingEventWrapper sut = new LoggingEventWrapper(new LoggingEvent(), new HashMap<>());

        // this method does not break compatibility in logback 1.2
        sut.getInstant();
    }

    @Test
    void testGetMarkerList() {
        final LoggingEventWrapper sut = new LoggingEventWrapper(new LoggingEvent(), new HashMap<>());

        // this method does not break compatibility in logback 1.2
        sut.getMarkerList();
    }

    @Test
    void testGetSequenceNumber() {
        final LoggingEventWrapper sut = new LoggingEventWrapper(new LoggingEvent(), new HashMap<>());

        // this method cause a trouble in logback 1.2
        assertThatThrownBy(sut::getSequenceNumber).isInstanceOf(NoSuchMethodError.class);
    }

    @Test
    void testGetKeyValuePairs() {
        final LoggingEventWrapper sut = new LoggingEventWrapper(new LoggingEvent(), new HashMap<>());

        // this method cause a trouble in logback 1.2
        assertThatThrownBy(sut::getKeyValuePairs).isInstanceOf(NoSuchMethodError.class);
    }
}
