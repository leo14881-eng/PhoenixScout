package com.phoenixscout.market;

/** 接口失败：调用方应标记“数据缺失”，不得据此判定项目不合格。 */
public class ApiException extends Exception {
    private final int status;

    public ApiException(String message, int status) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
