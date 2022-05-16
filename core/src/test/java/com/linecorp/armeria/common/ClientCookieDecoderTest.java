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
/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
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
package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;

import io.netty.handler.codec.DateFormatter;

class ClientCookieDecoderTest {

    // Forked from netty-4.1.34.
    // https://github.com/netty/netty/blob/587afddb279bea3fd0f64d3421de8e69a35cecb9/codec-http/src/test/java/io/netty/handler/codec/http/cookie/ClientCookieDecoderTest.java

    @Test
    void testDecodingSingleCookieV0() {
        final String cookieString = "myCookie=myValue;expires=" +
                                    DateFormatter.format(new Date(System.currentTimeMillis() + 50000)) +
                                    ";path=/apathsomewhere;domain=.adomainsomewhere;secure;";

        final Cookie cookie = Cookie.fromSetCookieHeader(cookieString);
        assertThat(cookie).isNotNull();
        assertThat(cookie.value()).isEqualTo("myValue");
        assertThat(cookie.domain()).isEqualTo("adomainsomewhere");
        assertThat(cookie.maxAge()).withFailMessage("maxAge should be defined when parsing cookie: " +
                                                    cookieString)
                                   .isNotEqualTo(Cookie.UNDEFINED_MAX_AGE);
        assertThat(cookie.maxAge()).withFailMessage("maxAge should be about 50ms when parsing cookie: " +
                                                    cookieString)
                                   .isGreaterThanOrEqualTo(40)
                                   .isLessThanOrEqualTo(60);
        assertThat(cookie.path()).isEqualTo("/apathsomewhere");
        assertThat(cookie.isSecure()).isTrue();
    }

    @Test
    void testDecodingSingleCookieV0ExtraParamsIgnored() {
        final String cookieString = "myCookie=myValue;max-age=50;path=/apathsomewhere;" +
                                    "domain=.adomainsomewhere;secure;comment=this is a comment;version=0;" +
                                    "commentURL=http://aurl.com;port=\"80,8080\";discard;";
        final Cookie cookie = Cookie.fromSetCookieHeader(cookieString);
        assertThat(cookie).isNotNull();
        assertThat(cookie.value()).isEqualTo("myValue");
        assertThat(cookie.domain()).isEqualTo("adomainsomewhere");
        assertThat(cookie.maxAge()).isEqualTo(50);
        assertThat(cookie.path()).isEqualTo("/apathsomewhere");
        assertThat(cookie.isSecure()).isTrue();
    }

