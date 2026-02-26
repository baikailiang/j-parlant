package com.jparlant.utils;

import reactor.core.publisher.Mono;
import reactor.util.context.Context;

public class ReactiveUserContext {
    private static final String USER_ID_KEY = "userId";

    // 获取当前 Context 中的 userId (返回 Mono)
    public static Mono<String> getUserId() {
        return Mono.deferContextual(ctx -> {
            if (ctx.hasKey(USER_ID_KEY)) {
                return Mono.just(ctx.get(USER_ID_KEY));
            }
            return Mono.empty();
        });
    }

    // 将 userId 注入到 Context 中
    public static Context withUserId(String userId) {
        return Context.of(USER_ID_KEY, userId);
    }
}
