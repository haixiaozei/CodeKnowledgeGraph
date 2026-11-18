package com.example.codeknowledgegraph.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;

/**
 * Neo4j 索引初始化。
 * 没有索引时,边写入的端点匹配(MATCH by id/qualifiedName)退化为全表扫描,
 * 是全量/增量分析慢的主因(万级方法节点时单批写入可达分钟级)。
 * 应用启动时创建并等待索引就绪。
 */
@Component
public class Neo4jSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Neo4jSchemaInitializer.class);

    private final Neo4jClient neo4j;

    public Neo4jSchemaInitializer(Neo4jClient neo4j) {
        this.neo4j = neo4j;
    }

    @Override
    public void run(ApplicationArguments args) {
        long start = System.currentTimeMillis();
        createIndexes();
        try {
            // 等待既有数据上的索引构建完成(首次会填充,之后瞬时)
            neo4j.query("CALL db.awaitIndexes(300)").run();
            log.info("Neo4j 索引就绪,耗时 {}ms", System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("等待索引构建失败(不影响功能,首查询可能较慢): {}", e.getMessage());
        }
    }

    private void createIndexes() {
        String[][] indexes = {
                // label, 属性列表
                {"Project", "name"},
                {"Type", "project,qualifiedName"},
                {"Type", "project,filePath"},
                {"Method", "project,id"},
                {"Method", "project,filePath"},
                {"Field", "project,id"},
                {"Package", "project,name"},
        };
        for (String[] idx : indexes) {
            // 复合索引的每个属性都要带变量前缀: ON (n.project, n.qualifiedName)
            String props = idx[1].contains(",")
                    ? "n." + idx[1].replace(",", ",n.")
                    : "n." + idx[1];
            String cypher = "CREATE INDEX IF NOT EXISTS FOR (n:" + idx[0] + ") ON (" + props + ")";
            try {
                neo4j.query(cypher).run();
            } catch (Exception e) {
                log.warn("创建索引失败 ({} on {}): {}", idx[0], idx[1], e.getMessage());
            }
        }
    }
}
