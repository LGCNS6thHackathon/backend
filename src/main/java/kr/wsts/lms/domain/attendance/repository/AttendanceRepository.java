package kr.wsts.lms.domain.attendance.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import kr.wsts.lms.domain.attendance.model.AttendanceCorrection;
import kr.wsts.lms.domain.attendance.model.AttendanceRecord;

/**
 * Date/session-aware JDBC contract. Adapters must hide deleted records.
 * Approval must check original status/version, update the record conditionally,
 * and finish the pending correction in one service transaction.
 */
public interface AttendanceRepository {
    Optional<AttendanceRecord> findRecordForStudent(long recordId, long studentId);
    Optional<AttendanceRecord> findRecordForCohort(long cohortId, long recordId);
    Optional<AttendanceRecord> findRecord(long enrollmentId, LocalDate date,
            AttendanceRecord.UnitType unitType, int sessionNo);
    List<AttendanceRecord> findStudentRecords(long cohortId, long studentId, LocalDate from, LocalDate to);
    List<AttendanceRecord> findCohortRecords(long cohortId, LocalDate from, LocalDate to, int offset, int limit);
    AttendanceRecord insertRecord(AttendanceRecord record);
    boolean updateRecord(AttendanceRecord record, int expectedVersion);
    boolean softDeleteRecord(long cohortId, long recordId, int expectedVersion, Instant deletedAt);
    Optional<AttendanceCorrection> findCorrectionForStudent(long correctionId, long studentId);
    Optional<AttendanceCorrection> findPendingCorrectionForUpdate(long attendanceId);
    List<AttendanceCorrection> findPendingCorrections(long cohortId, int offset, int limit);
    AttendanceCorrection insertCorrection(AttendanceCorrection correction);
    boolean updateCorrectionIfPending(AttendanceCorrection correction);
}
