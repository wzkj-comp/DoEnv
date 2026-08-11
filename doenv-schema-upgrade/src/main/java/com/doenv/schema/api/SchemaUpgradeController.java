package com.doenv.schema.api;

import com.doenv.schema.diff.DiffLevel;
import com.doenv.schema.diff.DiffOptions;
import com.doenv.schema.service.SchemaUpgradeService;
import com.doenv.schema.service.UpgradeReport;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 结构升级 REST 接口。
 *
 * <p>一期只读：只有比对、查看、导出，<b>没有执行入口</b>。
 *
 * <p>返回体统一为 {@code {success, message, data}} —— 与 datao-main 前端
 * 的 HTTP 封装约定一致，嵌入宿主后前端可直接用现有的 HttpGet/HttpPost。
 */
@RestController
@RequestMapping("/doenv/schemaUpgrade")
public class SchemaUpgradeController {

    private final SchemaUpgradeService service;

    public SchemaUpgradeController(@org.springframework.context.annotation.Lazy SchemaUpgradeService service) {
        this.service = service;
    }

    /** 环境探测：库、schema、权限、阻断项 */
    @GetMapping("/environment")
    public Map<String, Object> environment() {
        try {
            return ok(service.probeEnvironment());
        } catch (Exception e) {
            return fail(e);
        }
    }

    /** 发起比对 */
    @PostMapping("/compare")
    public Map<String, Object> compare(
            @RequestParam(defaultValue = "true") boolean checkComment,
            @RequestParam(defaultValue = "true") boolean checkDefault,
            @RequestParam(defaultValue = "true") boolean checkIndex,
            @RequestParam(defaultValue = "true") boolean checkConstraint,
            @RequestParam(defaultValue = "true") boolean reportExtra) {
        try {
            DiffOptions opt = new DiffOptions();
            opt.setCheckComment(checkComment);
            opt.setCheckDefault(checkDefault);
            opt.setCheckIndex(checkIndex);
            opt.setCheckConstraint(checkConstraint);
            opt.setReportExtra(reportExtra);
            return ok(service.compare(opt));
        } catch (Exception e) {
            return fail(e);
        }
    }

    /** 取已缓存的报告 */
    @GetMapping("/report")
    public Map<String, Object> report(@RequestParam String reportId) {
        UpgradeReport r = service.getReport(reportId);
        if (r == null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("success", false);
            m.put("message", "报告不存在或已过期，请重新比对");
            return m;
        }
        return ok(r);
    }

    /**
     * 执行升级。
     *
     * <p>只接受 reportId 与档位，<b>不接受外部传入 SQL</b> —— 语句一律由后端从基准
     * 重新生成，杜绝篡改与前后端不一致。MANUAL / INFO 档会被强制剔除。
     *
     * @param dryRun true 时每条执行后立刻回滚，只验证可行性
     */
    @PostMapping("/execute")
    public Map<String, Object> execute(
            @RequestParam String reportId,
            @RequestParam(defaultValue = "AUTO") String levels,
            @RequestParam(defaultValue = "true") boolean dryRun) {
        try {
            java.util.List<DiffLevel> want = java.util.Arrays.stream(levels.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .map(DiffLevel::valueOf).toList();
            return ok(service.execute(reportId, want, dryRun));
        } catch (Exception e) {
            return fail(e);
        }
    }

    /** 导出 SQL 脚本 */
    @GetMapping(value = "/downloadSql", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<byte[]> downloadSql(
            @RequestParam String reportId,
            @RequestParam(defaultValue = "AUTO") String levels) {

        UpgradeReport r = service.getReport(reportId);
        if (r == null) {
            return ResponseEntity.notFound().build();
        }
        DiffLevel[] want = java.util.Arrays.stream(levels.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(DiffLevel::valueOf)
                .toArray(DiffLevel[]::new);

        byte[] body = r.toSqlScript(want).getBytes(StandardCharsets.UTF_8);
        String filename = "doenv-upgrade-" + r.getReportId() + ".sql";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.TEXT_PLAIN)
                .body(body);
    }

    private Map<String, Object> ok(Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("message", "");
        m.put("data", data);
        return m;
    }

    private Map<String, Object> fail(Exception e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("message", e.getMessage() == null ? e.toString() : e.getMessage());
        return m;
    }
}
