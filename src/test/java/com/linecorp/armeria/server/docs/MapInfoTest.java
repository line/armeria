/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.docs;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import org.apache.thrift.meta_data.FieldValueMetaData;
import org.apache.thrift.meta_data.MapMetaData;
import org.apache.thrift.protocol.TType;
import org.junit.Test;

public class MapInfoTest {

    @Test
    public void testOf() throws Exception {
        final MapInfo map = MapInfo.of(new MapMetaData(TType.MAP,
                                                       new FieldValueMetaData(TType.I32, false),
                                                       new FieldValueMetaData(TType.STRING, false)));

        assertThat(map, is(MapInfo.of(TypeInfo.of(ValueType.I32, false),
                                      TypeInfo.of(ValueType.STRING, false))));
    }
}
