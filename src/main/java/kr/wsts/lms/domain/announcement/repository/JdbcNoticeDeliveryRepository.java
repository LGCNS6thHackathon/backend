package kr.wsts.lms.domain.announcement.repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.wsts.lms.domain.announcement.model.NoticeDelivery;
import kr.wsts.lms.global.support.Db;
import org.springframework.stereotype.Repository;

/** Persists the one-send reservation and its final Discord result. */
@Repository
public class JdbcNoticeDeliveryRepository implements NoticeDeliveryRepository {
    private static final String COLUMNS = "d.id,d.post_id,d.requested_by,d.channel,d.status,"
            + "d.idempotency_key,d.title_snapshot,d.summary_snapshot,d.public_url,"
            + "d.external_message_id,d.attempt_count,d.error_code,d.created_at,d.sent_at";
    private final Db db;

    public JdbcNoticeDeliveryRepository(Db db) {
        this.db = db;
    }

    @Override
    public Optional<NoticeDelivery> findByIdempotencyKey(String key) {
        return db.find("SELECT " + COLUMNS + " FROM notice_deliveries d WHERE d.idempotency_key=?", key)
                .map(this::map);
    }

    @Override
    public List<NoticeDelivery> findForNotice(long cohortId, long postId, int offset, int limit) {
        return db.list("SELECT " + COLUMNS + " FROM notice_deliveries d "
                + "JOIN posts p ON p.id=d.post_id WHERE p.cohort_id=? AND p.id=? "
                + "ORDER BY d.id DESC LIMIT ? OFFSET ?", cohortId, postId, limit, offset)
                .stream().map(this::map).toList();
    }

    @Override
    public List<NoticeDelivery> findPendingForWorker(int limit) {
        return db.list("SELECT " + COLUMNS + " FROM notice_deliveries d "
                + "WHERE d.status='PENDING' ORDER BY d.id LIMIT ?", limit)
                .stream().map(this::map).toList();
    }

    @Override
    public NoticeDelivery insert(NoticeDelivery delivery) {
        long id = db.insert("INSERT INTO notice_deliveries(post_id,requested_by,channel,status,"
                + "idempotency_key,title_snapshot,summary_snapshot,public_url,attempt_count,created_at) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?)", delivery.postId(), delivery.requestedBy(),
                delivery.channel().name(), delivery.status().name(), delivery.idempotencyKey(),
                delivery.titleSnapshot(), delivery.summarySnapshot(), delivery.publicUrl(),
                delivery.attemptCount(), utc(delivery.createdAt()));
        return new NoticeDelivery(id, delivery.postId(), delivery.requestedBy(), delivery.channel(),
                delivery.status(), delivery.idempotencyKey(), delivery.titleSnapshot(),
                delivery.summarySnapshot(), delivery.publicUrl(), null, delivery.attemptCount(),
                null, delivery.createdAt(), null);
    }

    @Override
    public boolean updateIfPending(NoticeDelivery delivery) {
        return db.update("UPDATE notice_deliveries SET status=?,external_message_id=?,error_code=?,sent_at=? "
                + "WHERE id=? AND status='PENDING' AND attempt_count=?", delivery.status().name(),
                delivery.externalMessageId(), delivery.errorCode(),
                delivery.sentAt() == null ? null : utc(delivery.sentAt()),
                delivery.id(), delivery.attemptCount()) == 1;
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private NoticeDelivery map(Map<String, Object> row) {
        Object sentAt = row.get("sentAt");
        return new NoticeDelivery(Db.id(row, "id"), Db.id(row, "postId"),
                Db.id(row, "requestedBy"), NoticeDelivery.Channel.valueOf((String) row.get("channel")),
                NoticeDelivery.Status.valueOf((String) row.get("status")),
                (String) row.get("idempotencyKey"), (String) row.get("titleSnapshot"),
                (String) row.get("summarySnapshot"), (String) row.get("publicUrl"),
                (String) row.get("externalMessageId"), ((Number) row.get("attemptCount")).intValue(),
                (String) row.get("errorCode"), Instant.parse((String) row.get("createdAt")),
                sentAt == null ? null : Instant.parse((String) sentAt));
    }
}
