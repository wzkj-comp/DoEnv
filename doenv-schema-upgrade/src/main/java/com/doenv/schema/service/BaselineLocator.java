package com.doenv.schema.service;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 基准文件的查找顺序。
 *
 * <p>基准会随发版更新，而 jar 里打包的那份是构建时的快照。如果只认 jar 内置的，
 * 换一次基准就得重新编译打包再分发一次 —— 对现场运维不合理。
 *
 * <p>所以查找顺序是：
 * <ol>
 *   <li><b>jar 同级目录</b>的 {@code install/<子目录>} —— 换基准只需放个文件</li>
 *   <li>进程工作目录的 {@code install/<子目录>} —— 从别处启动时的退路</li>
 *   <li>jar 内置的 {@code classpath:install/<子目录>} —— 什么都不放也能跑</li>
 * </ol>
 */
public final class BaselineLocator {

    private BaselineLocator() {
    }

    /**
     * 找一个可用的外部基准目录。
     *
     * @param subDir 如 {@code pg-sql} 或 {@code clickhouse-sql}
     * @return 存在的目录；都不存在返回 null，调用方退回 classpath
     */
    public static Path externalDir(String subDir) {
        Path jarDir = jarDirectory();
        if (jarDir != null) {
            Path p = jarDir.resolve("install").resolve(subDir);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        Path cwd = Path.of("").toAbsolutePath().resolve("install").resolve(subDir);
        return Files.isDirectory(cwd) ? cwd : null;
    }

    /**
     * jar 自身所在目录。
     *
     * <p>不能直接用工作目录 —— 运维可能从任意路径执行
     * {@code java -jar /opt/datao/doenv.jar}，那时工作目录和 jar 目录是两回事。
     */
    public static Path jarDirectory() {
        try {
            String loc = BaselineLocator.class.getProtectionDomain()
                    .getCodeSource().getLocation().toString();
            // Spring Boot 3.2 的可执行 jar 走 nested 协议，形如
            // jar:nested:/opt/datao/doenv.jar/!BOOT-INF/classes/!/
            // 逐层剥掉协议前缀和 jar 内路径，只留磁盘上那个 .jar 的真实路径
            String path = loc;
            for (String prefix : new String[]{"jar:", "nested:", "file:"}) {
                while (path.startsWith(prefix)) {
                    path = path.substring(prefix.length());
                }
            }
            int bang = path.indexOf('!');
            if (bang >= 0) {
                path = path.substring(0, bang);
            }
            path = java.net.URLDecoder.decode(path, java.nio.charset.StandardCharsets.UTF_8);
            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            if (path.isEmpty()) {
                return null;
            }
            File f = new File(path);
            File dir = f.isDirectory() ? f : f.getParentFile();
            return dir == null ? null : dir.toPath();
        } catch (Exception e) {
            // 拿不到就退回工作目录，不因为定位失败而中断升级
            return null;
        }
    }
}
