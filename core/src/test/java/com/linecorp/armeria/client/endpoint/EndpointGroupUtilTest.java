/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.client.endpoint;

import static com.linecorp.armeria.client.endpoint.EndpointGroupUtil.getEndpointGroupName;
import static com.linecorp.armeria.client.endpoint.EndpointGroupUtil.replaceEndpointGroup;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class EndpointGroupUtilTest {

    private static final String endpointGroupMark = "group:";

    @Test
    public void testGetEndpointGroupName() throws Exception {
        assertNull(getEndpointGroupName("http://myGroupName/"));
        assertNull(getEndpointGroupName("http://myGroupName:8080/xxx"));
        assertNull(getEndpointGroupName("http://group1:myGroupName:8080/"));
        assertNull(getEndpointGroupName("http://username:password@myGroupName:8080/"));

        assertEquals("myGroupName", getEndpointGroupName("http://" + endpointGroupMark + "myGroupName/"));
        assertEquals("myGroupName", getEndpointGroupName("http://" + endpointGroupMark + "myGroupName:8080/"));
        assertEquals("myGroupName", getEndpointGroupName("http://" + endpointGroupMark + "myGroupName:8080/"));
        assertEquals("myGroupName", getEndpointGroupName("http://username:password@" + endpointGroupMark +
                                                         "myGroupName:8080/"));
    }

    @Test
    public void testReplace() throws Exception {
        final String replacement = "127.0.0.1:1234";
        assertEquals("http://myGroupName/",
                     replaceEndpointGroup("http://myGroupName/", replacement));
        assertEquals("http://myGroupName:8080/xxx",
                     replaceEndpointGroup("http://myGroupName:8080/xxx", replacement));
        assertEquals("http://group1:myGroupName:8080/",
                     replaceEndpointGroup("http://group1:myGroupName:8080/", replacement));
        assertEquals("http://username:password@myGroupName:8080/",
                     replaceEndpointGroup("http://username:password@myGroupName:8080/", replacement));

        assertEquals("http://127.0.0.1:1234/",
                     replaceEndpointGroup("http://" + endpointGroupMark + "myGroupName/", replacement));
        assertEquals("http://127.0.0.1:1234/",
                     replaceEndpointGroup("http://" + endpointGroupMark + "myGroupName:8080/", replacement));
        assertEquals("http://127.0.0.1:1234/xxx",
                     replaceEndpointGroup("http://" + endpointGroupMark + "myGroupName:8080/xxx", replacement));
        assertEquals("http://username:password@127.0.0.1:1234/xxx",
                     replaceEndpointGroup("http://username:password@" + endpointGroupMark +
                                          "myGroupName:8080/xxx", replacement));
    }
}
