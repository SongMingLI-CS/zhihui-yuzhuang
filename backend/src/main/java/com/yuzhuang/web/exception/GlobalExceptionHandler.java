package com.yuzhuang.web.exception;

import com.yuzhuang.common.api.ApiResponse;
import com.yuzhuang.common.enums.ResultCode;
import com.yuzhuang.common.exception.BusinessException;
import com.yuzhuang.common.trace.TraceContext;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 全局异常处理器：将各类异常统一转换为契约 {@link ApiResponse}。
 *
 * <ul>
 *   <li>{@link BusinessException} → 按 ResultCode 映射 HTTP 状态与业务码；</li>
 *   <li>参数/请求体校验异常 → HTTP 400 + A1001；</li>
 *   <li>资源不存在 → HTTP 404 + A1004；</li>
 *   <li>其余未知异常 → HTTP 500 + C5001（系统兜底）。</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常（code 未命中枚举时按 HTTP 200 返回业务码，交由前端按 code 处理）。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        ResultCode rc = ResultCode.ofCode(ex.getCode());
        HttpStatus status = (rc != null) ? rc.getHttpStatus() : HttpStatus.OK;
        log.warn("[business] code={}, message={}, requestId={}",
                ex.getCode(), ex.getMessage(), TraceContext.getRequestId());
        return ResponseEntity.status(status).body(ApiResponse.fail(ex.getCode(), ex.getMessage()));
    }

    /** @RequestBody + @Valid 校验失败。 */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<ApiResponse<Void>> handleBindException(BindException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.joining("; "));
        String message = detail.isBlank() ? ResultCode.PARAM_ERROR.getMessage() : "参数校验失败: " + detail;
        log.warn("[param] message={}, requestId={}", message, TraceContext.getRequestId());
        return error(ResultCode.PARAM_ERROR, HttpStatus.BAD_REQUEST, message);
    }

    /** 其他请求参数/请求体类异常。 */
    @ExceptionHandler({
            ConstraintViolationException.class,
            HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class,
            MissingPathVariableException.class,
            MethodArgumentTypeMismatchException.class,
            HttpRequestMethodNotSupportedException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleRequestError(Exception ex) {
        log.warn("[param] type={}, message={}, requestId={}",
                ex.getClass().getSimpleName(), ex.getMessage(), TraceContext.getRequestId());
        return error(ResultCode.PARAM_ERROR, HttpStatus.BAD_REQUEST, null);
    }

    /** 资源/接口不存在。 */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleNotFound(Exception ex) {
        log.warn("[not-found] type={}, requestId={}", ex.getClass().getSimpleName(), TraceContext.getRequestId());
        return error(ResultCode.NOT_FOUND, HttpStatus.NOT_FOUND, "接口不存在或资源未找到");
    }

    /** 系统兜底。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception ex) {
        log.error("[system] unexpected error, requestId={}", TraceContext.getRequestId(), ex);
        return error(ResultCode.SYSTEM_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, null);
    }

    private ResponseEntity<ApiResponse<Void>> error(ResultCode rc, HttpStatus status, String message) {
        String finalMessage = (message == null || message.isBlank()) ? rc.getMessage() : message;
        return ResponseEntity.status(status).body(ApiResponse.fail(rc.getCode(), finalMessage));
    }
}
