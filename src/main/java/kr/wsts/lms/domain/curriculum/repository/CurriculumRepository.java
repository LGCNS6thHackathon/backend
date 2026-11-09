package kr.wsts.lms.domain.curriculum.repository;

import java.util.List;
import java.util.Optional;
import kr.wsts.lms.domain.curriculum.model.LearningSubject;
import kr.wsts.lms.domain.curriculum.model.LearningTopic;
import kr.wsts.lms.domain.curriculum.model.LearningUnit;

/** Subject -> unit -> topic hierarchy; reads traverse and verify cohort ownership. */
public interface CurriculumRepository {
    List<LearningSubject> findSubjects(long cohortId);
    List<LearningUnit> findUnits(long cohortId, long subjectId);
    List<LearningTopic> findTopics(long cohortId, long unitId);
    Optional<LearningTopic> findTopic(long cohortId, long topicId);
    LearningSubject saveSubject(LearningSubject subject);
    LearningUnit saveUnit(LearningUnit unit);
    LearningTopic saveTopic(LearningTopic topic);
}
