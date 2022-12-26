/*
 * Copyright 2022 LINE Corporation
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

package example.armeria.server.blog.thrift;

import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;

import example.armeria.blog.thrift.BlogNotFoundException;
import example.armeria.blog.thrift.BlogPost;
import example.armeria.blog.thrift.BlogService.AsyncIface;
import example.armeria.blog.thrift.CreateBlogPostRequest;
import example.armeria.blog.thrift.DeleteBlogPostRequest;
import example.armeria.blog.thrift.GetBlogPostRequest;
import example.armeria.blog.thrift.ListBlogPostsRequest;
import example.armeria.blog.thrift.ListBlogPostsResponse;
import example.armeria.blog.thrift.UpdateBlogPostRequest;

public class BlogServiceImpl implements AsyncIface {

    private final AtomicInteger idGenerator = new AtomicInteger();
    private final Map<Integer, BlogPost> blogPosts = new ConcurrentHashMap<>();

    @Override
    public void createBlogPost(CreateBlogPostRequest request, AsyncMethodCallback<BlogPost> resultHandler)
            throws TException {
        final int id = idGenerator.getAndIncrement();
        final Instant now = Instant.now();
        final BlogPost updated = new BlogPost()
                .setId(id)
                .setTitle(request.getTitle())
                .setContent(request.getContent())
                .setModifiedAt(now.toEpochMilli())
                .setCreatedAt(now.toEpochMilli());
        blogPosts.put(id, updated);
        final BlogPost stored = updated;
        resultHandler.onComplete(stored);
    }

    @Override
    public void getBlogPost(GetBlogPostRequest request, AsyncMethodCallback<BlogPost> resultHandler)
            throws TException {
        final BlogPost blogPost = blogPosts.get(request.getId());
        if (blogPost == null) {
            // throw new BlogNotFoundException("The blog post does not exist. ID: " + request.getId());
            resultHandler.onError(new BlogNotFoundException("The blog post does not exist. ID: " + request.getId()));
        } else {
            resultHandler.onComplete(blogPost);
        }
    }

    @Override
    public void listBlogPosts(ListBlogPostsRequest request,
                              AsyncMethodCallback<ListBlogPostsResponse> resultHandler) throws TException {
        final List<BlogPost> blogPosts;
        if (request.isDescending()) {
            blogPosts = this.blogPosts.entrySet()
                                      .stream()
                                      .sorted(Collections.reverseOrder(Comparator.comparingInt(Entry::getKey)))
                                      .map(Entry::getValue).collect(Collectors.toList());
        } else {
            blogPosts = this.blogPosts.values().stream().collect(Collectors.toList());
        }
        resultHandler.onComplete(new ListBlogPostsResponse().setBlogs(blogPosts));
    }

    @Override
    public void updateBlogPost(UpdateBlogPostRequest request, AsyncMethodCallback<BlogPost> resultHandler)
            throws TException {
        final BlogPost oldBlogPost = blogPosts.get(request.getId());
        if (oldBlogPost == null) {
            // throwing an exception will also have the same effect
            // throw new BlogNotFoundException("The blog post does not exist. ID: " + request.getId());
            resultHandler.onError(new BlogNotFoundException("The blog post does not exist. ID: " + request.getId()));
        } else {
            final BlogPost newBlogPost = oldBlogPost.deepCopy()
                                                    .setTitle(request.getTitle())
                                                    .setContent(request.getContent())
                                                    .setModifiedAt(Instant.now().toEpochMilli());
            blogPosts.put(request.getId(), newBlogPost);
            resultHandler.onComplete(newBlogPost);
        }
    }

    @Override
    public void deleteBlogPost(DeleteBlogPostRequest request, AsyncMethodCallback<Void> resultHandler)
            throws TException {
        final BlogPost removed = blogPosts.remove(request.getId());
        if (removed == null) {
            // throwing an exception will also have the same effect
            // throw new NullPointerException("The blog post does not exist. ID: " + request.getId());
            resultHandler.onError(new NullPointerException("The blog post does not exist. ID: " + request.getId()));
        } else {
            resultHandler.onComplete(null);
        }
    }
}
