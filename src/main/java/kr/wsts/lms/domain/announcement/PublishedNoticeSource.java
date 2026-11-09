package kr.wsts.lms.domain.announcement;

import java.net.URI;
import java.util.Map;
import kr.wsts.lms.global.error.AppException;
import kr.wsts.lms.global.support.Db;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Temporary boundary to the community owner's publication model and public URL. */
@Component
public class PublishedNoticeSource {
    private final Db db;
    private final String publicBaseUrl;

    public PublishedNoticeSource(Db db,
            @Value("${app.discord.public-base-url:}") String publicBaseUrl) {
        this.db = db;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
        if (!this.publicBaseUrl.isBlank()) {
            URI uri = URI.create(this.publicBaseUrl);
            boolean localHttp = "http".equals(uri.getScheme())
                    && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
            if (!("https".equals(uri.getScheme()) || localHttp) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
                throw new IllegalArgumentException("DISCORD_PUBLIC_BASE_URL must be an HTTPS origin or localhost");
            }
        }
    }

    public PublishedNotice find(long postId) {
        if (postId < 1) throw AppException.bad("공지 ID를 확인해주세요.");
        if (publicBaseUrl.isBlank()) {
            throw new AppException(503, "PUBLIC_URL_NOT_CONFIGURED", "공개 공지 주소가 설정되지 않았습니다.");
        }
        // Replace only this query and URL path when the community owner's API is ready.
        Map<String, Object> row = db.find("SELECT id,title,body,public_slug FROM posts "
                + "WHERE id=? AND category IN ('COURSE','SYSTEM') AND visibility='PUBLIC' "
                + "AND public_slug IS NOT NULL AND deleted_at IS NULL", postId)
                .orElseThrow(() -> AppException.conflict("게시된 공개 공지만 디스코드로 보낼 수 있습니다."));
        String slug = (String) row.get("publicSlug");
        if (!slug.matches("[0-9a-f-]{36}")) {
            throw AppException.conflict("공개 공지 링크가 준비되지 않았습니다.");
        }
        return new PublishedNotice(Db.id(row, "id"), (String) row.get("title"),
                (String) row.get("body"), publicBaseUrl + "/public/notices/" + slug);
    }

    public record PublishedNotice(long id, String title, String body, String publicUrl) {}
}
