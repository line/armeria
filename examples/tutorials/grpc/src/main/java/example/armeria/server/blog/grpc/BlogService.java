package example.armeria.server.blog.grpc;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.google.protobuf.Empty;

import example.armeria.server.blog.grpc.Blog.BlogPost;
import example.armeria.server.blog.grpc.Blog.CreateBlogPostRequest;
import example.armeria.server.blog.grpc.Blog.DeleteBlogPostRequest;
import example.armeria.server.blog.grpc.Blog.GetBlogPostRequest;
import example.armeria.server.blog.grpc.Blog.ListBlogPostsRequest;
import example.armeria.server.blog.grpc.Blog.ListBlogPostsResponse;
import example.armeria.server.blog.grpc.Blog.UpdateBlogPostRequest;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

final class BlogService extends BlogServiceGrpc.BlogServiceImplBase {
    private final AtomicInteger idGenerator = new AtomicInteger();
    private final Map<Integer, BlogPost> blogPosts = new ConcurrentHashMap<>();

    /**
     * Creates a new {@link BlogPost} and returns the stored {@link BlogPost} containing automatically
     * generated ID and creation time.
     */
    @Override
    public void createBlogPost(CreateBlogPostRequest request, StreamObserver<BlogPost> responseObserver) {
        final int id = idGenerator.getAndIncrement();
        final Instant now = Instant.now();
        final BlogPost updated = BlogPost.newBuilder()
                                         .setId(id)
                                         .setTitle(request.getTitle())
                                         .setContent(request.getContent())
                                         .setModifiedAt(now.toEpochMilli())
                                         .setCreatedAt(now.toEpochMilli())
                                         .build();
        blogPosts.put(id, updated);
        final BlogPost stored = updated;
        responseObserver.onNext(stored);
        responseObserver.onCompleted();
    }

    /**
     * Retrieves a {@link BlogPost} whose {@link BlogPost#getId()} is the {@link GetBlogPostRequest#getId()}.
     */
    @Override
    public void getBlogPost(GetBlogPostRequest request, StreamObserver<BlogPost> responseObserver) {
        final BlogPost blogPost = blogPosts.get(request.getId());
        if (blogPost == null) {
            throw new BlogNotFoundException("The blog post does not exist. ID: " + request.getId());
        } else {
            responseObserver.onNext(blogPost);
            responseObserver.onCompleted();
        }
    }

    @Override
    public void listBlogPosts(ListBlogPostsRequest request,
                              StreamObserver<ListBlogPostsResponse> responseObserver) {
        final Collection<BlogPost> blogPosts;
        if ("desc".equals(request.getOrderBy())) {
            blogPosts = this.blogPosts.entrySet()
                                      .stream()
                                      .sorted(Collections.reverseOrder(Comparator.comparingInt(Entry::getKey)))
                                      .map(Entry::getValue).collect(Collectors.toList());
        } else {
            blogPosts = this.blogPosts.values();
        }
        responseObserver.onNext(ListBlogPostsResponse.newBuilder().addAllBlogs(blogPosts).build());
        responseObserver.onCompleted();
    }

    @Override
    public void updateBlogPost(UpdateBlogPostRequest request, StreamObserver<BlogPost> responseObserver) {
        final BlogPost oldBlogPost = blogPosts.get(request.getId());
        if (oldBlogPost == null) {
            responseObserver.onError(
                    Status.NOT_FOUND.withDescription("The blog post does not exist. ID: " + request.getId())
                                    .asRuntimeException());
        } else {
            final BlogPost newBlogPost = oldBlogPost.toBuilder()
                                                    .setTitle(request.getTitle())
                                                    .setContent(request.getContent())
                                                    .setModifiedAt(Instant.now().toEpochMilli())
                                                    .build();
            blogPosts.put(request.getId(), newBlogPost);
            responseObserver.onNext(newBlogPost);
            responseObserver.onCompleted();
        }
    }

    @Override
    public void deleteBlogPost(DeleteBlogPostRequest request, StreamObserver<Empty> responseObserver) {
        final BlogPost removed = blogPosts.remove(request.getId());
        if (removed == null) {
            responseObserver.onError(
                    Status.NOT_FOUND.withDescription("The blog post does not exist. ID: " + request.getId())
                                    .asRuntimeException());
        } else {
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        }
    }
}
