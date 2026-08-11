package com.doenv.schema.baseline;

import com.doenv.schema.model.SchemaModel;
import com.doenv.schema.model.SeedTable;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 基准文件解析结果：结构模型 + 种子数据 + 解析统计。
 */
@Data
public class BaselineBundle {

    private String baselineId;

    private String sourceFile;

    private String sourceSha256;

    private SchemaModel schema = new SchemaModel();

    /** 表名 -> 种子数据 */
    private Map<String, SeedTable> seeds = new LinkedHashMap<>();

    /** 被丢弃的语句类型 -> 条数，如 DROP_TABLE=167、OWNER_TO=172、UNSAFE_DELETE=165 */
    private Map<String, Integer> discarded = new TreeMap<>();

    /** 无法识别的语句样本（最多保留若干条），便于排查解析器盲区 */
    private Map<String, String> unparsedSamples = new LinkedHashMap<>();

    public void discard(String kind) {
        discarded.merge(kind, 1, Integer::sum);
    }

    public int getSeedRowCount() {
        return seeds.values().stream().mapToInt(SeedTable::getRowCount).sum();
    }
}
