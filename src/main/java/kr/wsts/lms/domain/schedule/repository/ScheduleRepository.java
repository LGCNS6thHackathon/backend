package kr.wsts.lms.domain.schedule.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.wsts.lms.domain.schedule.model.RoadmapModule;
import kr.wsts.lms.domain.schedule.model.RoadmapPhase;
import kr.wsts.lms.domain.schedule.model.ScheduleEvent;

/** Cohort-scoped persistence contract; staff authorization belongs to the service. */
public interface ScheduleRepository {
    List<ScheduleEvent> findOverlappingEvents(long cohortId, Instant fromInclusive, Instant toExclusive);
    Optional<ScheduleEvent> findEvent(long cohortId, long eventId);
    List<RoadmapPhase> findPhases(long cohortId);
    List<RoadmapModule> findModules(long cohortId, long phaseId);
    ScheduleEvent saveEvent(ScheduleEvent event);
    RoadmapPhase savePhase(RoadmapPhase phase);
    RoadmapModule saveModule(RoadmapModule module);
    boolean deleteEvent(long cohortId, long eventId);
}
