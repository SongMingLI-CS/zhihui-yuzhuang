package com.yuzhuang.common.enums;

import org.springframework.http.HttpStatus;

/**
 * 统一业务状态码（严格对齐 docs/api-spec.yaml 的 code 语义）。
 *
 * <p>码段约定：
 * <ul>
 *   <li>{@code 00000} 成功；</li>
 *   <li>{@code A1xxx} 访问/参数/鉴权类（HTTP 4xx）；</li>
 *   <li>{@code B2xxx} 交易/业务冲突类（HTTP 409）；</li>
 *   <li>{@code C5xxx} 系统/依赖类（HTTP 5xx）。</li>
 * </ul>
 */
public enum ResultCode {

    /** 操作成功 */
    SUCCESS("00000", "操作成功", HttpStatus.OK),

    // ==================== A1 访问 / 参数 / 鉴权 ====================
    /** 请求参数错误或校验未通过 */
    PARAM_ERROR("A1001", "请求参数错误", HttpStatus.BAD_REQUEST),
    /** 未登录或登录状态已过期 */
    UNAUTHORIZED("A1002", "未登录或登录已过期", HttpStatus.UNAUTHORIZED),
    /** 无权限访问该资源 */
    FORBIDDEN("A1003", "无权限访问该资源", HttpStatus.FORBIDDEN),
    /** 请求的资源不存在 */
    NOT_FOUND("A1004", "请求的资源不存在", HttpStatus.NOT_FOUND),
    /** 账号已锁定（连续登录失败） */
    ACCOUNT_LOCKED("A1005", "账号已锁定，请稍后再试", HttpStatus.LOCKED),
    /** 密码不符合安全策略 */
    PASSWORD_POLICY_VIOLATION("A1006", "新密码不符合安全策略", HttpStatus.BAD_REQUEST),

    // ==================== B2 交易 / 业务冲突 ====================
    /** 库存不足（spec 409 示例：B2001） */
    INVENTORY_STOCK_OUT("B2001", "抱歉，该批次特产库存不足", HttpStatus.CONFLICT),
    /** 幂等冲突：订单处理中，请勿重复提交 */
    IDEMPOTENT_CONFLICT("B2002", "订单处理中，请勿重复提交", HttpStatus.CONFLICT),
    /** 订单状态已被并发变更 */
    ORDER_STATE_CONFLICT("B2003", "订单状态已更新，请刷新后重试", HttpStatus.CONFLICT),
    /** SKU 编码重复 */
    SKU_CODE_CONFLICT("B2004", "SKU 编码已存在", HttpStatus.CONFLICT),

    // ==================== C5 系统 / 依赖 ====================
    /** 系统内部错误兜底 */
    SYSTEM_ERROR("C5001", "系统繁忙，请稍后重试", HttpStatus.INTERNAL_SERVER_ERROR),
    /** 下游依赖服务暂不可用 */
    DEPENDENT_SERVICE_ERROR("C5002", "依赖服务暂不可用，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE),
    /** 渠道未配置（阶段 G：外部渠道未提供凭证时的明确状态） */
    CHANNEL_NOT_CONFIGURED("C5003", "该渠道尚未配置，暂不接收回调", HttpStatus.SERVICE_UNAVAILABLE),
    /** 渠道订单回流尚未实现（已配置凭证但接入未完成） */
    CHANNEL_INTAKE_NOT_IMPLEMENTED("C5004", "该渠道订单回流尚未实现", HttpStatus.NOT_IMPLEMENTED);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;

    ResultCode(String code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    /**
     * 按业务码反查枚举；未命中返回 {@code null}。
     *
     * @param code 业务状态码，如 B2001
     * @return 命中的 ResultCode
     */
    public static ResultCode ofCode(String code) {
        if (code == null) {
            return null;
        }
        for (ResultCode rc : values()) {
            if (rc.code.equals(code)) {
                return rc;
            }
        }
        return null;
    }
}
