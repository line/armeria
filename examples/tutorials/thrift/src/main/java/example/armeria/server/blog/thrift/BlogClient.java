/*
 * Copyright 2023 LINE Corporation
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

import java.net.URI;
import java.util.List;

import org.apache.thrift.TException;

import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.client.thrift.ThriftClients;

import example.armeria.blog.thrift.BlogPost;
import example.armeria.blog.thrift.BlogService;
import example.armeria.blog.thrift.CreateBlogPostRequest;
import example.armeria.blog.thrift.DeleteBlogPostRequest;
import example.armeria.blog.thrift.GetBlogPostRequest;
import example.armeria.blog.thrift.ListBlogPostsRequest;
import example.armeria.blog.thrift.UpdateBlogPostRequest;

public final class BlogClient {

    public static void main(String[] args) throws Exception {
        final BlogClient client = new BlogClient(URI.create("http://127.0.0.1:8080"), "/thrift");
        // some operations...
    }

    private final BlogService.Iface blogService;

    BlogClient(URI uri, String path) {
        blogService = ThriftClients.builder(uri)
                                   .path(path)
                                   .decorator(LoggingClient.newDecorator())
                                   .build(BlogService.Iface.class);
    }

    BlogPost createBlogPost(String title, String content) throws TException {
        final CreateBlogPostRequest request =
                new CreateBlogPostRequest().setTitle(title)
                                           .setContent(content);
        return blogService.createBlogPost(request);
    }

    BlogPost getBlogPost(int id) throws TException {
        final GetBlogPostRequest request =
                new GetBlogPostRequest().setId(id);
        return blogService.getBlogPost(request);
    }

    List<BlogPost> listBlogPosts(boolean descending) throws TException {
        return blogService.listBlogPosts(new ListBlogPostsRequest().setDescending(descending))
                          .getBlogs();
    }

    BlogPost updateBlogPost(int id, String newTitle, String newContent) throws TException {
        final UpdateBlogPostRequest request = new UpdateBlogPostRequest().setId(id).setTitle(newTitle).setContent(newContent);
        return blogService.updateBlogPost(request);
    }

    void deleteBlogPost(int id) throws TException {
        final DeleteBlogPostRequest request = new DeleteBlogPostRequest().setId(id);
        blogService.deleteBlogPost(request);
    }
}