    @Test
    void testDecodingSingleCookieV1() {
        final String cookieString = "myCookie=myValue;max-age=50;path=/apathsomewhere;" +
                                    "domain=.adomainsomewhere;secure;comment=this is a comment;version=1;" +
                                    "SameSite=Lax";
        final Cookie cookie = Cookie.fromSetCookieHeader(cookieString);
        assertThat(cookie).isNotNull();
        assertThat(cookie.value()).isEqualTo("myValue");
        assertThat(cookie.domain()).isEqualTo("adomainsomewhere");
        assertThat(cookie.maxAge()).isEqualTo(50);
        assertThat(cookie.path()).isEqualTo("/apathsomewhere");
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.sameSite()).isEqualTo("Lax");
    }

    @Test
    void testDecodingSingleCookieV1ExtraParamsIgnored() {
        final String cookieString = "myCookie=myValue;max-age=50;path=/apathsomewhere;" +
                                    "domain=.adomainsomewhere;secure;comment=this is a comment;version=1;" +
                                    "commentURL=http://aurl.com;port='80,8080';discard;";
        final Cookie cookie = Cookie.fromSetCookieHeader(cookieString);
        assertThat(cookie).isNotNull();
        assertThat(cookie.value()).isEqualTo("myValue");
        assertThat(cookie.domain()).isEqualTo("adomainsomewhere");
        assertThat(cookie.maxAge()).isEqualTo(50);
        assertThat(cookie.path()).isEqualTo("/apathsomewhere");
        assertThat(cookie.isSecure()).isTrue();
    }

    @Test
    void testDecodingSingleCookieV2() {
        final String cookieString = "myCookie=myValue;max-age=50;path=/apathsomewhere;" +
                                    "domain=.adomainsomewhere;secure;comment=this is a comment;version=2;" +
                                    "commentURL=http://aurl.com;port=\"80,8080\";discard;";
        final Cookie cookie = Cookie.fromSetCookieHeader(cookieString);
        assertThat(cookie).isNotNull();
        assertThat(cookie.value()).isEqualTo("myValue");
        assertThat(cookie.domain()).isEqualTo("adomainsomewhere");
        assertThat(cookie.maxAge()).isEqualTo(50);
        assertThat(cookie.path()).isEqualTo("/apathsomewhere");
        assertThat(cookie.isSecure()).isTrue();
    }

    @Test
    void testDecodingComplexCookie() {
        final String c1 = "myCookie=myValue;max-age=50;path=/apathsomewhere;" +
                          "domain=.adomainsomewhere;secure;comment=this is a comment;version=2;" +
                          "commentURL=\"http://aurl.com\";port='80,8080';discard;";

        final Cookie cookie = Cookie.fromSetCookieHeader(c1);
        assertThat(cookie).isNotNull();
        assertThat(cookie.value()).isEqualTo("myValue");
        assertThat(cookie.domain()).isEqualTo("adomainsomewhere");
        assertThat(cookie.maxAge()).isEqualTo(50);
        assertThat(cookie.path()).isEqualTo("/apathsomewhere");
        assertThat(cookie.isSecure()).isTrue();
    }

    @Test
    void testDecodingQuotedCookie() {
        final Collection<String> sources = new ArrayList<>();
        sources.add("a=\"\",");
        sources.add("b=\"1\",");

        final Collection<Cookie> cookies = new ArrayList<>();
        for (String source : sources) {
            cookies.add(Cookie.fromSetCookieHeader(source));
        }

        final Iterator<Cookie> it = cookies.iterator();
        Cookie c;

        c = it.next();
        assertThat(c.name()).isEqualTo("a");
        assertThat(c.value()).isEmpty();

        c = it.next();
        assertThat(c.name()).isEqualTo("b");
        assertThat(c.value()).isEqualTo("1");

        assertThat(it.hasNext()).isFalse();
    }

    @Test
    void testDecodingGoogleAnalyticsCookie() {
        final String source = "ARPT=LWUKQPSWRTUN04CKKJI; " +
                              "kw-2E343B92-B097-442c-BFA5-BE371E0325A2=unfinished furniture; " +
                              "__utma=48461872.1094088325.1258140131.1258140131.1258140131.1; " +
                              "__utmb=48461872.13.10.1258140131; __utmc=48461872; " +
                              "__utmz=48461872.1258140131.1.1.utmcsr=overstock.com|utmccn=(referral)|" +
                              "utmcmd=referral|utmcct=/Home-Garden/Furniture/Clearance,/clearance," +
                              "/32/dept.html";
        final Cookie cookie = Cookie.fromSetCookieHeader(source);
        assertThat(cookie).isNotNull();
        assertThat(cookie.name()).isEqualTo("ARPT");
        assertThat(cookie.value()).isEqualTo("LWUKQPSWRTUN04CKKJI");
    }

    @Test
    void testDecodingLongDates() {
        final Calendar cookieDate = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cookieDate.set(9999, Calendar.DECEMBER, 31, 23, 59, 59);
        final long expectedMaxAge = (cookieDate.getTimeInMillis() - System.currentTimeMillis()) / 1000;

        final String source = "Format=EU; expires=Fri, 31-Dec-9999 23:59:59 GMT; path=/";

        final Cookie cookie = Cookie.fromSetCookieHeader(source);
        assertThat(cookie).isNotNull();
        assertThat(Math.abs(expectedMaxAge - cookie.maxAge())).isLessThan(2);
    }

    @Test
    void testDecodingValueWithCommaFails() {
        final String source = "UserCookie=timeZoneName=(GMT+04:00) Moscow, St. Petersburg, " +
                              "Volgograd&promocode=&region=BE; expires=Sat, 01-Dec-2012 10:53:31 GMT; path=/";
        final Cookie cookie = Cookie.fromSetCookieHeader(source);
        assertThat(cookie).isNull();
    }

    @Test
    void testDecodingWeirdNames1() {
        final String src = "path=; expires=Mon, 01-Jan-1990 00:00:00 GMT; path=/; domain=.www.google.com";
        final Cookie cookie = Cookie.fromSetCookieHeader(src);
        assertThat(cookie).isNotNull();
        assertThat(cookie.name()).isEqualTo("path");
        assertThat(cookie.value()).isEqualTo("");
        assertThat(cookie.path()).isEqualTo("/");
    }

    @Test
    void testDecodingWeirdNames2() {
        final String src = "HTTPOnly=";
        final Cookie cookie = Cookie.fromSetCookieHeader(src);
        assertThat(cookie).isNotNull();
        assertThat(cookie.name()).isEqualTo("HTTPOnly");
        assertThat(cookie.value()).isEmpty();
    }

    @Test
    void testDecodingWeirdNames3() {
        final String src = "SameSite=";
        final Cookie cookie = Cookie.fromSetCookieHeader(src);
        assertThat(cookie).isNotNull();
        assertThat(cookie.name()).isEqualTo("SameSite");
        assertThat(cookie.value()).isEmpty();
    }

    @Test
    void testDecodingValuesWithCommasAndEqualsFails() {
        final String src = "A=v=1&lg=en-US,it-IT,it&intl=it&np=1;T=z=E";
        final Cookie cookie = Cookie.fromSetCookieHeader(src);
        assertThat(cookie).isNull();
    }

    @Test
    void testDecodingInvalidValuesWithCommaAtStart() {
        assertThat(Cookie.fromSetCookieHeader(",")).isNull();
        assertThat(Cookie.fromSetCookieHeader(",a")).isNull();
        assertThat(Cookie.fromSetCookieHeader(",a=a")).isNull();
    }

    @Test
    void testDecodingLongValue() {
        final String longValue =
                "b___$Q__$ha__<NC=MN(F__%#4__<NC=MN(F__2_d____#=IvZB__2_F____'=KqtH__2-9____" +
                "'=IvZM__3f:____$=HbQW__3g'____%=J^wI__3g-____%=J^wI__3g1____$=HbQW__3g2____" +
                "$=HbQW__3g5____%=J^wI__3g9____$=HbQW__3gT____$=HbQW__3gX____#=J^wI__3gY____" +
                "#=J^wI__3gh____$=HbQW__3gj____$=HbQW__3gr____$=HbQW__3gx____#=J^wI__3h_____" +
                "$=HbQW__3h$____#=J^wI__3h'____$=HbQW__3h_____$=HbQW__3h0____%=J^wI__3h1____" +
                "#=J^wI__3h2____$=HbQW__3h4____$=HbQW__3h7____$=HbQW__3h8____%=J^wI__3h:____" +
                "#=J^wI__3h@____%=J^wI__3hB____$=HbQW__3hC____$=HbQW__3hL____$=HbQW__3hQ____" +
                "$=HbQW__3hS____%=J^wI__3hU____$=HbQW__3h[____$=HbQW__3h^____$=HbQW__3hd____" +
                "%=J^wI__3he____%=J^wI__3hf____%=J^wI__3hg____$=HbQW__3hh____%=J^wI__3hi____" +
                "%=J^wI__3hv____$=HbQW__3i/____#=J^wI__3i2____#=J^wI__3i3____%=J^wI__3i4____" +
                "$=HbQW__3i7____$=HbQW__3i8____$=HbQW__3i9____%=J^wI__3i=____#=J^wI__3i>____" +
                "%=J^wI__3iD____$=HbQW__3iF____#=J^wI__3iH____%=J^wI__3iM____%=J^wI__3iS____" +
                "#=J^wI__3iU____%=J^wI__3iZ____#=J^wI__3i]____%=J^wI__3ig____%=J^wI__3ij____" +
                "%=J^wI__3ik____#=J^wI__3il____$=HbQW__3in____%=J^wI__3ip____$=HbQW__3iq____" +
                "$=HbQW__3it____%=J^wI__3ix____#=J^wI__3j_____$=HbQW__3j%____$=HbQW__3j'____" +
                "%=J^wI__3j(____%=J^wI__9mJ____'=KqtH__=SE__<NC=MN(F__?VS__<NC=MN(F__Zw`____" +
                "%=KqtH__j+C__<NC=MN(F__j+M__<NC=MN(F__j+a__<NC=MN(F__j_.__<NC=MN(F__n>M____" +
                "'=KqtH__s1X____$=MMyc__s1_____#=MN#O__ypn____'=KqtH__ypr____'=KqtH_#%h_____" +
                "%=KqtH_#%o_____'=KqtH_#)H6__<NC=MN(F_#*%'____%=KqtH_#+k(____'=KqtH_#-E_____" +
                "'=KqtH_#1)w____'=KqtH_#1)y____'=KqtH_#1*M____#=KqtH_#1*p____'=KqtH_#14Q__<N" +
                "C=MN(F_#14S__<NC=MN(F_#16I__<NC=MN(F_#16N__<NC=MN(F_#16X__<NC=MN(F_#16k__<N" +
                "C=MN(F_#17@__<NC=MN(F_#17A__<NC=MN(F_#1Cq____'=KqtH_#7)_____#=KqtH_#7)b____" +
                "#=KqtH_#7Ww____'=KqtH_#?cQ____'=KqtH_#His____'=KqtH_#Jrh____'=KqtH_#O@M__<N" +
                "C=MN(F_#O@O__<NC=MN(F_#OC6__<NC=MN(F_#Os.____#=KqtH_#YOW____#=H/Li_#Zat____" +
                "'=KqtH_#ZbI____%=KqtH_#Zbc____'=KqtH_#Zbs____%=KqtH_#Zby____'=KqtH_#Zce____" +
                "'=KqtH_#Zdc____%=KqtH_#Zea____'=KqtH_#ZhI____#=KqtH_#ZiD____'=KqtH_#Zis____" +
                "'=KqtH_#Zj0____#=KqtH_#Zj1____'=KqtH_#Zj[____'=KqtH_#Zj]____'=KqtH_#Zj^____" +
                "'=KqtH_#Zjb____'=KqtH_#Zk_____'=KqtH_#Zk6____#=KqtH_#Zk9____%=KqtH_#Zk<____" +
                "'=KqtH_#Zl>____'=KqtH_#]9R____$=H/Lt_#]I6____#=KqtH_#]Z#____%=KqtH_#^*N____" +
                "#=KqtH_#^:m____#=KqtH_#_*_____%=J^wI_#`-7____#=KqtH_#`T>____'=KqtH_#`T?____" +
                "'=KqtH_#`TA____'=KqtH_#`TB____'=KqtH_#`TG____'=KqtH_#`TP____#=KqtH_#`U_____" +
                "'=KqtH_#`U/____'=KqtH_#`U0____#=KqtH_#`U9____'=KqtH_#aEQ____%=KqtH_#b<)____" +
                "'=KqtH_#c9-____%=KqtH_#dxC____%=KqtH_#dxE____%=KqtH_#ev$____'=KqtH_#fBi____" +
                "#=KqtH_#fBj____'=KqtH_#fG)____'=KqtH_#fG+____'=KqtH_#g<d____'=KqtH_#g<e____" +
                "'=KqtH_#g=J____'=KqtH_#gat____#=KqtH_#s`D____#=J_#p_#sg?____#=J_#p_#t<a____" +
                "#=KqtH_#t<c____#=KqtH_#trY____$=JiYj_#vA$____'=KqtH_#xs_____'=KqtH_$$rO____" +
                "#=KqtH_$$rP____#=KqtH_$(_%____'=KqtH_$)]o____%=KqtH_$_@)____'=KqtH_$_k]____" +
                "'=KqtH_$1]+____%=KqtH_$3IO____%=KqtH_$3J#____'=KqtH_$3J.____'=KqtH_$3J:____" +
                "#=KqtH_$3JH____#=KqtH_$3JI____#=KqtH_$3JK____%=KqtH_$3JL____'=KqtH_$3JS____" +
                "'=KqtH_$8+M____#=KqtH_$99d____%=KqtH_$:Lw____#=LK+x_$:N@____#=KqtG_$:NC____" +
                "#=KqtG_$:hW____'=KqtH_$:i[____'=KqtH_$:ih____'=KqtH_$:it____'=KqtH_$:kO____" +
                "'=KqtH_$>*B____'=KqtH_$>hD____+=J^x0_$?lW____'=KqtH_$?ll____'=KqtH_$?lm____" +
                "%=KqtH_$?mi____'=KqtH_$?mx____'=KqtH_$D7]____#=J_#p_$D@T____#=J_#p_$V<g____" +
                "'=KqtH";

        final Cookie cookie = Cookie.fromSetCookieHeader("bh=\"" + longValue + "\";");
        assertThat(cookie).isNotNull();
        assertThat(cookie.name()).isEqualTo("bh");
        assertThat(cookie.value()).isEqualTo(longValue);
    }

    @Test
    void testIgnoreEmptyDomain() {
        final String emptyDomain = "sessionid=OTY4ZDllNTgtYjU3OC00MWRjLTkzMWMtNGUwNzk4MTY0MTUw;Domain=;Path=/";
        final Cookie cookie = Cookie.fromSetCookieHeader(emptyDomain);
        assertThat(cookie).isNotNull();
        assertThat(cookie.domain()).isNull();
    }

    @Test
    void testIgnoreEmptyPath() {
        final String emptyPath = "sessionid=OTY4ZDllNTgtYjU3OC00MWRjLTkzMWMtNGUwNzk4MTY0MTUw;Domain=;Path=";
        final Cookie cookie = Cookie.fromSetCookieHeader(emptyPath);
        assertThat(cookie).isNotNull();
        assertThat(cookie.path()).isNull();
    }

    @Test
    void testDecodingSameSite() {
        assertThat(Cookie.fromSetCookieHeader("myCookie=myValue;SameSite=None").sameSite())
                .isEqualTo("None");
        assertThat(Cookie.fromSetCookieHeader("myCookie=myValue;HTTPOnly;SameSite=STRICT").sameSite())
                .isEqualTo("Strict");
        assertThat(Cookie.fromSetCookieHeader("myCookie=myValue;SameSite=Invalid").sameSite()).isEqualTo("Lax");
        assertThat(Cookie.fromSetCookieHeader("myCookie=myValue;SameSite=").sameSite()).isEqualTo("Lax");
    }
}
