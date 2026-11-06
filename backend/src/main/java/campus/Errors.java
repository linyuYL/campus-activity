package campus;

import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class Errors {
    @ExceptionHandler(ApiError.class)
    ResponseEntity<?> api(ApiError e) { return response(e.status,e.code,e.getMessage()); }
    @ExceptionHandler({IllegalArgumentException.class,java.time.DateTimeException.class,HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid(Exception e) { return response(400,"VALIDATION_FAILED","请检查参数格式、字段长度与时间"); }
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<?> duplicate(Exception e) { return response(409,"INVALID_STATE","资源或操作已存在，请刷新后重试"); }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<?> database(Exception e) { return response(503,"TEMPORARILY_UNAVAILABLE","数据服务暂时不可用，请查询结果后重试"); }
    static ResponseEntity<?> response(int status,String code,String message) {
        var builder=ResponseEntity.status(status);
        if (status==429 || status==503) builder.header("Retry-After","1");
        return builder.body(Map.of("code",code,"message",message,"requestId",UUID.randomUUID().toString(),"details",List.of()));
    }
}
