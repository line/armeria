/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.armeria.common

import munit.FunSuite
import com.linecorp.armeria.scala.implicits._

class CookieSuite extends FunSuite {
  test("should be able to create Cookies from cookie headers") {
    val cookieA = Cookie.of("session_id", "foobar")
    val cookieB = Cookie.of("device_id", "Armeria")
    Cookie.fromCookieHeaders(cookieA.toCookieHeader)
    Cookie.fromCookieHeaders(cookieA.toCookieHeader, cookieB.toCookieHeader)
    Cookie.fromCookieHeaders(List(cookieA, cookieB).map(_.toCookieHeader).asJava)
    Cookie.fromCookieHeaders(true, cookieA.toCookieHeader, cookieB.toCookieHeader)
    Cookie.fromCookieHeaders(true, List(cookieA.toCookieHeader, cookieB.toCookieHeader).asJava)
  }
}
