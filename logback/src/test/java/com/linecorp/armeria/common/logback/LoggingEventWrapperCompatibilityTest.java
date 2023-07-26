package com.linecorp.armeria.common.logback;

import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

/**
 * Ensure compatibility with logback 1.2
 */
public class LoggingEventWrapperCompatibilityTest {

    @Test
    void testGetInstant() {
        LoggingEventWrapper sut = new LoggingEventWrapper(new LoggingEvent(), new HashMap<>());

        // this method does not break compatibility in logback 1.2
        sut.getInstant();
    }

    @Test
    void testGetMarkerList() {
        LoggingEventWrapper sut = new LoggingEventWrapper(new LoggingEvent(), new HashMap<>());

        // this method does not break compatibility in logback 1.2
        sut.getMarkerList();
    }
}
