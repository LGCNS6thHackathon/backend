package kr.wsts.lms.domain.attendance.model;

import java.time.Instant;
import java.time.LocalDate;

/**
 * JDBC domain state for {@code attendance_records}.
 * DATE uses sessionNo 0; SESSION uses a positive sessionNo.
 * Future mutation adapters must increment version and hide deleted records.
 * Timestamp components use UTC instants.
 * Persistence mapping and business operations are implemented by the feature owner.
 */
public record AttendanceRecord(
        Long id,
        Long enrollmentId,
        LocalDate attendanceDate,
        Status status,
        Instant checkIn,
        Instant checkOut,
        String note,
        Instant updatedAt,
        UnitType unitType,
        Integer sessionNo,
        Integer version,
        Instant deletedAt
) {
    public enum Status {
        PRESENT, LATE, EARLY, ABSENT, EXCUSED, LEAVE
    }

    public enum UnitType {
        DATE, SESSION
    }
}
