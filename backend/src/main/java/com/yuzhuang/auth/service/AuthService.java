package com.yuzhuang.auth.service;

import com.yuzhuang.auth.dto.AuthLoginRequest;
import com.yuzhuang.auth.dto.AuthLoginResponse;

/**
 * 认证服务。
 */
public interface AuthService {

    /**
     * 用户登录：校验用户名/密码，签发 JWT 并返回登录信息。
     *
     * @param request 登录请求
     * @return 登录响应（token / 用户信息）
     * @throws com.yuzhuang.common.exception.BusinessException 用户名或密码错误抛 A1002，账号停用抛 A1003
     */
    AuthLoginResponse login(AuthLoginRequest request);
}
