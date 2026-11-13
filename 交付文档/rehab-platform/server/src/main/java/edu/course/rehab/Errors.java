package edu.course.rehab;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.*;
import org.springframework.dao.DataIntegrityViolationException;
import static edu.course.rehab.RehabService.map;

@RestControllerAdvice
public class Errors {
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> known(ResponseStatusException e){return ResponseEntity.status(e.getStatus()).body(map("message",e.getReason(),"status",e.getStatus().value()));}
    @ExceptionHandler(DataIntegrityViolationException.class) public ResponseEntity<?> constraint(DataIntegrityViolationException e){return ResponseEntity.status(409).body(map("message","数据约束校验失败，请检查重复记录、关联对象和参数范围。"));}
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class}) public ResponseEntity<?> input(Exception e){return ResponseEntity.badRequest().body(map("message","请求数据格式不正确"));}
    @ExceptionHandler(Exception.class) public ResponseEntity<?> unexpected(Exception e){org.slf4j.LoggerFactory.getLogger(Errors.class).error("Request failed",e);return ResponseEntity.status(500).body(map("message","服务处理失败，请查看 Java 后端日志。"));}
}
