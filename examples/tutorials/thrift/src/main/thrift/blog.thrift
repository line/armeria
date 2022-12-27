namespace java example.armeria.blog.thrift

/**
 * A request object for creating a blog post.
 */
struct CreateBlogPostRequest {
    1: string title;
    2: string content;
}

/**
 * A request object for getting a blog post.
 */
struct GetBlogPostRequest {
    1: i32 id;
}

/**
 * A request object for fetching a list of blog posts.
 */
struct ListBlogPostsRequest {
    1: bool descending;
}

/**
 * A response object containing a list of blog posts.
 */
struct ListBlogPostsResponse {
    1: list<BlogPost> blogs;
}

/**
 * A request object for updating a blog post.
 */
struct UpdateBlogPostRequest {
    1: i32 id;
    2: string title;
    3: string content;
}

/**
 * A request object for deleting a blog post.
 */
struct DeleteBlogPostRequest {
    1: i32 id;
}

/**
 * An object which represents a blog post.
 */
struct BlogPost {
    1: i32 id;
    2: string title;
    3: string content;
    4: i64 createdAt;
    5: i64 modifiedAt;
}

/**
 * An exception which is thrown when a blog is not found.
 */
exception BlogNotFoundException {
    1: string reason
}

/**
 * My first BlogService in Armeria!
 */
service BlogService {

    /**
     * Creates a blog post.
     */
    BlogPost createBlogPost(1:CreateBlogPostRequest request),

    /**
     * Gets a blog post.
     */
    BlogPost getBlogPost(1:GetBlogPostRequest request) throws (1:BlogNotFoundException e),

    /**
     * Retrieves a list of blog posts.
     */
    ListBlogPostsResponse listBlogPosts(1:ListBlogPostsRequest request),

    /**
     * Updates a blog post.
     */
    BlogPost updateBlogPost(1:UpdateBlogPostRequest request) throws (1:BlogNotFoundException e),

    /**
     * Deletes a blog post.
     */
    void deleteBlogPost(1:DeleteBlogPostRequest request) throws (1:BlogNotFoundException e),
}
