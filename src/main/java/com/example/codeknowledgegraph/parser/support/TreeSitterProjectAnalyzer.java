package com.example.codeknowledgegraph.parser.support;

import com.example.codeknowledgegraph.parser.LanguageParser;
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
 * F5.1: tree-sitter 语言项目编排器——按扩展名路由到对应 LanguageParser,
 * 并基于全局名称索引解析跨文件引用。
 * 统一调用解析规则:
 * - self./this./cls. 前缀 -> 当前类(所属类型)的方法
 * - Class.m / pkg.f -> 类型简名或模块简名匹配后找方法
 * - 裸函数名 f -> 优先模块级函数,其次任意同名方法
 * 已知局限: 接收者变量类型无法静态推断(Go 的 s.Method()、TS 的实例调用),这类边 v1 不建。
 */
@Service
public class TreeSitterProjectAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(TreeSitterProjectAnalyzer.class);

    private static final Set<String> SELF_PREFIXES = Set.of("self", "this", "cls");

    private final Map<String, LanguageParser> parsersByExtension = new HashMap<>();

    public TreeSitterProjectAnalyzer(List<LanguageParser> parsers) {
        for (LanguageParser p : parsers) {
            for (String ext : p.extensions()) {
                parsersByExtension.put(ext, p);
            }
        }
        log.info("tree-sitter 语言支持: {}", parsersByExtension.keySet());
    }

    /** 支持的全部扩展名 */
    public Set<String> extensions() {
        return Set.copyOf(parsersByExtension.keySet());
    }

    /** 解析结果(实体只含本次解析的文件,可直接 applyDelta;全量时即全部) */
    public record Result(List<TypeInfo> types, List<MethodInfo> methods,
                         List<FieldInfo> fields, List<Edge> typeEdges, List<Edge> callEdges,
                         List<String> packages, int fileCount) {}

    private record Index(Map<String, TypeInfo> typesByQn, Map<String, TypeInfo> typesBySimple,
                          Map<String, List<MethodInfo>> methodsByOwner,
                          Map<String, List<MethodInfo>> methodsByName,
                          Map<String, String> ownerKind) {}

    /** 找出目录下全部受支持文件(排除虚拟环境/构建产物) */
    public List<Path> discoverFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return parsersByExtension.containsKey(extOf(name));
                    })
                    .filter(p -> {
                        String s = p.toString();
                        return !s.contains("/.git/") && !s.contains("/__pycache__/")
                                && !s.contains("/venv/") && !s.contains("/.venv/")
                                && !s.contains("/node_modules/") && !s.contains("/dist/")
                                && !s.contains("/build/") && !s.contains("/target/");
                    })
                    .forEach(files::add);
        }
        return files;
    }

    /** 全量:解析全部受支持文件并解析跨文件引用 */
    public Result analyzeProject(Path root, List<Path> files) {
        List<FileStructure> structs = parseAll(root, files);
        List<TypeInfo> types = new ArrayList<>();
        List<MethodInfo> methods = new ArrayList<>();
        structs.forEach(s -> { types.addAll(s.types()); methods.addAll(s.methods()); });
        Index index = buildIndex(types, methods);
        return resolve(structs, index);
    }

    /** 增量:只解析变更文件,跨文件引用按"图内种子 + 本次实体"解析 */
    public Result analyzeIncremental(Path root, List<Path> changedFiles,
                                      List<TypeInfo> seedTypes, List<MethodInfo> seedMethods) {
        List<FileStructure> structs = parseAll(root, changedFiles);
        List<TypeInfo> allTypes = new ArrayList<>(seedTypes);
        List<MethodInfo> allMethods = new ArrayList<>(seedMethods);
        structs.forEach(s -> { allTypes.addAll(s.types()); allMethods.addAll(s.methods()); });
        Index index = buildIndex(allTypes, allMethods);
        return resolve(structs, index);
    }

    // ==================== 内部实现 ====================

    private String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }

    private List<FileStructure> parseAll(Path root, List<Path> files) {
        List<FileStructure> structs = new ArrayList<>();
        for (Path f : files) {
            LanguageParser parser = parsersByExtension.get(extOf(f.getFileName().toString()));
            if (parser == null) continue;
            try {
                structs.add(parser.parseFile(root, f));
            } catch (Exception e) {
                log.warn("解析失败,跳过 {}: {}", f, e.getMessage());
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

    /** 解析跨文件引用: EXTENDS/IMPLEMENTS/CALLS/DEPENDS_ON(按名启发式) */
    private Result resolve(List<FileStructure> structs, Index index) {
        List<Edge> callEdges = new ArrayList<>();
        List<Edge> typeEdges = new ArrayList<>();
        Set<String> callDedup = new LinkedHashSet<>();
        Set<String> typeDedup = new LinkedHashSet<>();
        Set<String> packages = new LinkedHashSet<>();

        for (FileStructure s : structs) {
            packages.addAll(s.packages());

            // 1. EXTENDS/IMPLEMENTS: 基类按全限定名或简名匹配,目标存在才建边
            for (Edge e : s.localEdges()) {
                TypeInfo target = index.typesByQn().get(e.to());
                if (target == null) target = index.typesBySimple().get(e.to());
                if (target != null && !target.qualifiedName().equals(e.from())) {
                    addEdge(typeEdges, typeDedup,
                            new Edge(e.from(), target.qualifiedName(), e.kind()));
                }
            }

            // 2. CALLS
            for (FileStructure.NameRef ref : s.unresolvedCalls()) {
                Edge call = resolveCall(ref.from(), ref.name(), index);
                if (call != null) addEdge(callEdges, callDedup, call);
            }

            // 3. DEPENDS_ON: 导入的模块/类若在图内
            //    相对导入('./x'、'../y')按导入方模块的包路径换算成候选全限定名
            for (FileStructure.NameRef ref : s.unresolvedImports()) {
                String name = ref.name();
                TypeInfo target = index.typesByQn().get(name);
                if (target == null && name.startsWith(".")) {
                    String pkg = s.moduleQn().contains(".")
                            ? s.moduleQn().substring(0, s.moduleQn().lastIndexOf('.'))
                            : s.moduleQn();
                    String rest = name;
                    while (rest.startsWith("./")) rest = rest.substring(2);
                    while (rest.startsWith("../")) {
                        int dot = pkg.lastIndexOf('.');
                        pkg = dot < 0 ? "" : pkg.substring(0, dot);
                        rest = rest.substring(3);
                    }
                    // TS ESM 风格相对导入带 .js 后缀(源文件是 .ts)
                    if (rest.endsWith(".js")) rest = rest.substring(0, rest.length() - 3);
                    if (rest.endsWith("/index")) rest = rest.substring(0, rest.length() - "/index".length());
                    String candidate = pkg.isEmpty() ? rest : pkg + "." + rest;
                    target = index.typesByQn().get(candidate);
                    if (target == null) {
                        // 候选模块本身不存在时,尝试该路径下的类型(简名拼接)
                        target = index.typesBySimple().get(rest);
                    }
                }
                if (target == null) continue;
                if (!target.qualifiedName().equals(s.moduleQn())) {
                    addEdge(typeEdges, typeDedup,
                            new Edge(s.moduleQn(), target.qualifiedName(), "DEPENDS_ON"));
                }
            }
        }
        List<TypeInfo> types = new ArrayList<>();
        List<MethodInfo> methods = new ArrayList<>();
        List<FieldInfo> fields = new ArrayList<>();
        structs.forEach(s -> { types.addAll(s.types()); methods.addAll(s.methods()); fields.addAll(s.fields()); });
        return new Result(types, methods, fields, typeEdges, callEdges,
                new ArrayList<>(packages), structs.size());
    }

    private Edge resolveCall(String fromMethodId, String ref, Index index) {
        // 装饰器引用的 from 是类/模块(非方法 id),无法建 CALLS 边,跳过
        if (!fromMethodId.matches(".+\\.\\w+\\(\\d+\\)$")) return null;

        // self./this./cls. -> 当前类型的方法
        int firstDot = ref.indexOf('.');
        if (firstDot > 0) {
            String prefix = ref.substring(0, firstDot);
            if (SELF_PREFIXES.contains(prefix)) {
                String owner = ownerOf(fromMethodId);
                String methodName = ref.substring(firstDot + 1);
                MethodInfo target = findMethod(index, owner, methodName);
                return target == null ? null : new Edge(fromMethodId, target.id(), "CALLS");
            }
        }
        // Class.m / module.f
        int lastDot = ref.lastIndexOf('.');
        if (lastDot > 0) {
            String ownerPart = ref.substring(0, lastDot);
            String methodName = ref.substring(lastDot + 1);
            TypeInfo ownerType = index.typesByQn().containsKey(ownerPart)
                    ? index.typesByQn().get(ownerPart)
                    : index.typesBySimple().get(ownerPart);
            if (ownerType != null) {
                MethodInfo target = findMethod(index, ownerType.qualifiedName(), methodName);
                return target == null ? null : new Edge(fromMethodId, target.id(), "CALLS");
            }
            return null;
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
