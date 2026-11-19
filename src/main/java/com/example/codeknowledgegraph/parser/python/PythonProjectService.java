package com.example.codeknowledgegraph.parser.python;

import com.example.codeknowledgegraph.parser.LanguageParser.FileStructure;
import com.example.codeknowledgegraph.parser.model.AnalysisResult.Edge;
import com.example.codeknowledgegraph.parser.model.AnalysisResult.FieldInfo;
import com.example.codeknowledgegraph.parser.model.AnalysisResult.MethodInfo;
import com.example.codeknowledgegraph.parser.model.AnalysisResult.TypeInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * F5.1: Python 项目解析编排——按文件解析 + 全局名称索引二次解析跨文件引用。
 * 全量: 解析全部 .py; 增量: 只解析变更文件,全局索引来自图内种子(未变更部分)。
 */
@Service
public class PythonProjectService {

    private static final Logger log = LoggerFactory.getLogger(PythonProjectService.class);

    private final PythonAnalyzer parser;

    public PythonProjectService(PythonAnalyzer parser) {
        this.parser = parser;
    }

    /** 解析结果(实体只含本次解析的文件,可直接 applyDelta;全量时即全部) */
    public record PyResult(List<TypeInfo> types, List<MethodInfo> methods,
                           List<FieldInfo> fields, List<Edge> typeEdges, List<Edge> callEdges,
                           List<String> packages, int fileCount) {}

    private record Index(Map<String, TypeInfo> typesByQn, Map<String, TypeInfo> typesBySimple,
                          Map<String, List<MethodInfo>> methodsByOwner,
                          Map<String, List<MethodInfo>> methodsByName,
                          Map<String, String> ownerKind) {}

