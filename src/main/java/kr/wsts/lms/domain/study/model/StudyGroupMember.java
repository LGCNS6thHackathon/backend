package kr.wsts.lms.domain.study.model;

import java.time.Instant;

/**
 * JDBC domain state for {@code study_group_members}.
 * Timestamp components use UTC instants.
 * Persistence mapping and business operations are implemented by the feature owner.
 */
public record StudyGroupMember(
        Long id,
        Long groupId,
        Long userId,
        Instant joinedAt
) {
}
