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

package testing.resteasy.jaxrs.samples.books;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

import javax.ws.rs.core.Form;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.MoreObjects;
import com.google.common.base.Splitter;

public class Book {

    private String isbn;
    private String title;
    private String author;
    private Float price;

    public Book() {
    }

    public Book(String isbn, String title, String author, Float price) {
        this.isbn = isbn;
        this.title = title;
        this.author = author;
        this.price = price;
    }

    public String getIsbn() {
        return isbn;
    }

    public void setIsbn(String isbn) {
        this.isbn = isbn;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public Float getPrice() {
        return price;
    }

    public void setPrice(Float price) {
        this.price = price;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("isbn", isbn)
                          .add("title", title)
                          .add("author", author)
                          .add("price", price)
                          .toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Book)) {
            return false;
        }
        final Book book = (Book) o;
        return Objects.equals(isbn, book.isbn) && Objects.equals(title, book.title) &&
               Objects.equals(author, book.author) && Objects.equals(price, book.price);
    }

    @Override
    public int hashCode() {
        return Objects.hash(isbn, title, author, price);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    public String toJsonString() {
        try {
            return JSON.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public String toHtmlForm() {
        final StringBuilder builder = new StringBuilder();
        try {
            builder.append("isbn").append('=')
                   .append(URLEncoder.encode(isbn, StandardCharsets.UTF_8.name()));
            if (title != null) {
                builder.append('&').append("title").append('=')
                       .append(URLEncoder.encode(title, StandardCharsets.UTF_8.name()));
            }
            if (author != null) {
                builder.append('&').append("author").append('=')
                       .append(URLEncoder.encode(author, StandardCharsets.UTF_8.name()));
            }
            if (price != null) {
                builder.append('&').append("price").append('=')
                       .append(URLEncoder.encode(String.format("%f", price), StandardCharsets.UTF_8.name()));
            }
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e); // we should not get here
        }
        return builder.toString();
    }

    public Form toForm() {
        final Form form = new Form();
        form.param("isbn", isbn);
        form.param("title", title);
        form.param("author", author);
        form.param("price", String.format("%f", price));
        return form;
    }

    public static Book of(Map<String, String> fields) {
        final Book book = new Book();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            final String value;
            try {
                value = URLDecoder.decode(entry.getValue(), StandardCharsets.UTF_8.name());
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e); // we should not get here
            }
            switch (entry.getKey()) {
                case "isbn":
                    book.setIsbn(value);
                    break;
                case "title":
                    book.setTitle(value);
                    break;
                case "author":
                    book.setAuthor(value);
                    break;
                case "price":
                    book.setPrice(Float.parseFloat(value));
                    break;
            }
        }
        return book;
    }

    @SuppressWarnings("UnstableApiUsage")
    public static Book ofHtmlForm(String htmlForm) {
        final Map<String, String> fields = Splitter.on("&").withKeyValueSeparator("=").split(htmlForm);
        return of(fields);
    }

    public static Book ofJsonString(String json) {
        try {
            return JSON.readValue(json, Book.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
