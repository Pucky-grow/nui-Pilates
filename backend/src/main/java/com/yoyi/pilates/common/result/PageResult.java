package com.yoyi.pilates.common.result;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.function.Function;

/**
 * 统一分页响应.
 */
@Data
public class PageResult<T> implements Serializable {

    private List<T> list;
    private long total;
    private long page;
    private long size;

    public static <T> PageResult<T> of(List<T> list, long total, long page, long size) {
        PageResult<T> r = new PageResult<>();
        r.setList(list);
        r.setTotal(total);
        r.setPage(page);
        r.setSize(size);
        return r;
    }

    /** MyBatis-Plus 分页对象转换, 同时对记录做 DTO 映射. */
    public static <E, T> PageResult<T> of(IPage<E> page, Function<E, T> mapper) {
        return of(page.getRecords().stream().map(mapper).toList(),
                page.getTotal(), page.getCurrent(), page.getSize());
    }
}
