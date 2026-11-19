package com.example.codeknowledgegraph.parser.model;

import java.util.List;

/**
 * 一次代码分析产生的全部结果:实体(类型/方法/字段) + 关系(边)。
 */
public record AnalysisResult(
        String projectName,
        String rootPath,
        List<String> packages,
        List<TypeInfo> types,
        List<MethodInfo> methods,
        List<FieldInfo> fields,
        List<Edge> typeEdges,
        List<Edge> callEdges,
        int fileCount) {

    /** kind: class / interface / enum / record / annotation */
    public record TypeInfo(
            String qualifiedName,
            String simpleName,
            String packageName,
            String kind,
            String visibility,
            boolean isAbstract,
            int lineCount,
            String filePath,      // 相对分析根目录的路径(F1.1)
            int startLine,        // 声明起始行,1-based(F1.1)
            int endLine,          // 声明结束行,1-based(F1.1)
            String javadoc) {}    // Javadoc 描述摘要(F1.2)

    /** id 格式: 全限定名.方法名(参数个数) */
    public record MethodInfo(
            String id,
            String ownerType,
            String name,
            String signature,
            String returnType,
            String visibility,
            boolean isStatic,
            boolean isConstructor,
            String filePath,      // F1.1
            int startLine,        // F1.1
            int endLine,          // F1.1
            String javadoc,       // F1.2
            List<String> annotations, // F4.1/F4.2: 注解简单名列表(Override/Test/GetMapping...)
            String httpMethod,        // F4.2: REST 方法(GET/POST/...),非端点为 null
            String httpPath) {}       // F4.2: REST 完整路径,非端点为 null

    /** id 格式: 全限定名.字段名 */
    public record FieldInfo(
            String id,
            String ownerType,
            String name,
            String type,
            String visibility,
            boolean isStatic) {}

    /** from/to 为类型全限定名或方法 id, kind 见 TYPE_EDGE_KINDS */
    public record Edge(String from, String to, String kind) {}

    public static final String EXTENDS = "EXTENDS";
    public static final String IMPLEMENTS = "IMPLEMENTS";
    public static final String DEPENDS_ON = "DEPENDS_ON";
}
