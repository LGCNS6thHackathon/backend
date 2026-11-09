package kr.wsts.lms.domain.lecture;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class YouTubeLinksTests {
    @Test
    void acceptsOnlySpecFormats() {
        assertEquals(Optional.of("ygfJcpCTQNg"), YouTubeLinks.videoId("https://www.youtube.com/watch?v=ygfJcpCTQNg"));
        assertEquals(Optional.of("ygfJcpCTQNg"), YouTubeLinks.videoId("https://youtube.com/watch?list=x&v=ygfJcpCTQNg&t=10s"));
        assertEquals(Optional.of("ygfJcpCTQNg"), YouTubeLinks.videoId("https://youtu.be/ygfJcpCTQNg"));
        assertTrue(YouTubeLinks.videoId("https://www.youtube.com/embed/ygfJcpCTQNg").isEmpty());
        assertTrue(YouTubeLinks.videoId("https://vimeo.com/watch?v=ygfJcpCTQNg").isEmpty());
        assertTrue(YouTubeLinks.videoId("https://youtu.be/short").isEmpty());
        assertTrue(YouTubeLinks.videoId("javascript:alert(1)").isEmpty());
        assertTrue(YouTubeLinks.videoId(" ").isEmpty());
    }

    @Test
    void playbackLinksCarryStartOnlyWhenPositive() {
        assertEquals("https://www.youtube.com/embed/ygfJcpCTQNg?autoplay=0&rel=0&start=120", YouTubeLinks.embedUrl("ygfJcpCTQNg", 120));
        assertEquals("https://www.youtube.com/embed/ygfJcpCTQNg?autoplay=0&rel=0", YouTubeLinks.embedUrl("ygfJcpCTQNg", 0));
        assertEquals("https://www.youtube.com/watch?v=ygfJcpCTQNg&t=120s", YouTubeLinks.watchUrl("ygfJcpCTQNg", 120));
    }
}