    /** 找出目录下全部 Python 文件(排除虚拟环境/构建产物) */
    public List<Path> discoverPythonFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".py"))
                    .filter(p -> {
                        String s = p.toString();
                        return !s.contains("/.git/") && !s.contains("/__pycache__/")
                                && !s.contains("/venv/") && !s.contains("/.venv/")
                                && !s.contains("/node_modules/") && !s.contains("/dist/")
                                && !s.contains("/build/");
                    })
                    .forEach(files::add);
        }
        return files;
    }

    /** 全量:解析全部 .py 文件并解析跨文件引用 */
    public PyResult analyzeProject(Path root, List<Path> pyFiles) {
        List<FileStructure> structs = parseAll(root, pyFiles);
        Index index = buildIndex(collectTypes(structs), collectMethods(structs));
        return resolve(structs, index);
    }

    /** 增量:只解析变更文件,跨文件引用按"图内种子 + 本次实体"解析 */
    public PyResult analyzeIncremental(Path root, List<Path> changedFiles,
                                        List<TypeInfo> seedTypes, List<MethodInfo> seedMethods) {
        List<FileStructure> structs = parseAll(root, changedFiles);
        List<TypeInfo> allTypes = new ArrayList<>(seedTypes);
        List<MethodInfo> allMethods = new ArrayList<>(seedMethods);
        structs.forEach(s -> { allTypes.addAll(s.types()); allMethods.addAll(s.methods()); });
        Index index = buildIndex(allTypes, allMethods);
        return resolve(structs, index);
    }

    private List<TypeInfo> collectTypes(List<FileStructure> structs) {
        List<TypeInfo> out = new ArrayList<>();
        structs.forEach(s -> out.addAll(s.types()));
        return out;
    }

    private List<MethodInfo> collectMethods(List<FileStructure> structs) {
        List<MethodInfo> out = new ArrayList<>();
        structs.forEach(s -> out.addAll(s.methods()));
        return out;
    }

    // ==================== 内部实现 ====================

    private List<FileStructure> parseAll(Path root, List<Path> files) {
        List<FileStructure> structs = new ArrayList<>();
        for (Path f : files) {
            try {
                structs.add(parser.parseFile(root, f));
            } catch (Exception e) {
                log.warn("Python 解析失败,跳过 {}: {}", f, e.getMessage());
            }
        }
        return structs;
    }

    private Index buildIndex(List<TypeInfo> types, List<MethodInfo> methods) {
        Map<String, TypeInfo> typesByQn = new HashMap<>();
        Map<String, TypeInfo> typesBySimple = new HashMap<>();
        Map<String, String> ownerKind = new HashMap<>();
        for (TypeInfo t : types) {
            typesByQn.putIfAbsent(t.qualifiedName(), t);
            typesBySimple.putIfAbsent(t.simpleName(), t);
            ownerKind.putIfAbsent(t.qualifiedName(), t.kind());
        }
        Map<String, List<MethodInfo>> methodsByOwner = new HashMap<>();
        Map<String, List<MethodInfo>> methodsByName = new HashMap<>();
        for (MethodInfo m : methods) {
            methodsByOwner.computeIfAbsent(m.ownerType(), k -> new ArrayList<>()).add(m);
            methodsByName.computeIfAbsent(m.name(), k -> new ArrayList<>()).add(m);
        }
        return new Index(typesByQn, typesBySimple, methodsByOwner, methodsByName, ownerKind);
    }

    /** 解析跨文件引用: EXTENDS/CALLS/DEPENDS_ON(按名启发式) */
    private PyResult resolve(List<FileStructure> structs, Index index) {
        List<Edge> callEdges = new ArrayList<>();
        List<Edge> typeEdges = new ArrayList<>();
        Set<String> callDedup = new LinkedHashSet<>();
        Set<String> typeDedup = new LinkedHashSet<>();
        Set<String> packages = new LinkedHashSet<>();

        for (FileStructure s : structs) {
            packages.addAll(s.packages());

            // 1. EXTENDS: 基类按全限定名或简名匹配,目标存在才建边
            for (Edge e : s.localEdges()) {
                if (!"EXTENDS".equals(e.kind())) continue;
                TypeInfo base = index.typesByQn().get(e.to());
                if (base == null) base = index.typesBySimple().get(e.to());
                if (base != null && !base.qualifiedName().equals(e.from())) {
                    addEdge(typeEdges, typeDedup, new Edge(e.from(), base.qualifiedName(), "EXTENDS"));
                }
            }

            // 2. CALLS: self.m / Class.m / 裸函数名
            for (FileStructure.NameRef ref : s.unresolvedCalls()) {
                Edge call = resolveCall(ref.from(), ref.name(), index);
                if (call != null) addEdge(callEdges, callDedup, call);
            }

            // 3. DEPENDS_ON: import 的模块/类若在图内
            for (FileStructure.NameRef ref : s.unresolvedImports()) {
                TypeInfo target = index.typesByQn().get(ref.name());
                if (target == null) {
                    // 前缀匹配: import a.b.c 时 a.b 包的 __init__ 模块(存在则为 a.b)
                    TypeInfo mod = index.typesByQn().get(ref.name());
                    if (mod == null) continue;
                    target = mod;
                }
                if (!target.qualifiedName().equals(s.moduleQn())) {
                    addEdge(typeEdges, typeDedup, new Edge(s.moduleQn(), target.qualifiedName(), "DEPENDS_ON"));
                }
            }
        }
        List<TypeInfo> types = new ArrayList<>();
        List<MethodInfo> methods = new ArrayList<>();
        List<FieldInfo> fields = new ArrayList<>();
        structs.forEach(s -> { types.addAll(s.types()); methods.addAll(s.methods()); fields.addAll(s.fields()); });
        return new PyResult(types, methods, fields, typeEdges, callEdges,
                new ArrayList<>(packages), structs.size());
    }

    private Edge resolveCall(String fromMethodId, String ref, Index index) {
        // 装饰器引用的 from 是类/模块(非方法 id),无法建 CALLS 边,跳过
        if (!fromMethodId.matches(".+\\.\\w+\\(\\d+\\)$")) return null;

        if (ref.startsWith("self.") || ref.startsWith("cls.")) {
            String owner = ownerOf(fromMethodId);
            String methodName = ref.substring(ref.indexOf('.') + 1);
            MethodInfo target = findMethod(index, owner, methodName);
            return target == null ? null : new Edge(fromMethodId, target.id(), "CALLS");
        }
        int lastDot = ref.lastIndexOf('.');
        if (lastDot > 0) {
            String clsPart = ref.substring(0, lastDot);
            String methodName = ref.substring(lastDot + 1);
            TypeInfo cls = index.typesByQn().containsKey(clsPart)
                    ? index.typesByQn().get(clsPart)
                    : index.typesBySimple().get(clsPart);
            if (cls == null) return null;
            MethodInfo target = findMethod(index, cls.qualifiedName(), methodName);
            return target == null ? null : new Edge(fromMethodId, target.id(), "CALLS");
        }
        // 裸函数名:优先模块级函数,其次任意同名方法
        List<MethodInfo> candidates = index.methodsByName().get(ref);
        if (candidates == null || candidates.isEmpty()) return null;
        MethodInfo moduleFn = candidates.stream()
                .filter(m -> "module".equals(index.ownerKind().get(m.ownerType())))
                .findFirst().orElse(candidates.get(0));
        return new Edge(fromMethodId, moduleFn.id(), "CALLS");
    }

    /** from = ownerQn.name(N) -> ownerQn */
    private String ownerOf(String methodId) {
        int paren = methodId.lastIndexOf('(');
        int dot = methodId.lastIndexOf('.', paren);
        return dot < 0 ? methodId : methodId.substring(0, dot);
    }

    private MethodInfo findMethod(Index index, String ownerQn, String name) {
        List<MethodInfo> candidates = index.methodsByOwner().get(ownerQn);
        if (candidates == null) return null;
        return candidates.stream().filter(m -> m.name().equals(name)).findFirst().orElse(null);
    }

    private void addEdge(List<Edge> out, Set<String> dedup, Edge e) {
        if (e.to().equals(e.from())) return;
        if (dedup.add(e.from() + "|" + e.to() + "|" + e.kind())) out.add(e);
    }
}
