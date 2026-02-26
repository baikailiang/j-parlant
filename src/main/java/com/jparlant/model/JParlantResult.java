package com.jparlant.model;

import lombok.Data;

@Data
public class JParlantResult<T> {

    private Integer code;
    private String message;
    private T data;

    public JParlantResult() {}

    public JParlantResult(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> JParlantResult<T> success(T data) {
        return new JParlantResult<>(200, "操作成功", data);
    }

    public static <T> JParlantResult<T> error(String message) {
        return new JParlantResult<>(500, message, null);
    }
}
