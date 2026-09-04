package com.yuzhuang.common.exception;

import com.yuzhuang.common.enums.ResultCode;

/**
 * 业务异常（非受检）。
 *
 * <p>携带 {@link ResultCode} 业务码或自定义 message；
 * 由 {@code GlobalExceptionHandler} 统一转换为 {@code ApiResponse}。
 */
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 业务状态码 */
    private final String code;

    public BusinessException(ResultCode resultCode) {
        this(resultCode, resultCode.getMessage());
    }

    /** 保留 resultCode 的码，但覆盖提示文案。 */
    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
    }

    /** 自定义业务码 + 文案。 */
    public BusinessException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** 仅自定义文案：默认按系统错误 C5001 兜底。 */
    public BusinessException(String message) {
        this(ResultCode.SYSTEM_ERROR.getCode(), message);
    }

    public String getCode() {
        return code;
    }
}
