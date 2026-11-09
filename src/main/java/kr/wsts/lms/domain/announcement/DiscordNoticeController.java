package kr.wsts.lms.domain.announcement;

import java.util.LinkedHashMap;
import java.util.Map;
import kr.wsts.lms.domain.announcement.model.NoticeDelivery;
import kr.wsts.lms.global.support.Actor;
import kr.wsts.lms.global.support.Db;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class DiscordNoticeController {
    private final Actor actor;
    private final Db db;
    private final DiscordNoticeService discord;

    public DiscordNoticeController(Actor actor, Db db, DiscordNoticeService discord) {
        this.actor = actor;
        this.db = db;
        this.discord = discord;
    }

    // A separate button action is the only operation that sends to Discord.
    @PostMapping("/posts/{postId}/discord-delivery")
    public DeliveryView send(@PathVariable long postId) {
        actor.requireAdmin();
        return DeliveryView.from(discord.send(postId, actor.id()));
    }

    @GetMapping("/posts/{postId}/discord-delivery")
    public DeliveryView result(@PathVariable long postId) {
        actor.requireAdmin();
        return DeliveryView.from(discord.find(postId));
    }

    // The existing post-list query stays untouched; the frontend merges these statuses by post ID.
    @GetMapping("/cohorts/{cohortId}/discord-deliveries")
    public Map<Long, String> statuses(@PathVariable long cohortId) {
        actor.requireAdmin();
        actor.cohort(cohortId);
        Map<Long, String> statuses = new LinkedHashMap<>();
        for (var row : db.list("SELECT d.post_id,d.status,d.error_code FROM notice_deliveries d "
                + "JOIN posts p ON p.id=d.post_id WHERE p.cohort_id=? AND p.deleted_at IS NULL "
                + "AND d.idempotency_key=CONCAT('discord-post-',p.id)", cohortId)) {
            statuses.put(Db.id(row, "postId"), "UNCERTAIN".equals(row.get("errorCode"))
                    ? "UNKNOWN" : (String) row.get("status"));
        }
        return statuses;
    }

    public record DeliveryView(long id, long postId, String status,
            String externalMessageId, String errorCode) {
        static DeliveryView from(NoticeDelivery delivery) {
            return new DeliveryView(delivery.id(), delivery.postId(), delivery.status().name(),
                    delivery.externalMessageId(), delivery.errorCode());
        }
    }
}
