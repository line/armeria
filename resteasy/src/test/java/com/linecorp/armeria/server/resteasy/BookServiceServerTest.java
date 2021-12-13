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

package com.linecorp.armeria.server.resteasy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.jboss.resteasy.core.ResteasyDeploymentImpl;
import org.jboss.resteasy.spi.ResteasyDeployment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.Cookies;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.jaxrs.samples.JaxRsApp;
import com.linecorp.armeria.server.jaxrs.samples.books.Book;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

public class BookServiceServerTest {

    private static final Logger logger = LoggerFactory.getLogger(BookServiceServerTest.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    @RegisterExtension
    static ServerExtension restServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder serverBuilder) throws Exception {
            logger.info("Configuring HTTP Server with RESTEasy Service");
            serverBuilder.accessLogger(logger);
            final ResteasyDeployment deployment = new ResteasyDeploymentImpl();
            //deployment.setApplicationClass(JaxRsApp.class.getName());
            deployment.setApplication(new JaxRsApp());
            ResteasyService.builder(deployment).path("/resteasy").build().register(serverBuilder);
        }
    };

    @Test
    void testBooksContext() throws Exception {
        final WebClient restClient = newWebClient();

        final String contextPath = "/resteasy/app/calc/context";
        final AggregatedHttpResponse context = restClient
                .execute(RequestHeaders.builder(HttpMethod.GET, contextPath)
                                       .add(HttpHeaderNames.COOKIE, "param1=1234; other=xyz").build())
                .aggregate()
                .join();
        logger.info("{} responded with {}", contextPath, context.contentUtf8());
        assertThat(context.status()).isEqualTo(HttpStatus.OK);
        assertThat(context.contentType()).isNull();
        assertThat(context.content().isEmpty()).isTrue();
        final Cookies cookies =
                Cookie.fromSetCookieHeaders(context.headers().getAll(HttpHeaderNames.SET_COOKIE));
        assertThat(cookies).containsOnly(Cookie.ofSecure("serverCookie", "123"));
    }

    @Test
    void testBooks() throws Exception {
        final WebClient restClient = newWebClient();

        final String booksPath = "/resteasy/app/books/";
        final AggregatedHttpResponse getBooks = restClient
                .get(booksPath + "?author=John%20Doe&title=Java")
                .aggregate()
                .join();
        assertThat(getBooks.status()).isEqualTo(HttpStatus.OK);
        assertThat(getBooks.contentType()).isNotNull();
        assertThat(getBooks.contentType().nameWithoutParameters())
                .isEqualTo(MediaType.JSON_UTF_8.nameWithoutParameters());
        final String getBooksEntry = getBooks.contentUtf8();
        assertThat(getBooksEntry).isNotEmpty();
        logger.info("{} responded with {}", booksPath, getBooksEntry);
        assertThat(getBooksEntry).contains("John Doe");
        assertThat(getBooksEntry).contains("Java");
        final Book[] getBooksEntryArray = JSON.readValue(getBooksEntry, Book[].class);
        assertThat(getBooksEntryArray).hasSize(3);

        final AggregatedHttpResponse getAllBooks = restClient
                .get(booksPath)
                .aggregate()
                .join();
        assertThat(getAllBooks.status()).isEqualTo(HttpStatus.OK);
        assertThat(getAllBooks.contentType()).isNotNull();
        assertThat(getAllBooks.contentType().nameWithoutParameters())
                .isEqualTo(MediaType.JSON_UTF_8.nameWithoutParameters());
        final String getAllBooksEntry = getAllBooks.contentUtf8();
        assertThat(getAllBooksEntry).isNotEmpty();
        logger.info("{} responded with {}", booksPath, getAllBooksEntry);
        assertThat(getAllBooksEntry.length()).isGreaterThan(getBooksEntry.length());
        final Book[] getAllBooksEntryArray = JSON.readValue(getAllBooksEntry, Book[].class);
        assertThat(getAllBooksEntryArray).contains(getBooksEntryArray);

        final String getBookPath = "/resteasy/app/books/978-3-16-148410-0";
        final AggregatedHttpResponse getBook = restClient
                .get(getBookPath)
                .aggregate()
                .join();
        assertThat(getBook.status()).isEqualTo(HttpStatus.OK);
        assertThat(getBook.contentType()).isEqualTo(MediaType.JSON);
        final String getBookEntry = getBook.contentUtf8();
        assertThat(getBookEntry).isNotEmpty();
        logger.info("{} responded with {}", getBookPath, getBookEntry);
        final Book bookGetBook = JSON.readValue(getBookEntry, Book.class);
        assertThat(bookGetBook.getTitle()).isEqualTo("Java Fun");
        assertThat(bookGetBook.getAuthor()).isEqualTo("John Doe");
        assertThat(bookGetBook.getPrice()).isEqualTo(10f);

        final AggregatedHttpResponse post1 = restClient
                .execute(RequestHeaders.builder(HttpMethod.POST, booksPath)
                                       .contentType(MediaType.FORM_DATA)
                                       .add(HttpHeaderNames.COOKIE, "issue=1234; param1=xyz").build(),
                         HttpData.of(StandardCharsets.UTF_8,
                                     Book.ofHtmlForm(
                                             "isbn=1-9999-9999-9&author=Jane%20Austen&title=Emma&price=15.50")
                                         .toHtmlForm()))
                .aggregate()
                .join();
        assertThat(post1.status()).isEqualTo(HttpStatus.NO_CONTENT);

        final AggregatedHttpResponse post2 = restClient
                .execute(RequestHeaders.builder(HttpMethod.POST, booksPath)
                                       .contentType(MediaType.JSON)
                                       .add(HttpHeaderNames.COOKIE, "issue=1234").build(),
                         HttpData.of(StandardCharsets.UTF_8,
                                     Book.ofHtmlForm(
                                             "isbn=1-9999-9999-8&author=Jane%20Austen&" +
                                             "title=Sense%20and%20Sensibility&price=15.60")
                                         .toJsonString()))
                .aggregate()
                .join();
        assertThat(post2.status()).isEqualTo(HttpStatus.NO_CONTENT);

        final String postBookPath = "/resteasy/app/books/1-9999-9999-9";
        final AggregatedHttpResponse getPost = restClient
                .get(postBookPath)
                .aggregate()
                .join();
        assertThat(getPost.status()).isEqualTo(HttpStatus.OK);
        assertThat(getPost.contentType()).isEqualTo(MediaType.JSON);
        final String postBookEntry = getPost.contentUtf8();
        assertThat(postBookEntry).isNotEmpty();
        logger.info("{} responded with {}", postBookPath, postBookEntry);
        final Book bookGetPost = JSON.readValue(postBookEntry, Book.class);
        assertThat(bookGetPost.getTitle()).isEqualTo("Emma");
        assertThat(bookGetPost.getAuthor()).isEqualTo("Jane Austen");
        assertThat(bookGetPost.getPrice()).isEqualTo(15.50f);

        final AggregatedHttpResponse put = restClient
                .execute(RequestHeaders.builder(HttpMethod.PUT, postBookPath)
                                       .contentType(MediaType.FORM_DATA).build(),
                         "price=15.58", StandardCharsets.UTF_8)
                .aggregate()
                .join();
        assertThat(put.status()).isEqualTo(HttpStatus.NO_CONTENT);

        final AggregatedHttpResponse getPut = restClient
                .get(postBookPath)
                .aggregate()
                .join();
        assertThat(getPut.status()).isEqualTo(HttpStatus.OK);
        assertThat(getPut.contentType()).isEqualTo(MediaType.JSON);
        final String putBookEntry = getPut.contentUtf8();
        assertThat(putBookEntry).isNotEmpty();
        logger.info("{} responded with {}", postBookPath, putBookEntry);
        final Book bookGetPut = JSON.readValue(putBookEntry, Book.class);
        assertThat(bookGetPut.getTitle()).isEqualTo("Emma");
        assertThat(bookGetPut.getAuthor()).isEqualTo("Jane Austen");
        assertThat(bookGetPut.getPrice()).isEqualTo(15.58f);

        final AggregatedHttpResponse delete = restClient
                .delete(postBookPath)
                .aggregate()
                .join();
        assertThat(delete.status()).isEqualTo(HttpStatus.NO_CONTENT);

        final AggregatedHttpResponse getDelete = restClient
                .get(postBookPath)
                .aggregate()
                .join();
        assertThat(getDelete.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static WebClient newWebClient() {
        return WebClient.builder(restServer.httpUri())
                        .decorator(LoggingClient.builder()
                                                .logger(logger)
                                                .requestLogLevel(LogLevel.INFO)
                                                .successfulResponseLogLevel(LogLevel.INFO)
                                                .failureResponseLogLevel(LogLevel.WARN)
                                                .newDecorator())
                        .build();
    }
}
