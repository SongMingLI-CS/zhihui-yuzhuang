package com.yuzhuang.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yuzhuang.audit.service.AuditService;
import com.yuzhuang.auth.context.AuthContext;
import com.yuzhuang.auth.context.AuthPrincipal;
import com.yuzhuang.auth.enums.UserRole;
import com.yuzhuang.common.enums.ResultCode;
import com.yuzhuang.common.exception.BusinessException;
import com.yuzhuang.inventory.entity.ProductSku;
import com.yuzhuang.inventory.mapper.ProductSkuMapper;
import com.yuzhuang.product.dto.ProductProfileRequest;
import com.yuzhuang.product.dto.ProductSkuResponse;
import com.yuzhuang.product.dto.ProductStatusRequest;
import com.yuzhuang.product.dto.ProductUpsertRequest;
import com.yuzhuang.product.service.ProductAdminService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 商品管理写接口实现。
 *
 * <p>状态契约与读接口一致：在售 = {@code ON_SALE}、下架 = {@code OFF_SHELF}。
 * 描述与占位图地址沿用读接口的派生/预置逻辑，保证读写返回结构一致。
 *
 * <p>租户作用域（对齐 docs/api-spec.yaml 与 audit-verification.md §3.4）：
 * 编辑/上下架目标必须归属操作者租户；{@code global} 共享目录仅 VILLAGE（村委/运营）
 * 可维护；跨租户一律 404 + A1004（不暴露资源存在性）。
 */
@Service
public class ProductAdminServiceImpl implements ProductAdminService {

    private static final String DEFAULT_TENANT_ID = "global";
    private static final String STATUS_ON_SALE = "ON_SALE";
    private static final String STATUS_OFF_SHELF = "OFF_SHELF";
    private static final String IMAGE_URL_PREFIX = "https://cdn.yuzhuang.example/specialty/";
    private static final String IMAGE_URL_SUFFIX = ".jpg";
    private static final String DESCRIPTION_PREFIX = "于庄原产地直发 · ";
    private static final String DESCRIPTION_SUFFIX = "，传统工艺，地道风味";

    private final ProductSkuMapper productSkuMapper;
    private final AuditService auditService;

    public ProductAdminServiceImpl(ProductSkuMapper productSkuMapper, AuditService auditService) {
        this.productSkuMapper = productSkuMapper;
        this.auditService = auditService;
    }

    @Override
    public ProductSkuResponse createProduct(ProductUpsertRequest request, String tenantId) {
        String tenant = normalizeTenantId(tenantId);
        String status = normalizeStatus(request.getStatus());

        Long exists = productSkuMapper.selectCount(new LambdaQueryWrapper<ProductSku>()
                .eq(ProductSku::getTenantId, tenant)
                .eq(ProductSku::getSkuCode, request.getSkuCode().trim()));
        if (exists > 0) {
            throw new BusinessException(ResultCode.SKU_CODE_CONFLICT);
        }

        ProductSku sku = ProductSku.builder()
                .tenantId(tenant)
                .skuCode(request.getSkuCode().trim())
                .spuName(request.getSpuName().trim())
                .price(request.getPrice())
                .stock(request.getStock())
                .version(0)
                .status(status)
                .stockAlert(0)
                .createdBy(AuthContext.get() == null ? null : AuthContext.get().getUsername())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        try {
            productSkuMapper.insert(sku);
        } catch (DuplicateKeyException ex) {
            // 并发下唯一约束兜底
            throw new BusinessException(ResultCode.SKU_CODE_CONFLICT);
        }
        auditService.record("PRODUCT_CREATE", "PRODUCT", sku.getSkuCode(),
                "创建商品 tenant=" + tenant + " status=" + status, true);
        return toResponse(sku);
    }

