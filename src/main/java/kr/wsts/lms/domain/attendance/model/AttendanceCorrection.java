package kr.wsts.lms.domain.attendance.model;

import java.time.Instant;

/**
 * JDBC domain state for {@code attendance_corrections}.
 * originalStatus/originalVersion snapshot the record at request time.
 * Legacy rows may lack snapshots; new approval services must reject stale requests.
 * Timestamp components use UTC instants.
 * Persistence mapping and business operations are implemented by the feature owner.
 */
public record AttendanceCorrection(
        Long id,
        Long attendanceId,
        Long enrollmentId,
        RequestedStatus requestedStatus,
        String reason,
        Status status,
        Long reviewedBy,
        String reviewNote,
        Instant createdAt,
        Instant reviewedAt,
        AttendanceRecord.Status originalStatus,
        Integer originalVersion
) {
    public enum RequestedStatus {
        PRESENT, LATE, EARLY, ABSENT, EXCUSED
    }

    public enum Status {
        PENDING, APPROVED, REJECTED, INVALIDATED
    }
}
