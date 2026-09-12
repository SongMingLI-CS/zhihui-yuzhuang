package com.yuzhuang.auth.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuzhuang.auth.entity.User;
import com.yuzhuang.auth.enums.UserRole;
import com.yuzhuang.auth.mapper.UserMapper;
import com.yuzhuang.auth.security.PasswordEncoder;
import com.yuzhuang.gov.entity.GovScope;
import com.yuzhuang.gov.mapper.GovScopeMapper;
import com.yuzhuang.tenant.entity.Tenant;
import com.yuzhuang.tenant.mapper.TenantMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 认证演示数据初始化器（启动时幂等插入演示租户 + 演示账号）。
 *
 * <p><b>生产安全边界</b>：本类仅在 {@code yuzhuang.demo.enabled=true}（即显式
 * {@code DEMO_MODE=true} / demo·dev·test profile）时注册为 Bean。生产环境由
 * {@code ProductionSafetyValidator} 拒绝 {@code DEMO_MODE=true}，因此生产启动
 * <b>绝不会</b>自举任何固定账号或演示租户。
 *
 * <p>密码哈希由 {@link PasswordEncoder} 动态生成（含随机盐），
 * 避免在 SQL 种子中硬编码哈希；重复启动通过“存在即跳过”保证幂等。
 * 演示账号在首次登录时被要求改密（{@code must_change_password=true}）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "yuzhuang.demo.enabled", havingValue = "true")
public class AuthDataInitializer implements ApplicationRunner {

    private final TenantMapper tenantMapper;
    private final UserMapper userMapper;
    private final GovScopeMapper govScopeMapper;

    public AuthDataInitializer(TenantMapper tenantMapper, UserMapper userMapper,
                               GovScopeMapper govScopeMapper) {
        this.tenantMapper = tenantMapper;
        this.userMapper = userMapper;
        this.govScopeMapper = govScopeMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedTenant("tenant_yuzhuang_001", "鹿邑试量镇于庄村股份经济合作社", "河南省周口市鹿邑县试量镇", "COOPERATIVE", "鹿邑县");
        seedTenant("tenant_platform_000", "智汇于庄平台管理租户", "河南省周口市", "PLATFORM", "周口市");
        seedUser("tenant_yuzhuang_001", "admin", "admin123", "于庄村委管理员", UserRole.VILLAGE, "13800000000");
        seedUser("tenant_yuzhuang_001", "coop001", "coop123", "于庄合作社运营", UserRole.COOPERATIVE, "13800000001");
        seedUser("tenant_yuzhuang_001", "farmer001", "farmer123", "于庄农户代表", UserRole.FARMER, "13800000002");
        seedUser("tenant_yuzhuang_001", "gov001", "gov12345", "鹿邑县农业农村局（只读）", UserRole.GOVERNMENT, "13800000003");
        seedUser("tenant_platform_000", "platform001", "platform123", "平台管理员", UserRole.PLATFORM_ADMIN, "13800000004");
        seedGovScope("gov001", "REGION", "鹿邑县");
        log.warn("[auth-init] 已创建演示账号（admin/coop001/farmer001/gov001/platform001，首次登录需改密）。"
                + "该行为仅允许在 demo/dev/test 环境，生产由启动安全校验拦截。");
    }

    private void seedTenant(String tenantId, String name, String region) {
        seedTenant(tenantId, name, region, "VILLAGE", null);
    }

    private void seedTenant(String tenantId, String name, String region,
                            String tenantType, String parentRegion) {
        Tenant existing = tenantMapper.selectOne(new LambdaQueryWrapper<Tenant>()
                .eq(Tenant::getTenantId, tenantId));
        if (existing != null) {
            return;
        }
        tenantMapper.insert(Tenant.builder()
                .tenantId(tenantId)
                .name(name)
                .region(region)
                .tenantType(tenantType)
                .parentRegion(parentRegion)
                .status("ACTIVE")
                .createdAt(LocalDateTime.now())
                .build());
    }

    /** 为政府演示账号授权区域只读范围（REGION 鹿邑县 → 命中该区域租户）。 */
    private void seedGovScope(String username, String scopeType, String scopeValue) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username));
        if (user == null) {
            return;
        }
        Long exists = govScopeMapper.selectCount(new LambdaQueryWrapper<GovScope>()
                .eq(GovScope::getUserId, user.getId())
                .eq(GovScope::getScopeType, scopeType)
                .eq(GovScope::getScopeValue, scopeValue));
        if (exists > 0) {
            return;
        }
        govScopeMapper.insert(GovScope.builder()
                .tenantId("global")
                .userId(user.getId())
                .scopeType(scopeType)
                .scopeValue(scopeValue)
                .grantedBy("system-demo-seed")
                .createdAt(LocalDateTime.now())
                .build());
    }

    private void seedUser(String tenantId, String username, String rawPassword,
                          String displayName, UserRole role, String phone) {
        User existing = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username));
        if (existing != null) {
            return;
        }
        userMapper.insert(User.builder()
                .tenantId(tenantId)
                .username(username)
                .passwordHash(PasswordEncoder.encode(rawPassword))
                .displayName(displayName)
                .role(role)
                .phone(phone)
                .status("ACTIVE")
                // 演示账号首次登录必须改密，避免公开演示口令长期有效。
                .mustChangePassword(true)
                .failedLoginCount(0)
                .createdAt(LocalDateTime.now())
                .build());
    }
}
