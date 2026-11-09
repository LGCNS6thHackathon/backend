package kr.wsts.lms.domain.study.repository;

import java.util.List;
import java.util.Optional;
import kr.wsts.lms.domain.study.model.StudyGroup;
import kr.wsts.lms.domain.study.model.StudyGroupMember;

/** Lock the group before capacity-sensitive membership changes in a transaction. */
public interface StudyGroupRepository {
    List<StudyGroup> findGroups(long cohortId, int offset, int limit);
    Optional<StudyGroup> findGroup(long cohortId, long groupId);
    Optional<StudyGroup> findGroupForUpdate(long cohortId, long groupId);
    List<StudyGroupMember> findMembers(long cohortId, long groupId);
    Optional<StudyGroupMember> findMembership(long groupId, long userId);
    long countMembers(long groupId);
    StudyGroup saveGroup(StudyGroup group);
    StudyGroupMember addMember(StudyGroupMember member);
    boolean removeMember(long groupId, long userId);
}
