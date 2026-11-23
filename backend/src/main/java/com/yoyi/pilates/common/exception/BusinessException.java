package com.yoyi.pilates.common.exception;

import com.yoyi.pilates.common.result.ResultCode;
import lombok.Getter;

/**
 * 业务异常: 抛出后由全局异常处理器转为统一响应体.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(ResultCode rc) {
        super(rc.getMessage());
        this.code = rc.getCode();
    }

    public BusinessException(ResultCode rc, String message) {
        super(message);
        this.code = rc.getCode();
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public static BusinessException of(ResultCode rc) {
        return new BusinessException(rc);
    }

    public static BusinessException of(ResultCode rc, String message) {
        return new BusinessException(rc, message);
    }
}
