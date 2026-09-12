package com.yuzhuang.gov.controller;

import com.yuzhuang.audit.service.AuditService;
import com.yuzhuang.auth.context.AuthContext;
import com.yuzhuang.common.api.ApiResponse;
import com.yuzhuang.gov.dto.GovSummaryResponse;
import com.yuzhuang.gov.service.GovScopeService;
import com.yuzhuang.gov.service.GovSummaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 政府治理接口（阶段 D，只读）。
 *
 * <p>仅 {@code GOVERNMENT}（按授权范围）与 {@code PLATFORM_ADMIN} 可访问，由
 * {@code EndpointSecurityPolicy} 的 {@code /api/v1/gov/**} 规则强制。
 * <b>不含</b>商品编辑、库存修改、订单履约、知识删除等写操作。
 * 导出操作写审计日志（GOV_EXPORT）。
 */
@Slf4j
@Tag(name = "政府治理", description = "授权范围只读聚合与合规导出（GOVERNMENT / PLATFORM_ADMIN）")
@RestController
@RequestMapping("/api/v1/gov")
public class GovController {

    private static final DateTimeFormatter CSV_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final GovSummaryService govSummaryService;
    private final GovScopeService govScopeService;
    private final AuditService auditService;

    public GovController(GovSummaryService govSummaryService, GovScopeService govScopeService,
                         AuditService auditService) {
        this.govSummaryService = govSummaryService;
        this.govScopeService = govScopeService;
        this.auditService = auditService;
    }

    @Operation(summary = "授权范围经营聚合（含快照时间、范围与口径元数据）")
    @GetMapping("/summary")
    public ApiResponse<GovSummaryResponse> summary() {
        log.debug("[gov] summary");
        return ApiResponse.success(govSummaryService.summary(AuthContext.require()));
    }

    @Operation(summary = "查询当前账号的可治理范围（供端上明示授权边界）")
    @GetMapping("/scope")
    public ApiResponse<Map<String, Object>> scope() {
        GovScopeService.ScopeResolution scope = govScopeService.resolve(AuthContext.require());
        return ApiResponse.success(Map.of(
                "all", scope.all(),
                "tenantIds", scope.tenantIds(),
                "description", scope.description()));
    }

    @Operation(summary = "导出授权范围聚合为 CSV（含导出审计）")
    @GetMapping(value = "/export", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(value = "scope", defaultValue = "all") String exportScope) {
        GovSummaryResponse summary = govSummaryService.summary(AuthContext.require());
        StringBuilder csv = new StringBuilder();
        // UTF-8 BOM：便于 Excel 正确识别中文
        csv.append('\uFEFF');
        csv.append("范围类型,授权租户数,范围说明,快照时间,口径版本\n");
        csv.append(escape(summary.getScope().isAll() ? "全域授权" : "指定范围")).append(',')
                .append(summary.getScope().getTenantCount()).append(',')
                .append(escape(summary.getScope().getDescription())).append(',')
                .append(CSV_TS.format(LocalDateTime.now())).append(',')
                .append(escape(summary.getMetrics().getVersion())).append('\n');

        csv.append("\n合计指标,数值\n");
        csv.append("累计订单量,").append(summary.getTotals().getTotalOrders()).append('\n');
        csv.append("累计交易额(元),").append(summary.getTotals().getTotalSales()).append('\n');
        csv.append("今日订单量,").append(summary.getTotals().getTodayOrders()).append('\n');
        csv.append("今日交易额(元),").append(summary.getTotals().getTodaySales()).append('\n');
        csv.append("待支付锁定,").append(summary.getTotals().getPendingPayOrders()).append('\n');
        csv.append("待出库订单,").append(summary.getTotals().getReadyShipOrders()).append('\n');

        csv.append("\n租户,属地,累计订单,累计交易额(元),今日订单\n");
        for (GovSummaryResponse.TenantBreakdown row : summary.getBreakdown()) {
            csv.append(escape(row.getTenantName())).append(',')
                    .append(escape(row.getRegion())).append(',')
                    .append(row.getTotalOrders()).append(',')
                    .append(row.getTotalSales()).append(',')
                    .append(row.getTodayOrders()).append('\n');
        }

        byte[] body = csv.toString().getBytes(StandardCharsets.UTF_8);
        auditService.record("GOV_EXPORT", "GOV_SUMMARY", summary.getScope().getDescription(),
                "导出 CSV，scope=" + exportScope + "，租户数=" + summary.getScope().getTenantCount()
                        + "，字节数=" + body.length, true);
        String filename = "gov-summary-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    /** CSV 字段转义（含逗号/引号/换行时加引号并转义内部引号）。 */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        String v = value.replace("\"", "\"\"");
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v + "\"";
        }
        return v;
    }
}

