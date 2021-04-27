/*
 *  Copyright 2021 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */
/*
 * Logback: the reliable, generic, fast and flexible logging framework.
 * Copyright (C) 1999-2015, QOS.ch. All rights reserved.
 * <p>
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 * <p>
 * or (per the licensee's choosing)
 * <p>
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation.
 */
package com.linecorp.armeria.internal.common.util;

import static org.junit.Assert.assertEquals;

import org.junit.jupiter.api.Test;

class TargetLengthBasedClassNameAbbreviatorTest {

    // Forked from https://github.com/qos-ch/logback/blob/c2dcbf/logback-classic/src/test/java/ch/qos/logback/classic/pattern/TargetLengthBasedClassNameAbbreviatorTest.java

    @Test
    void testShortName() {
        TargetLengthBasedClassNameAbbreviator abbreviator = new TargetLengthBasedClassNameAbbreviator(100);
        String name = "hello";
        assertEquals(name, abbreviator.abbreviate(name));

        abbreviator = new TargetLengthBasedClassNameAbbreviator(100);
        name = "hello.world";
        assertEquals(name, abbreviator.abbreviate(name));
    }

    @Test
    void testNoDot() {
        final TargetLengthBasedClassNameAbbreviator abbreviator = new TargetLengthBasedClassNameAbbreviator(1);
        final String name = "hello";
        assertEquals(name, abbreviator.abbreviate(name));
    }

    @Test
    void testOneDot() {
        TargetLengthBasedClassNameAbbreviator abbreviator = new TargetLengthBasedClassNameAbbreviator(1);
        String name = "hello.world";
        assertEquals("h.world", abbreviator.abbreviate(name));

        abbreviator = new TargetLengthBasedClassNameAbbreviator(1);
        name = "h.world";
        assertEquals("h.world", abbreviator.abbreviate(name));

        abbreviator = new TargetLengthBasedClassNameAbbreviator(1);
        name = ".world";
        assertEquals(".world", abbreviator.abbreviate(name));
    }

    @Test
    void testTwoDot() {
        TargetLengthBasedClassNameAbbreviator abbreviator = new TargetLengthBasedClassNameAbbreviator(1);
        String name = "com.logback.Foobar";
        assertEquals("c.l.Foobar", abbreviator.abbreviate(name));

        abbreviator = new TargetLengthBasedClassNameAbbreviator(1);
        name = "c.logback.Foobar";
        assertEquals("c.l.Foobar", abbreviator.abbreviate(name));

        abbreviator = new TargetLengthBasedClassNameAbbreviator(1);
        name = "c..Foobar";
        assertEquals("c..Foobar", abbreviator.abbreviate(name));

        abbreviator = new TargetLengthBasedClassNameAbbreviator(1);
        name = "..Foobar";
        assertEquals("..Foobar", abbreviator.abbreviate(name));
    }

    @Test
    void test3Dot() {
        TargetLengthBasedClassNameAbbreviator abbreviator = new TargetLengthBasedClassNameAbbreviator(1);
        String name = "com.logback.xyz.Foobar";
        assertEquals("c.l.x.Foobar", abbreviator.abbreviate(name));

        abbreviator = new TargetLengthBasedClassNameAbbreviator(13);
        name = "com.logback.xyz.Foobar";
        assertEquals("c.l.x.Foobar", abbreviator.abbreviate(name));

        abbreviator = new TargetLengthBasedClassNameAbbreviator(14);
        name = "com.logback.xyz.Foobar";
        assertEquals("c.l.xyz.Foobar", abbreviator.abbreviate(name));

        abbreviator = new TargetLengthBasedClassNameAbbreviator(15);
        name = "com.logback.alligator.Foobar";
        assertEquals("c.l.a.Foobar", abbreviator.abbreviate(name));
    }

    @Test
    void testXDot() {
        TargetLengthBasedClassNameAbbreviator abbreviator = new TargetLengthBasedClassNameAbbreviator(21);
        String name = "com.logback.wombat.alligator.Foobar";
        assertEquals("c.l.w.a.Foobar", abbreviator.abbreviate(name));

        abbreviator = new TargetLengthBasedClassNameAbbreviator(22);
        name = "com.logback.wombat.alligator.Foobar";
        assertEquals("c.l.w.alligator.Foobar", abbreviator.abbreviate(name));

        abbreviator = new TargetLengthBasedClassNameAbbreviator(1);
        name = "com.logback.wombat.alligator.tomato.Foobar";
        assertEquals("c.l.w.a.t.Foobar", abbreviator.abbreviate(name));

        abbreviator = new TargetLengthBasedClassNameAbbreviator(21);
        name = "com.logback.wombat.alligator.tomato.Foobar";
        assertEquals("c.l.w.a.tomato.Foobar", abbreviator.abbreviate(name));

        abbreviator = new TargetLengthBasedClassNameAbbreviator(29);
        name = "com.logback.wombat.alligator.tomato.Foobar";
        assertEquals("c.l.w.alligator.tomato.Foobar", abbreviator.abbreviate(name));
    }
}
