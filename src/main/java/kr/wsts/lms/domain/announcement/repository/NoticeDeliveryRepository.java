package kr.wsts.lms.domain.announcement.repository;

import java.util.List;
import java.util.Optional;
import kr.wsts.lms.domain.announcement.model.NoticeDelivery;

/**
 * Discord delivery-log persistence contract only; no network transmission.
 * Store message snapshots and provider IDs, never the webhook secret.
 * A unique idempotency key must prevent duplicate dispatch requests.
 */
public interface NoticeDeliveryRepository {
    Optional<NoticeDelivery> findByIdempotencyKey(String idempotencyKey);
    List<NoticeDelivery> findForNotice(long cohortId, long postId, int offset, int limit);
    List<NoticeDelivery> findPendingForWorker(int limit);
    NoticeDelivery insert(NoticeDelivery delivery);
    boolean updateIfPending(NoticeDelivery delivery);
}
