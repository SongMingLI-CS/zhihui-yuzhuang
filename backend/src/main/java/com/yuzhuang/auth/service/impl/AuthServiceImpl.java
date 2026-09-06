package com.yuzhuang.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuzhuang.auth.context.AuthPrincipal;
import com.yuzhuang.auth.dto.AuthLoginRequest;
import com.yuzhuang.auth.dto.AuthLoginResponse;
import com.yuzhuang.auth.dto.UserInfoResponse;
import com.yuzhuang.auth.entity.User;
import com.yuzhuang.auth.mapper.UserMapper;
import com.yuzhuang.auth.security.JwtService;
import com.yuzhuang.auth.security.PasswordEncoder;
import com.yuzhuang.auth.service.AuthService;
import com.yuzhuang.common.enums.ResultCode;
import com.yuzhuang.common.exception.BusinessException;
import org.springframework.stereotype.Service;

/**
 * 认证服务实现。
 *
 * <p>登录链路：按用户名查询 → 密码常量时间比对 → 状态校验 → 签发 JWT。
 * 用户名/密码错误统一返回 {@code A1002}（不区分用户不存在/密码错误，防用户名枚举）。
 */
@Service
public class AuthServiceImpl implements AuthService {

    private static final String ACTIVE = "ACTIVE";
    private static final String TOKEN_TYPE = "Bearer";

    private final UserMapper userMapper;
    private final JwtService jwtService;

    public AuthServiceImpl(UserMapper userMapper, JwtService jwtService) {
        this.userMapper = userMapper;
        this.jwtService = jwtService;
    }

    @Override
    public AuthLoginResponse login(AuthLoginRequest request) {
        String username = request.getUsername().trim();
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username));
        // 用户不存在与密码错误合并为同一错误，避免用户名枚举
        if (user == null || !PasswordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (!ACTIVE.equals(user.getStatus())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "账号已被停用，请联系管理员");
        }

        AuthPrincipal principal = AuthPrincipal.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .tenantId(user.getTenantId())
                .role(user.getRole().name())
                .displayName(user.getDisplayName())
                .build();
        String token = jwtService.generateToken(principal);

        return AuthLoginResponse.builder()
                .token(token)
                .tokenType(TOKEN_TYPE)
                .expiresIn(jwtService.getExpireSeconds())
                .user(toUserInfo(user))
                .build();
    }

    private UserInfoResponse toUserInfo(User user) {
        return UserInfoResponse.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .displayName(user.getDisplayName())
                .tenantId(user.getTenantId())
                .role(user.getRole().name())
                .build();
    }
}
