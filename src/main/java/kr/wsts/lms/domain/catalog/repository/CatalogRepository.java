package kr.wsts.lms.domain.catalog.repository;

import java.util.List;
import java.util.Optional;
import kr.wsts.lms.domain.catalog.model.Cohort;
import kr.wsts.lms.domain.catalog.model.Course;
import kr.wsts.lms.domain.catalog.model.Enrollment;

/** Persistence contract; student reads must be restricted to active enrollment. */
public interface CatalogRepository {
    List<Course> findCoursesForUser(long userId, int offset, int limit);
    Optional<Course> findCourseForUser(long courseId, long userId);
    List<Cohort> findCohortsForUser(long userId, int offset, int limit);
    Optional<Cohort> findCohortForUser(long cohortId, long userId);
    Optional<Enrollment> findEnrollment(long cohortId, long userId);
    List<Enrollment> findEnrollmentsByCohort(long cohortId, int offset, int limit);
    Course saveCourse(Course course);
    Cohort saveCohort(Cohort cohort);
    Enrollment saveEnrollment(Enrollment enrollment);
}
