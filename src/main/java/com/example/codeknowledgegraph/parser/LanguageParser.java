package com.example.codeknowledgegraph.parser;

import com.example.codeknowledgegraph.parser.model.AnalysisResult.Edge;
import com.example.codeknowledgegraph.parser.model.AnalysisResult.FieldInfo;
import com.example.codeknowledgegraph.parser.model.AnalysisResult.MethodInfo;
import com.example.codeknowledgegraph.parser.model.AnalysisResult.TypeInfo;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * F5.1: 多语言解析器接口。
 * 每个实现负责一种(或一族)语言的单文件结构提取;跨文件的调用/依赖解析
 * 由 AnalysisService 基于全局名称索引统一完成(动态语言无符号求解器,按名启发式)。
 */
public interface LanguageParser {

    /** 该解析器处理的文件扩展名(含点,如 ".py") */
    Set<String> extensions();

    /**
     * 解析单个文件,产出其全部实体与"本地可确定"的边。
     * 跨文件引用(调用/导入)以名称形式返回在 unresolvedCalls / unresolvedImports 中,
     * 由调用方结合全项目索引二次解析。
     */
    FileStructure parseFile(Path root, Path file) throws Exception;

    /** 单文件结构提取结果 */
    record FileStructure(
            String language,
            String moduleQn,                  // 模块全限定名(如 a.b.c)
            List<TypeInfo> types,
            List<MethodInfo> methods,
            List<FieldInfo> fields,
            List<Edge> localEdges,            // 文件内可确定的边(如 EXTENDS 到同文件基类)
            List<NameRef> unresolvedCalls,    // callerId -> 被调名称(含接收者提示)
            List<NameRef> unresolvedImports) {

        /** 未解析引用:from 方法/类型 -> 名称(可能是函数名或 类名.方法名) */
        public record NameRef(String from, String name) {}
    }
}
