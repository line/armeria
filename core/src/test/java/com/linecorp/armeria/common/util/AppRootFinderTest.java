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
package com.linecorp.armeria.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

class AppRootFinderTest {

    @Test
    void findCurrent() {
        final Path path =
                Paths.get(AppRootFinder.findCurrent().toString(),
                          AppRootFinderTest.class.getName().replace('.', File.separatorChar) + ".class");

        assertThat(path).isRegularFile();
    }

    @Test
    void findCurrentWithCallDepth() {
        final Path path = AppRootFinder.findCurrent(1);
        assertThat(path.toUri().toString()).matches(".*/[^/]*junit[^/]*\\.jar$");
    }

    @Test
    void findCurrentWithBadCallDepth() {
        assertThatThrownBy(() -> AppRootFinder.findCurrent(-1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> AppRootFinder.findCurrent(8192)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void findWithMyClass() {
        final Path path =
                Paths.get(AppRootFinder.find(AppRootFinderTest.class).toString(),
                          AppRootFinderTest.class.getName().replace('.', File.separatorChar) + ".class");

        assertThat(path).isRegularFile();
    }

    @Test
    void findWithOthersClass() {
        final Path path = AppRootFinder.find(Test.class);
        assertThat(path.toUri().toString()).matches(".*/[^/]*junit[^/]*\\.jar$");
    }
}
