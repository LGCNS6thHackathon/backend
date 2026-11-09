package kr.wsts.lms.domain.lecture;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import kr.wsts.lms.global.error.AppException;

final class YouTubeLink {
    private YouTubeLink() {}

    static String videoId(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) throw new IllegalArgumentException();
            String host = uri.getHost();
            String id;
            if ("youtu.be".equalsIgnoreCase(host)) {
                id = uri.getPath().substring(1);
            } else if (("youtube.com".equalsIgnoreCase(host) || "www.youtube.com".equalsIgnoreCase(host))
                    && "/watch".equals(uri.getPath())) {
                var ids = new ArrayList<String>();
                if (uri.getRawQuery() != null) {
                    for (String entry : uri.getRawQuery().split("&")) {
                        String[] pair = entry.split("=", 2);
                        if ("v".equals(URLDecoder.decode(pair[0], StandardCharsets.UTF_8))) {
                            ids.add(pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
                        }
                    }
                }
                if (ids.size() != 1) throw new IllegalArgumentException();
                id = ids.get(0);
            } else throw new IllegalArgumentException();
            if (!id.matches("[A-Za-z0-9_-]{11}")) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            throw AppException.bad("youtubeUrl은 유효한 HTTPS youtube.com/watch?v=… 또는 youtu.be/… 주소여야 합니다.");
        }
    }
}
