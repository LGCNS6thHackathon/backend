package kr.wsts.lms.domain.notification.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.wsts.lms.domain.notification.model.Notification;

/** All user-facing reads/writes must restrict by the notification owner ID. */
public interface NotificationRepository {
    List<Notification> findForUser(long userId, boolean unreadOnly, int offset, int limit);
    Optional<Notification> findForUser(long notificationId, long userId);
    long countUnread(long userId);
    Notification insert(Notification notification);
    boolean markRead(long notificationId, long userId, Instant readAt);
    int markAllRead(long userId, Instant readAt);
}
