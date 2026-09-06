package com.yuzhuang.common.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.List;

/**
 * 通用分页响应包裹（字段对齐 docs/api-spec.yaml PageResult 约定）：
 * {@code items / page / pageSize / total / totalPages}。
 *
 * @param <T> 列表元素类型
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 当前页数据 */
    private List<T> items;

    /** 当前页码（从 1 开始） */
    private int page;

    /** 每页条数 */
    private int pageSize;

    /** 总条数 */
    private long total;

    /** 总页数 */
    private int totalPages;
}
