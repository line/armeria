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

package com.linecorp.armeria.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

import java.util.Arrays;

import org.junit.Test;

public class RequestContextExporterBuilderTest {

    @Test
    public void testExportBuiltInProperties() throws Exception {
        final RequestContextExporterBuilder builder = new RequestContextExporterBuilder();
        for (BuiltInProperty property : BuiltInProperty.values()) {
            builder.export(property.mdcKey);
        }
        assertThat(builder.getBuiltIns()).containsExactly(BuiltInProperty.values());
    }

    @Test
    public void testExportWithoutWildcards() throws Exception {
        final RequestContextExporterBuilder builder = new RequestContextExporterBuilder();
        builder.export(BuiltInProperty.REMOTE_HOST.mdcKey);
        assertThat(builder.getBuiltIns()).containsExactly(BuiltInProperty.REMOTE_HOST);
    }

    @Test
    public void testExportWithWildcard() throws Exception {
        final RequestContextExporterBuilder builder = new RequestContextExporterBuilder();
        final BuiltInProperty[] expectedProperties =
                Arrays.stream(BuiltInProperty.values())
                      .filter(p -> p.mdcKey.startsWith("req."))
                      .toArray(BuiltInProperty[]::new);
        builder.export("req.*");
        assertThat(builder.getBuiltIns()).containsExactly(expectedProperties);
    }

    @Test
    public void testExportWithWildcards() throws Exception {
        final RequestContextExporterBuilder builder = new RequestContextExporterBuilder();
        final BuiltInProperty[] expectedProperties =
                Arrays.stream(BuiltInProperty.values())
                      .filter(p -> p.mdcKey.contains("rpc"))
                      .toArray(BuiltInProperty[]::new);
        builder.export("*rpc*");
        assertThat(builder.getBuiltIns()).containsExactly(expectedProperties);
    }

    @Test
    public void testExportAttrWithWildcard() throws Exception {
        final RequestContextExporterBuilder builder = new RequestContextExporterBuilder();
        builder.export("attrs.*");
        builder.export("attrs.my_attrs:MyAttribute");
        assertEquals(1, builder.getAttributes().size());
    }
}
