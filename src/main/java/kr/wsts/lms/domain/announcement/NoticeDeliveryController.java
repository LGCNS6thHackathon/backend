package kr.wsts.lms.domain.announcement;

import kr.wsts.lms.domain.announcement.model.NoticeDelivery;
import kr.wsts.lms.global.support.Actor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Provisional admin API; only this route needs adjustment to the future UI. */
@RestController
@RequestMapping("/api/posts/{postId}/discord-delivery")
public class NoticeDeliveryController {
    private final Actor actor;
    private final DiscordNoticeService discord;

    public NoticeDeliveryController(Actor actor, DiscordNoticeService discord) {
        this.actor = actor;
        this.discord = discord;
    }

    @PostMapping
    public DeliveryView send(@PathVariable long postId) {
        actor.requireAdmin();
        return DeliveryView.from(discord.send(postId, actor.id()));
    }

    @GetMapping
    public DeliveryView result(@PathVariable long postId) {
        actor.requireAdmin();
        return DeliveryView.from(discord.find(postId));
    }

    public record DeliveryView(long id, long postId, String status,
            String externalMessageId, String errorCode) {
        static DeliveryView from(NoticeDelivery delivery) {
            return new DeliveryView(delivery.id(), delivery.postId(), delivery.status().name(),
                    delivery.externalMessageId(), delivery.errorCode());
        }
    }
}
