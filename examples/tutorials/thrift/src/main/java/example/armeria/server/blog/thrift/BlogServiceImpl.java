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
import example.armeria.blog.thrift.BlogService;
import example.armeria.blog.thrift.CreateBlogPostRequest;
import example.armeria.blog.thrift.DeleteBlogPostRequest;
import example.armeria.blog.thrift.GetBlogPostRequest;
import example.armeria.blog.thrift.ListBlogPostsRequest;
import example.armeria.blog.thrift.ListBlogPostsResponse;
import example.armeria.blog.thrift.UpdateBlogPostRequest;

public class BlogServiceImpl implements BlogService.AsyncIface {

    private final AtomicInteger idGenerator = new AtomicInteger();
    private final Map<Integer, BlogPost> blogPosts = new ConcurrentHashMap<>();

    @Override
    public void createBlogPost(CreateBlogPostRequest request, AsyncMethodCallback<BlogPost> resultHandler)
            throws TException {
        final int id = idGenerator.getAndIncrement();
        final Instant now = Instant.now();
        final BlogPost blogPost = new BlogPost()
                .setId(id)
                .setTitle(request.getTitle())
                .setContent(request.getContent())
                .setModifiedAt(now.toEpochMilli())
                .setCreatedAt(now.toEpochMilli());
        blogPosts.put(id, blogPost);
        final BlogPost stored = blogPost;
        resultHandler.onComplete(stored);
    }

    @Override
    public void getBlogPost(GetBlogPostRequest request, AsyncMethodCallback<BlogPost> resultHandler)
            throws TException {
        final BlogPost blogPost = blogPosts.get(request.getId());
        if (blogPost == null) {
            // throwing an exception will also have the same effect
            // throw new BlogNotFoundException("The blog post does not exist. ID: " + request.getId());
            resultHandler.onError(
                    new BlogNotFoundException("The blog post does not exist. ID: " + request.getId()));
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
            resultHandler.onError(
                    new BlogNotFoundException("The blog post does not exist. ID: " + request.getId()));
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
            resultHandler.onError(
                    new IllegalArgumentException("The blog post does not exist. ID: " + request.getId()));
        } else {
            resultHandler.onComplete(null);
        }
    }
}
