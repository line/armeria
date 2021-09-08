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

import example.armeria.server.blog.grpc.Blog.BlogId;
import example.armeria.server.blog.grpc.Blog.BlogPost;
import example.armeria.server.blog.grpc.Blog.Sort;
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
    public void createBlogPost(BlogPost blogPost, StreamObserver<BlogPost> responseObserver) {
        final BlogPost stored = storeBlogPost(blogPost);
        responseObserver.onNext(stored);
        responseObserver.onCompleted();
    }

    /**
     * Retrieves a {@link BlogPost} whose {@link BlogPost#getId()} is the {@link BlogId#getId()}.
     */
    @Override
    public void getBlogPost(BlogId blogId, StreamObserver<BlogPost> responseObserver) {
        final BlogPost blogPost = blogPosts.get(blogId.getId());
        if (blogPost == null) {
            responseObserver.onError(
                    Status.NOT_FOUND.withDescription("The blog post does not exist. ID: " + blogId.getId())
                                    .asRuntimeException());
        } else {
            responseObserver.onNext(blogPost);
            responseObserver.onCompleted();
        }
    }

    @Override
    public void getBlogPosts(Sort sort, StreamObserver<BlogPost> responseObserver) {
        final Collection<BlogPost> blogPosts;
        if (sort.getDescending()) {
            blogPosts = this.blogPosts.entrySet()
                                      .stream()
                                      .sorted(Collections.reverseOrder(Comparator.comparingInt(Entry::getKey)))
                                      .map(Entry::getValue).collect(Collectors.toList());
        } else {
            blogPosts = this.blogPosts.values();
        }
        blogPosts.forEach(responseObserver::onNext);
        responseObserver.onCompleted();
    }

    @Override
    public void updateBlogPost(BlogPost blogPost, StreamObserver<BlogPost> responseObserver) {
        final BlogPost oldBlogPost = blogPosts.get(blogPost.getId());
        if (oldBlogPost == null) {
            responseObserver.onError(
                    Status.NOT_FOUND.withDescription("The blog post does not exist. ID: " + blogPost.getId())
                                    .asRuntimeException());
        } else {
            final BlogPost newBlogPost = oldBlogPost.toBuilder()
                                                    .setTitle(blogPost.getTitle())
                                                    .setContent(blogPost.getContent())
                                                    .setModifiedAt(Instant.now().toEpochMilli())
                                                    .build();
            blogPosts.put(blogPost.getId(), newBlogPost);
            responseObserver.onNext(newBlogPost);
            responseObserver.onCompleted();
        }
    }

    @Override
    public void deleteBlogPost(BlogId blogId, StreamObserver<BlogId> responseObserver) {
        final BlogPost removed = blogPosts.remove(blogId.getId());
        if (removed == null) {
            responseObserver.onError(
                    Status.NOT_FOUND.withDescription("The blog post does not exist. ID: " + blogId.getId())
                                    .asRuntimeException());
        } else {
            responseObserver.onNext(blogId);
            responseObserver.onCompleted();
        }
    }

    private BlogPost storeBlogPost(BlogPost request) {
        final int id = idGenerator.getAndIncrement();
        final Instant now = Instant.now();
        final BlogPost updated = request.toBuilder()
                                        .setId(id)
                                        .setModifiedAt(now.toEpochMilli())
                                        .setCreatedAt(now.toEpochMilli())
                                        .build();
        blogPosts.put(id, updated);
        return updated;
    }
}
