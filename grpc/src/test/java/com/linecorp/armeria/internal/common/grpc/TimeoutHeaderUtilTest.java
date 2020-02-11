/*
 * Copyright 2017 LINE Corporation
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
/*
 * Copyright 2014, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.linecorp.armeria.internal.common.grpc;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TimeoutHeaderUtilTest {

    @Test
    void timeoutTest() {
        // nanos
        Assertions.assertEquals("0n", TimeoutHeaderUtil.toHeaderValue(0L));
        Assertions.assertEquals(0L, TimeoutHeaderUtil.fromHeaderValue("0n"));

        Assertions.assertEquals("99999999n", TimeoutHeaderUtil.toHeaderValue(99999999L));
        Assertions.assertEquals(99999999L, TimeoutHeaderUtil.fromHeaderValue("99999999n"));

        // micros
        Assertions.assertEquals("100000u", TimeoutHeaderUtil.toHeaderValue(100000000L));
        Assertions.assertEquals(100000000L, TimeoutHeaderUtil.fromHeaderValue("100000u"));

        Assertions.assertEquals("99999999u", TimeoutHeaderUtil.toHeaderValue(99999999999L));
        Assertions.assertEquals(99999999000L, TimeoutHeaderUtil.fromHeaderValue("99999999u"));

        // millis
        Assertions.assertEquals("100000m", TimeoutHeaderUtil.toHeaderValue(100000000000L));
        Assertions.assertEquals(100000000000L, TimeoutHeaderUtil.fromHeaderValue("100000m"));

        Assertions.assertEquals("99999999m", TimeoutHeaderUtil.toHeaderValue(99999999999999L));
        Assertions.assertEquals(99999999000000L, TimeoutHeaderUtil.fromHeaderValue("99999999m"));

        // seconds
        Assertions.assertEquals("100000S", TimeoutHeaderUtil.toHeaderValue(100000000000000L));
        Assertions.assertEquals(100000000000000L, TimeoutHeaderUtil.fromHeaderValue("100000S"));

        Assertions.assertEquals("99999999S", TimeoutHeaderUtil.toHeaderValue(99999999999999999L));
        Assertions.assertEquals(99999999000000000L, TimeoutHeaderUtil.fromHeaderValue("99999999S"));

        // minutes
        Assertions.assertEquals("1666666M", TimeoutHeaderUtil.toHeaderValue(100000000000000000L));
        Assertions.assertEquals(99999960000000000L, TimeoutHeaderUtil.fromHeaderValue("1666666M"));

        Assertions.assertEquals("99999999M", TimeoutHeaderUtil.toHeaderValue(5999999999999999999L));
        Assertions.assertEquals(5999999940000000000L, TimeoutHeaderUtil.fromHeaderValue("99999999M"));

        // hours
        Assertions.assertEquals("1666666H", TimeoutHeaderUtil.toHeaderValue(6000000000000000000L));
        Assertions.assertEquals(5999997600000000000L, TimeoutHeaderUtil.fromHeaderValue("1666666H"));

        Assertions.assertEquals("2562047H", TimeoutHeaderUtil.toHeaderValue(Long.MAX_VALUE));
        Assertions.assertEquals(9223369200000000000L, TimeoutHeaderUtil.fromHeaderValue("2562047H"));

        Assertions.assertEquals(Long.MAX_VALUE, TimeoutHeaderUtil.fromHeaderValue("2562048H"));
    }
}
