package com.doenv.schema.service;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;

/**
 * 在 classpath（含 jar 内部）里挑基准文件。
 *
 * <p>现场部署形态是「一个 jar + 一份 application.properties」，基准 SQL 必须打进 jar，
 * 而 {@code Files.list} 读不了 jar 内的目录，所以走 Spring 的资源模式匹配。
 *
 * <p>挑选规则跟文件系统那条路一致：文件名最大的一份。基准文件名带日期
 * （{@code PG系统纯净库-2026-08-06.sql}），字典序最大即日期最新。
 */
final class ClasspathBaselines {

    private ClasspathBaselines() {
    }

    /**
     * @param dir        形如 {@code classpath:install/pg-sql}
     * @param namePrefix 文件名前缀，如 {@code PG系统纯净库-}
     * @return 最新的一份；一个都没有返回 null
     */
    static Resource newest(String dir, String namePrefix) throws IOException {
        String base = dir.substring("classpath:".length()).replaceAll("^/+|/+$", "");
        // classpath*: 才能同时命中 jar 内与目录形态
        String pattern = "classpath*:" + base + "/" + namePrefix + "*.sql";
        Resource[] found = new PathMatchingResourcePatternResolver().getResources(pattern);
        Optional<Resource> newest = Arrays.stream(found)
                .filter(Resource::exists)
                .max(Comparator.comparing(r -> r.getFilename() == null ? "" : r.getFilename()));
        return newest.orElse(null);
    }
}
