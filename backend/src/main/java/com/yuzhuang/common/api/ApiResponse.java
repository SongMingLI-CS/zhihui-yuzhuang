package com.yuzhuang.common.api;

import com.yuzhuang.common.enums.ResultCode;
import com.yuzhuang.common.trace.TraceContext;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

/**
 * 统一响应包裹（字段命名/语义严格对齐 docs/api-spec.yaml 的 ApiResponse）：
 * {@code code / message / data / timestamp(毫秒) / requestId}。
 *
 * @param <T> data 载荷类型
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务状态码（00000 代表成功） */
    private String code;

    /** 响应或错误信息 */
    private String message;

    /** 响应具体载荷（可为 null） */
    private T data;

    /** 毫秒级时间戳 */
    private long timestamp;

    /** 链路追踪 ID */
    private String requestId;

    /** 成功响应（带 data）。 */
    public static <T> ApiResponse<T> success(T data) {
        return build(ResultCode.SUCCESS, data);
    }

    /** 成功响应（无 data）。 */
    public static ApiResponse<Void> success() {
        return success(null);
    }

    /** 业务失败响应（按 ResultCode）。 */
    public static <T> ApiResponse<T> fail(ResultCode resultCode) {
        return fail(resultCode.getCode(), resultCode.getMessage());
    }

    /** 业务失败响应（自定义 code + message）。 */
    public static <T> ApiResponse<T> fail(String code, String message) {
        return new ApiResponse<>(code, message, null, System.currentTimeMillis(), TraceContext.getRequestId());
    }

    private static <T> ApiResponse<T> build(ResultCode resultCode, T data) {
        return new ApiResponse<>(
                resultCode.getCode(),
                resultCode.getMessage(),
                data,
                System.currentTimeMillis(),
                TraceContext.getRequestId());
    }
}
