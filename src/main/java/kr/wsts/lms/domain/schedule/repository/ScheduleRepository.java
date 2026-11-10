package kr.wsts.lms.domain.schedule.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.wsts.lms.domain.schedule.model.ScheduleEvent;

/** Cohort-scoped persistence contract; administrator authorization belongs to the service. */
public interface ScheduleRepository {
    List<ScheduleEvent> findOverlappingEvents(long cohortId, Instant fromInclusive, Instant toExclusive);
    Optional<ScheduleEvent> findEvent(long cohortId, long eventId);
    ScheduleEvent saveEvent(ScheduleEvent event);
    boolean deleteEvent(long cohortId, long eventId);
}
