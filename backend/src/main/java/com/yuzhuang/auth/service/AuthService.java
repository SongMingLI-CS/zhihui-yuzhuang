package com.yuzhuang.auth.service;

import com.yuzhuang.auth.context.AuthPrincipal;
import com.yuzhuang.auth.dto.AuthLoginRequest;
import com.yuzhuang.auth.dto.AuthLoginResponse;
import com.yuzhuang.auth.dto.ChangePasswordRequest;
import com.yuzhuang.auth.dto.UserInfoResponse;

/**
 * 认证服务。
 */
public interface AuthService {

    /**
     * 用户登录：校验用户名/密码，签发 JWT 并返回登录信息。
     *
     * @param request 登录请求
     * @return 登录响应（token / 用户信息 / 是否需改密）
     * @throws com.yuzhuang.common.exception.BusinessException 用户名或密码错误抛 A1002，账号停用抛 A1003，锁定抛 A1005
     */
    AuthLoginResponse login(AuthLoginRequest request);

    /**
     * 修改当前登录账号密码（含首次登录强制改密）。
     *
     * @param principal 已认证主体
     * @param request   当前密码 + 新密码
     * @throws com.yuzhuang.common.exception.BusinessException 当前密码错误 A1002；新密码不合规 A1006
     */
    void changePassword(AuthPrincipal principal, ChangePasswordRequest request);

    /**
     * 查询当前登录账号信息（用于 /auth/me 会话恢复）。
     *
     * @param principal 已认证主体
     * @return 用户信息
     */
    UserInfoResponse me(AuthPrincipal principal);
}

