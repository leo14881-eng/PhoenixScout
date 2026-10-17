package com.phoenixscout.http;

import java.time.Duration;
import java.util.Optional;

/** 接口响应缓存（两级：进程内 + SQLite）。实现见 store.SqliteApiCache；测试可用内存实现。 */
public interface ApiCache {

    Optional<String> get(String key, Duration ttl);

    void put(String key, String body);

    /** 最近 ttl 内该请求返回过 404 等“确定不存在”的状态，返回该状态码。 */
    Optional<Integer> negative(String key, Duration ttl);

    void putNegative(String key, int status);

    /** 带获取时间的缓存读取（P0 归档复用每日扫描已取过的同一请求时，需要记录原始请求时间）。 */
    default Optional<java.util.Map.Entry<String, java.time.Instant>> getWithTime(String key, Duration ttl) {
        return Optional.empty();
    }

    ApiCache NONE = new ApiCache() {
        public Optional<String> get(String key, Duration ttl) { return Optional.empty(); }
        public void put(String key, String body) { }
        public Optional<Integer> negative(String key, Duration ttl) { return Optional.empty(); }
        public void putNegative(String key, int status) { }
    };
}
