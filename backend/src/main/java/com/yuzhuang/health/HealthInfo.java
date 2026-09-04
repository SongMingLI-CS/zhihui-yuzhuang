package com.yuzhuang.health;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 健康检查结果载荷。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class HealthInfo {

    public static final String STATUS_UP = "UP";
    public static final String STATUS_DOWN = "DOWN";

    /** 整体状态：UP / DOWN */
    private String status;

    /** 数据库连通状态：UP / DOWN */
    private String db;

    /** 是否整体健康（status 与 db 均为 UP）。 */
    public boolean healthy() {
        return STATUS_UP.equals(status) && STATUS_UP.equals(db);
    }
}