    @Override
    public ProductSkuResponse updateProduct(Long id, ProductUpsertRequest request, String tenantId) {
        ProductSku sku = getOrThrow(id, tenantId);
        sku.setSkuCode(request.getSkuCode().trim());
        sku.setSpuName(request.getSpuName().trim());
        sku.setPrice(request.getPrice());
        sku.setStock(request.getStock());
        sku.setStatus(normalizeStatus(request.getStatus()));
        try {
            int updated = productSkuMapper.update(sku,
                    new LambdaUpdateWrapper<ProductSku>().eq(ProductSku::getId, id));
            if (updated != 1) {
                throw new BusinessException(ResultCode.NOT_FOUND, "商品不存在或已被移除");
            }
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ResultCode.SKU_CODE_CONFLICT);
        }
        auditService.record("PRODUCT_UPDATE", "PRODUCT", String.valueOf(id),
                "编辑商品 " + request.getSpuName() + "（价格/库存/状态）", true);
        return toResponse(sku);
    }

    @Override
    public ProductSkuResponse updateStatus(Long id, ProductStatusRequest request, String tenantId) {
        ProductSku sku = getOrThrow(id, tenantId);
        sku.setStatus(normalizeStatus(request.getStatus()));
        int updated = productSkuMapper.update(sku,
                new LambdaUpdateWrapper<ProductSku>().eq(ProductSku::getId, id));
        if (updated != 1) {
            throw new BusinessException(ResultCode.NOT_FOUND, "商品不存在或已被移除");
        }
        auditService.record("PRODUCT_STATUS", "PRODUCT", String.valueOf(id),
                "商品状态切换为 " + sku.getStatus(), true);
        return toResponse(sku);
    }

    @Override
    public ProductSkuResponse updateProfile(Long id, ProductProfileRequest request, String tenantId) {
        ProductSku sku = getOrThrow(id, tenantId);
        ProductSku update = new ProductSku();
        update.setId(id);
        // 仅在请求显式给出字段时更新（null = 不修改）
        if (request.getCategory() != null) {
            update.setCategory(request.getCategory().trim());
        }
        if (request.getUnit() != null) {
            update.setUnit(request.getUnit().trim());
        }
        if (request.getOrigin() != null) {
            update.setOrigin(request.getOrigin().trim());
        }
        if (request.getDetail() != null) {
            update.setDetail(request.getDetail().trim());
        }
        if (request.getStockAlert() != null) {
            update.setStockAlert(request.getStockAlert());
        }
        update.setUpdatedAt(LocalDateTime.now());
        int updated = productSkuMapper.update(update,
                new LambdaUpdateWrapper<ProductSku>().eq(ProductSku::getId, id));
        if (updated != 1) {
            throw new BusinessException(ResultCode.NOT_FOUND, "商品不存在或已被移除");
        }
        auditService.record("PRODUCT_PROFILE", "PRODUCT", String.valueOf(id),
                "更新商品扩展资料（分类/单位/产地/详情/库存预警阈值）", true);
        return toResponse(productSkuMapper.selectById(id));
    }

    private ProductSku getOrThrow(Long id, String tenantId) {
        if (id == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "商品 ID 不能为空");
        }
        String operatorTenant = normalizeTenantId(tenantId);
        ProductSku sku = productSkuMapper.selectById(id);
        // 跨租户/不存在统一 404，避免暴露资源存在性
        if (sku == null || !canManage(sku, operatorTenant)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "商品不存在");
        }
        return sku;
    }

    /**
     * 商品写作用域：目标归属操作者租户；{@code global} 共享目录仅 VILLAGE 可维护。
     */
    private boolean canManage(ProductSku sku, String operatorTenant) {
        if (sku.getTenantId().equals(operatorTenant)) {
            return true;
        }
        if (DEFAULT_TENANT_ID.equals(sku.getTenantId())) {
            AuthPrincipal principal = AuthContext.get();
            return principal != null && UserRole.VILLAGE.name().equals(principal.getRole());
        }
        return false;
    }

    private String normalizeTenantId(String tenantId) {
        return (tenantId == null || tenantId.isBlank()) ? DEFAULT_TENANT_ID : tenantId.trim();
    }

    /** 状态规整：空默认在售；接受 DRAFT / ON_SALE / OFF_SHELF / ARCHIVED（阶段 C 新增草稿与归档）。 */
    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return STATUS_ON_SALE;
        }
        String s = status.trim().toUpperCase();
        if (STATUS_ON_SALE.equals(s) || STATUS_OFF_SHELF.equals(s)
                || "DRAFT".equals(s) || "ARCHIVED".equals(s)) {
            return s;
        }
        throw new BusinessException(ResultCode.PARAM_ERROR,
                "商品状态仅支持 DRAFT / ON_SALE / OFF_SHELF / ARCHIVED");
    }

    private ProductSkuResponse toResponse(ProductSku sku) {
        return ProductSkuResponse.builder()
                .id(sku.getId())
                .skuCode(sku.getSkuCode())
                .spuName(sku.getSpuName())
                .price(sku.getPrice())
                .stock(sku.getStock())
                .status(sku.getStatus())
                .tenantId(sku.getTenantId())
                .description(DESCRIPTION_PREFIX + sku.getSpuName() + DESCRIPTION_SUFFIX)
                .imageUrl(IMAGE_URL_PREFIX + sku.getSkuCode() + IMAGE_URL_SUFFIX)
                .category(sku.getCategory())
                .unit(sku.getUnit())
                .origin(sku.getOrigin())
                .detail(sku.getDetail())
                .stockAlert(sku.getStockAlert())
                .lowStock(isLowStock(sku))
                .createdBy(sku.getCreatedBy())
                .updatedAt(sku.getUpdatedAt() == null ? null : sku.getUpdatedAt().toString())
                .build();
    }

    /** 是否库存预警：阈值 > 0 且库存 <= 阈值，且商品未归档。 */
    public static boolean isLowStock(ProductSku sku) {
        Integer alert = sku.getStockAlert();
        if (alert == null || alert <= 0) {
            return false;
        }
        if ("ARCHIVED".equals(sku.getStatus())) {
            return false;
        }
        return sku.getStock() != null && sku.getStock() <= alert;
    }
}
