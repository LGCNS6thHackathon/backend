package kr.wsts.lms.global.error;

import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.ErrorResponse;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    public static Map<String,Object> error(String code, String message) { return Map.of("code",code,"message",message,"timestamp",Instant.now().toString()); }
    @ExceptionHandler(AppException.class)
    ResponseEntity<?> application(AppException e) { return ResponseEntity.status(e.status()).body(error(e.code(),e.getMessage())); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> integrity(DataIntegrityViolationException e) { return ResponseEntity.status(409).body(error("DATA_CONFLICT","중복된 값이거나 참조 중인 데이터입니다.")); }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> validation(Exception e) { return ResponseEntity.badRequest().body(error("INVALID_REQUEST","요청 형식과 입력 값을 확인해주세요.")); }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> tooLarge(Exception e) { return ResponseEntity.status(413).body(error("FILE_TOO_LARGE","첨부파일 또는 요청이 허용된 최대 크기를 초과했습니다.")); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception e) {
        if(e instanceof ErrorResponse response && response.getStatusCode().is4xxClientError())return ResponseEntity.status(response.getStatusCode()).body(error("INVALID_REQUEST","요청 경로, HTTP 메서드, 입력 형식과 필수 항목을 확인해주세요."));
        log.error("Unhandled API error",e); return ResponseEntity.internalServerError().body(error("INTERNAL_ERROR","요청을 처리하지 못했습니다."));
    }
}
