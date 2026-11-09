package kr.wsts.lms.domain.lecture;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * YouTube URL rules from the feature spec: only {@code youtube.com/watch?v=…} and {@code youtu.be/…} are accepted.
 * Playback links carry the start position instead of splitting the video file.
 */
public final class YouTubeLinks {
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    private YouTubeLinks() {
    }

    /** Extracts the 11-character video id from a supported URL, or empty when the format is not supported. */
    public static Optional<String> videoId(String url) {
        if (url == null || url.isBlank()) return Optional.empty();
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) return Optional.empty();
        host = host.toLowerCase();
        String candidate = null;
        if (host.equals("youtu.be")) {
            String path = uri.getPath() == null ? "" : uri.getPath();
            candidate = path.startsWith("/") ? path.substring(1) : path;
        } else if ((host.equals("youtube.com") || host.equals("www.youtube.com") || host.equals("m.youtube.com"))
                && "/watch".equals(uri.getPath())) {
            candidate = queryValue(uri.getRawQuery(), "v");
        }
        return candidate != null && VIDEO_ID.matcher(candidate).matches() ? Optional.of(candidate) : Optional.empty();
    }

    public static boolean validVideoId(String videoId) {
        return videoId != null && VIDEO_ID.matcher(videoId).matches();
    }

    /** iframe src. autoplay=0: browsers may block autoplay, so the user presses play. */
    public static String embedUrl(String videoId, int startSeconds) {
        String url = "https://www.youtube.com/embed/" + videoId + "?autoplay=0&rel=0";
        return startSeconds > 0 ? url + "&start=" + startSeconds : url;
    }

    /** Fallback link that opens YouTube directly at the same position. */
    public static String watchUrl(String videoId, int startSeconds) {
        String url = "https://www.youtube.com/watch?v=" + videoId;
        return startSeconds > 0 ? url + "&t=" + startSeconds + "s" : url;
    }

    private static String queryValue(String rawQuery, String key) {
        if (rawQuery == null) return null;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) return pair.substring(eq + 1);
        }
        return null;
    }
}
