package kr.wsts.lms.domain.community.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.wsts.lms.domain.community.model.Comment;
import kr.wsts.lms.domain.community.model.Post;

/**
 * Notice/FAQ/question persistence contract. Normal reads exclude deleted rows.
 * Public reads must require PUBLIC visibility and a matching non-null slug.
 */
public interface CommunityRepository {
    List<Post> findPosts(long cohortId, Post.Category category, String search, int offset, int limit);
    Optional<Post> findPost(long cohortId, long postId);
    Optional<Post> findPublicNoticeBySlug(String publicSlug);
    List<Comment> findComments(long cohortId, long postId, int offset, int limit);
    Post savePost(Post post);
    Comment saveComment(Comment comment);
    boolean softDeletePost(long cohortId, long postId, Instant deletedAt);
    boolean softDeleteComment(long cohortId, long commentId, Instant deletedAt);
}
