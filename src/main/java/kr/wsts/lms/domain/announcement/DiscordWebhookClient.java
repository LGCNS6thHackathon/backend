package kr.wsts.lms.domain.announcement;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Sends one complete notice message without exposing the server's webhook secret. */
@Component
public class DiscordWebhookClient {
    private final URI endpoint;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    public DiscordWebhookClient(@Value("${DISCORD_WEBHOOK_URL:}") String webhookUrl) {
        if (webhookUrl.isBlank()) {
            endpoint = null;
            return;
        }
        try {
            URI uri = URI.create(webhookUrl);
            if (!"https".equals(uri.getScheme()) || !"discord.com".equals(uri.getHost())
                    || uri.getPort() != -1 || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || !uri.getPath().matches("/api/webhooks/[0-9]+/[^/]+")) {
                throw new IllegalArgumentException();
            }
            endpoint = URI.create(webhookUrl + "?wait=true");
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("DISCORD_WEBHOOK_URL must be a Discord webhook URL");
        }
    }

    DiscordWebhookClient(URI endpoint) {
        this.endpoint = endpoint;
    }

    public boolean isConfigured() {
        return endpoint != null;
    }

    public DeliveryResult send(String content) {
        if (endpoint == null) return new DeliveryResult(false, null, "NOT_CONFIGURED");
        String body = json.writeValueAsString(Map.of(
                "content", content,
                "allowed_mentions", Map.of("parse", List.of())
        ));
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                String id = json.readTree(response.body()).path("id").asText();
                return id.matches("[0-9]{1,100}")
                        ? new DeliveryResult(true, id, null)
                        : new DeliveryResult(false, null, "UNCERTAIN");
            }
            return new DeliveryResult(false, null, status >= 500 ? "UNCERTAIN" : "REJECTED");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new DeliveryResult(false, null, "UNCERTAIN");
        } catch (IOException e) {
            return new DeliveryResult(false, null, "UNCERTAIN");
        }
    }

    public record DeliveryResult(boolean sent, String messageId, String errorCode) {}
}
