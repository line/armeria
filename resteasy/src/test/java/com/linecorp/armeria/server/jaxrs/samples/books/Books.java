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

package com.linecorp.armeria.server.jaxrs.samples.books;

import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import com.linecorp.armeria.common.annotation.Nullable;

public class Books {

    private final ConcurrentMap<String, Book> books = new ConcurrentHashMap<>();

    public void provision(Book... newBooks) {
        provision(Arrays.asList(newBooks));
    }

    public void provision(Iterable<Book> newBooks) {
        newBooks.forEach(book -> books.put(book.getIsbn(), book));
    }

    public Collection<Book> getAllBooks() {
        return books.values();
    }

    public Collection<Book> getBookBy(String author, @Nullable String title) {
        return getAllBooks().stream()
                            .filter(book ->
                                            book.getAuthor().equals(author) &&
                                            (title == null || book.getTitle().contains(title)))
                            .collect(Collectors.toList());
    }

    public Book getBook(String isbn) {
        return books.get(isbn);
    }

    public boolean addBook(Book book) {
        return books.putIfAbsent(book.getIsbn(), book) == null;
    }

    public Book updateBook(Book book) {
        // check book contains new data then update otherwise return
        return books.replace(book.getIsbn(), book);
    }

    public Book deleteBook(String isbn) {
        return books.remove(isbn);
    }
}
