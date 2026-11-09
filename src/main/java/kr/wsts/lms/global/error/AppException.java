package kr.wsts.lms.global.error;

public class AppException extends RuntimeException {
    private final int status;
    private final String code;
    public AppException(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    public int status() { return status; }
    public String code() { return code; }
    public static AppException bad(String message) { return new AppException(400, "INVALID_REQUEST", message); }
    public static AppException notFound() { return new AppException(404, "NOT_FOUND", "대상을 찾을 수 없습니다."); }
    public static AppException forbidden() { return new AppException(403, "FORBIDDEN", "접근 권한이 없습니다."); }
    public static AppException conflict(String message) { return new AppException(409, "CONFLICT", message); }
}
