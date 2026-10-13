package com.softitrend.office.adapters.http;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.ResponseEntity;
@RestControllerAdvice
public class SafeErrors {
 @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
 public ResponseEntity<ErrorResponse> http(org.springframework.web.server.ResponseStatusException error) {
  int status=error.getStatusCode().value();
  String code=status==413?"PAYLOAD_TOO_LARGE":status==415?"UNSUPPORTED_MEDIA_TYPE":"VALIDATION";
  return ResponseEntity.status(status).header("Cache-Control","no-store").body(new ErrorResponse(status,code,"Requête refusée"));
 }
 public record ErrorResponse(int status,String code,String message) {}
 @ExceptionHandler({IllegalArgumentException.class,java.time.DateTimeException.class})
 public ResponseEntity<ErrorResponse> invalid() { return ResponseEntity.badRequest().header("Cache-Control","no-store").body(new ErrorResponse(400,"VALIDATION","Requête invalide")); }
}
