package com.example.codeknowledgegraph.parser;

import com.example.codeknowledgegraph.parser.model.AnalysisResult;
import com.example.codeknowledgegraph.parser.model.AnalysisResult.Edge;
import com.example.codeknowledgegraph.parser.model.AnalysisResult.FieldInfo;
import com.example.codeknowledgegraph.parser.model.AnalysisResult.MethodInfo;
import com.example.codeknowledgegraph.parser.model.AnalysisResult.TypeInfo;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithParameters;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 基于 JavaParser 的代码解析引擎。
 * 第一遍:收集所有类型/方法/字段实体;第二遍:借助符号求解器抽取继承/实现/依赖/调用关系。
 */
@Component
public class CodeAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(CodeAnalyzer.class);

    public AnalysisResult analyze(String projectName, Path root) throws IOException {
        long start = System.currentTimeMillis();
        List<Path> sourceRoots = findSourceRoots(root);
        log.info("[{}] 发现 {} 个源码根目录: {}", projectName, sourceRoots.size(), sourceRoots);

        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        sourceRoots.forEach(r -> typeSolver.add(new JavaParserTypeSolver(r.toFile())));
        typeSolver.add(new ReflectionTypeSolver());

        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setSymbolResolver(new JavaSymbolSolver(typeSolver))
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

        List<CompilationUnit> units = parseAll(parser, root);
        log.info("[{}] 解析完成,共 {} 个 Java 文件", projectName, units.size());

        // ---------- 第一遍:收集实体 ----------
        Map<String, TypeInfo> typeMap = new LinkedHashMap<>();          // qn -> info
        Map<String, List<MethodInfo>> methodsByType = new HashMap<>();  // qn -> methods
        Map<String, List<FieldInfo>> fieldsByType = new HashMap<>();   // qn -> fields
        Set<String> packages = new LinkedHashSet<>();
        Map<String, String> simpleNameToQn = new HashMap<>();         // 简名 -> qn(同图内)

        for (CompilationUnit cu : units) {
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            if (!pkg.isEmpty()) packages.add(pkg);
            for (TypeDeclaration<?> td : cu.getTypes()) {
                collectType(td, pkg, typeMap, methodsByType, fieldsByType, simpleNameToQn);
            }
        }

        // ---------- 第二遍:抽取关系 ----------
        List<Edge> typeEdges = new ArrayList<>();
        Set<String> typeEdgeSet = new HashSet<>();   // 去重
        List<Edge> callEdges = new ArrayList<>();
        Set<String> callEdgeSet = new HashSet<>();

        for (CompilationUnit cu : units) {
            for (TypeDeclaration<?> td : cu.getTypes()) {
                extractRelations(td, cu, typeMap, simpleNameToQn, methodsByType,
                        typeEdges, typeEdgeSet, callEdges, callEdgeSet);
            }
        }

        List<MethodInfo> allMethods = methodsByType.values().stream().flatMap(List::stream).toList();
        List<FieldInfo> allFields = fieldsByType.values().stream().flatMap(List::stream).toList();

        log.info("[{}] 分析完成: {} 类型 / {} 方法 / {} 字段 / {} 类型关系 / {} 调用关系, 耗时 {}ms",
                projectName, typeMap.size(), allMethods.size(), allFields.size(),
                typeEdges.size(), callEdges.size(), System.currentTimeMillis() - start);

        return new AnalysisResult(projectName, root.toAbsolutePath().toString(),
                new ArrayList<>(packages), new ArrayList<>(typeMap.values()),
                allMethods, allFields, typeEdges, callEdges, units.size());
    }

    // ==================== 实体收集 ====================

    private void collectType(TypeDeclaration<?> td, String pkg,
                              Map<String, TypeInfo> typeMap,
                              Map<String, List<MethodInfo>> methodsByType,
                              Map<String, List<FieldInfo>> fieldsByType,
                              Map<String, String> simpleNameToQn) {
        String qn = qualifiedName(td, pkg);
        if (qn == null || typeMap.containsKey(qn)) return;

        String kind = kindOf(td);
        typeMap.put(qn, new TypeInfo(qn, td.getNameAsString(), pkg, kind,
                visibilityOf(td), isAbstract(td), td.getRange().map(r -> r.end.line - r.begin.line + 1).orElse(0)));
        simpleNameToQn.put(td.getNameAsString(), qn);
        methodsByType.put(qn, new ArrayList<>());
        fieldsByType.put(qn, new ArrayList<>());

        // 方法与构造器
        for (BodyDeclaration<?> member : td.getMembers()) {
            if (member instanceof CallableDeclaration<?> cd) {
                String params = String.join(", ",
                        ((NodeWithParameters<?>) cd).getParameters().stream()
                                .map(p -> p.getType().asString()).toList());
                String name = cd.getNameAsString();
                boolean isCtor = cd.isConstructorDeclaration();
                methodsByType.get(qn).add(new MethodInfo(
                        methodId(qn, name, ((NodeWithParameters<?>) cd).getParameters().size()),
                        qn, name,
                        name + "(" + params + ")",
                        isCtor ? qn.substring(qn.lastIndexOf('.') + 1)
                                : cd.toMethodDeclaration().map(m -> m.getType().asString()).orElse("void"),
                        visibilityOf(cd), cd.toMethodDeclaration().map(m -> m.isStatic()).orElse(false),
                        isCtor));
            } else if (member instanceof FieldDeclaration fd) {
                for (VariableDeclarator v : fd.getVariables()) {
                    fieldsByType.get(qn).add(new FieldInfo(
                            qn + "." + v.getNameAsString(), qn, v.getNameAsString(),
                            v.getType().asString(), visibilityOf(fd), fd.isStatic()));
                }
            } else if (member instanceof TypeDeclaration<?> nested) {
                collectType(nested, pkg, typeMap, methodsByType, fieldsByType, simpleNameToQn);
            }
        }
    }

    // ==================== 关系抽取 ====================

    private void extractRelations(TypeDeclaration<?> td, CompilationUnit cu,
                                   Map<String, TypeInfo> typeMap, Map<String, String> simpleNameToQn,
                                   Map<String, List<MethodInfo>> methodsByType,
                                   List<Edge> typeEdges, Set<String> typeEdgeSet,
                                   List<Edge> callEdges, Set<String> callEdgeSet) {
        String qn = qualifiedName(td, cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse(""));
        if (qn == null || !typeMap.containsKey(qn)) return;

        if (td instanceof ClassOrInterfaceDeclaration cid) {
            cid.getExtendedTypes().forEach(t ->
                    addTypeEdge(qn, resolveTypeName(t.asString(), cu, typeMap, simpleNameToQn),
                            AnalysisResult.EXTENDS, typeEdges, typeEdgeSet));
            cid.getImplementedTypes().forEach(t ->
                    addTypeEdge(qn, resolveTypeName(t.asString(), cu, typeMap, simpleNameToQn),
                            AnalysisResult.IMPLEMENTS, typeEdges, typeEdgeSet));
        } else if (td instanceof RecordDeclaration rd) {
            rd.getImplementedTypes().forEach(t ->
                    addTypeEdge(qn, resolveTypeName(t.asString(), cu, typeMap, simpleNameToQn),
                            AnalysisResult.IMPLEMENTS, typeEdges, typeEdgeSet));
        } else if (td instanceof EnumDeclaration ed) {
            ed.getImplementedTypes().forEach(t ->
                    addTypeEdge(qn, resolveTypeName(t.asString(), cu, typeMap, simpleNameToQn),
                            AnalysisResult.IMPLEMENTS, typeEdges, typeEdgeSet));
        }

        // import 依赖(仅记录图内存在的类型)
        cu.getImports().forEach(imp -> {
            String imported = imp.getNameAsString();
            if (typeMap.containsKey(imported) && !imported.equals(qn)) {
                addTypeEdge(qn, imported, AnalysisResult.DEPENDS_ON, typeEdges, typeEdgeSet);
            }
        });

        // 方法体:调用关系 + 类型依赖
        td.findAll(MethodCallExpr.class).forEach(call -> {
            String callerId = enclosingMethodId(call, qn);
            if (callerId == null) return;
            // 1. 符号求解器精确解析
            String resolved = tryResolveCall(call);
            if (resolved != null) {
                addCallEdge(callerId, resolved, methodsByType, callEdges, callEdgeSet,
                        qn, typeMap, typeEdges, typeEdgeSet);
            } else {
                // 2. 启发式:推断接收者变量类型
                String receiverType = heuristicReceiverType(call, cu, typeMap, simpleNameToQn, qn);
                if (receiverType != null) {
                    addTypeEdge(qn, receiverType, AnalysisResult.DEPENDS_ON, typeEdges, typeEdgeSet);
                    matchCallByName(callerId, call, receiverType, methodsByType, callEdges, callEdgeSet);
                }
            }
        });

        // 字段类型 + new 表达式产生的依赖
        td.findAll(ObjectCreationExpr.class).forEach(oc -> {
            String t = resolveTypeName(oc.getType().asString(), cu, typeMap, simpleNameToQn);
            if (t != null) addTypeEdge(qn, t, AnalysisResult.DEPENDS_ON, typeEdges, typeEdgeSet);
        });
        td.findAll(FieldDeclaration.class).forEach(fd ->
                fd.getVariables().forEach(v -> {
                    String t = resolveTypeName(v.getType().asString(), cu, typeMap, simpleNameToQn);
                    if (t != null) addTypeEdge(qn, t, AnalysisResult.DEPENDS_ON, typeEdges, typeEdgeSet);
                }));
    }

    /** 尝试用符号求解器解析调用目标,成功返回 "声明类型" */
    private String tryResolveCall(MethodCallExpr call) {
        try {
            var resolved = call.resolve();
            return resolved.declaringType().getQualifiedName();
        } catch (Exception e) {
            return null;
        }
    }

    /** 调用边落在图内类型上时,按 方法名+参数个数 匹配目标方法 */
    private void addCallEdge(String callerId, String declaringType,
                             Map<String, List<MethodInfo>> methodsByType,
                             List<Edge> callEdges, Set<String> callEdgeSet,
                             String sourceType, Map<String, TypeInfo> typeMap,
                             List<Edge> typeEdges, Set<String> typeEdgeSet) {
        if (!declaringType.equals(sourceType)) {
            // 外部声明类型可能解析为完整限定名,但不在图内时忽略
            if (!methodsByType.containsKey(declaringType)) return;
            addTypeEdge(sourceType, declaringType, AnalysisResult.DEPENDS_ON, typeEdges, typeEdgeSet);
        }
        matchCallByName(callerId, null, declaringType, methodsByType, callEdges, callEdgeSet);
    }

    private void matchCallByName(String callerId, MethodCallExpr call, String declaringType,
                                 Map<String, List<MethodInfo>> methodsByType,
                                 List<Edge> callEdges, Set<String> callEdgeSet) {
        List<MethodInfo> candidates = methodsByType.get(declaringType);
        if (candidates == null) return;
        int argCount = call != null ? call.getArguments().size() : -1;
        MethodInfo best = null;
        for (MethodInfo m : candidates) {
            if (m.isConstructor()) continue;
            if (call != null && !m.name().equals(call.getNameAsString())) continue;
            if (best == null) best = m;   // 第一个同名方法
            // 精确匹配参数个数
            if (call != null && m.id().endsWith("(" + argCount + ")")) { best = m; break; }
        }
        if (best != null) {
            String key = callerId + "->" + best.id();
            if (callEdgeSet.add(key)) callEdges.add(new Edge(callerId, best.id(), "CALLS"));
        }
    }

    /** 启发式推断方法调用接收者的类型 */
    private String heuristicReceiverType(MethodCallExpr call, CompilationUnit cu,
                                          Map<String, TypeInfo> typeMap,
                                          Map<String, String> simpleNameToQn, String currentType) {
        var scope = call.getScope();
        if (scope.isEmpty()) return null;
        final String varName;
        if (scope.get() instanceof NameExpr ne) {
            varName = ne.getNameAsString();
        } else if (scope.get() instanceof ObjectCreationExpr oc) {
            return resolveTypeName(oc.getType().asString(), cu, typeMap, simpleNameToQn);
        } else {
            return null;
        }

        // 沿祖先链查找:方法参数/局部变量 → 类字段
        var node = call.getParentNode();
        while (node.isPresent()) {
            var n = node.get();
            if (n instanceof com.github.javaparser.ast.body.MethodDeclaration md) {
                for (var p : md.getParameters()) {
                    if (p.getNameAsString().equals(varName)) {
                        return resolveTypeName(p.getType().asString(), cu, typeMap, simpleNameToQn);
                    }
                }
                var local = md.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).stream()
                        .filter(v -> v.getNameAsString().equals(varName))
                        .findFirst();
                if (local.isPresent()) {
                    return resolveTypeName(local.get().getType().asString(), cu, typeMap, simpleNameToQn);
                }
            } else if (n instanceof TypeDeclaration<?> td) {
                for (var fd : td.getFields()) {
                    for (var v : fd.getVariables()) {
                        if (v.getNameAsString().equals(varName)) {
                            return resolveTypeName(v.getType().asString(), cu, typeMap, simpleNameToQn);
                        }
                    }
                }
            }
            node = n.getParentNode();
        }
        return null;
    }

    /** 将类型名(可能是简名/泛型)解析为图内的全限定名,不在图内返回 null */
    private String resolveTypeName(String name, CompilationUnit cu,
                                    Map<String, TypeInfo> typeMap, Map<String, String> simpleNameToQn) {
        if (name == null || name.isBlank()) return null;
        String base = name.replaceAll("<.*>", "").replaceAll("\\[\\]", "").trim();
        if (base.startsWith("java.") || base.equals("String") || base.equals("Integer")
                || base.equals("List") || base.equals("Map") || base.equals("Set")) return null;
        // 完全限定名直接命中
        if (typeMap.containsKey(base)) return base;
        // 同包
        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        if (!pkg.isEmpty() && typeMap.containsKey(pkg + "." + base)) return pkg + "." + base;
        // import 匹配
        for (var imp : cu.getImports()) {
            String impName = imp.getNameAsString();
            if (impName.endsWith("." + base) || impName.equals(base)) {
                if (typeMap.containsKey(impName)) return impName;
                if (imp.isAsterisk()) {
                    // 通配 import:查图内是否有该包下的简名
                    String prefix = impName.substring(0, impName.length() - 1); // 保留点号
                    for (String qn : typeMap.keySet()) {
                        if (qn.startsWith(prefix) && qn.substring(prefix.length()).equals(base)) return qn;
                    }
                }
            }
        }
        // 全局简名兜底
        return simpleNameToQn.get(base);
    }

    private void addTypeEdge(String from, String to, String kind,
                             List<Edge> edges, Set<String> dedup) {
        if (to == null || to.equals(from)) return;
        String key = from + "|" + to + "|" + kind;
        if (dedup.add(key)) edges.add(new Edge(from, to, kind));
    }

    /** 找到方法调用所处的所属方法,构造其 method id */
    private String enclosingMethodId(MethodCallExpr call, String typeQn) {
        var node = call.getParentNode();
        while (node.isPresent()) {
            var n = node.get();
            if (n instanceof CallableDeclaration<?> cd) {
                return methodId(typeQn, cd.getNameAsString(),
                        ((NodeWithParameters<?>) cd).getParameters().size());
            }
            node = n.getParentNode();
        }
        return null;
    }

    private String methodId(String typeQn, String name, int paramCount) {
        return typeQn + "." + name + "(" + paramCount + ")";
    }

    // ==================== 工具方法 ====================

    private String qualifiedName(TypeDeclaration<?> td, String pkg) {
        String name = td.getNameAsString();
        var parent = td.getParentNode();
        while (parent.isPresent() && !(parent.get() instanceof CompilationUnit)) {
            if (parent.get() instanceof TypeDeclaration<?> ptd) {
                name = ptd.getNameAsString() + "." + name;
            }
            parent = parent.get().getParentNode();
        }
        if (pkg == null || pkg.isEmpty()) return name;
        return pkg + "." + name;
    }

    private String kindOf(TypeDeclaration<?> td) {
        if (td instanceof ClassOrInterfaceDeclaration cid) return cid.isInterface() ? "interface" : "class";
        if (td instanceof EnumDeclaration) return "enum";
        if (td instanceof RecordDeclaration) return "record";
        return "class";
    }

    private String visibilityOf(com.github.javaparser.ast.nodeTypes.NodeWithModifiers<?> decl) {
        return decl.getModifiers().stream()
                .map(m -> m.getKeyword().asString()).filter(k -> k.equals("public") || k.equals("private")
                        || k.equals("protected")).findFirst().orElse("package-private");
    }

    private boolean isAbstract(TypeDeclaration<?> td) {
        return td.getModifiers().stream().anyMatch(m -> m.getKeyword().asString().equals("abstract"));
    }

    /** 识别源码根目录:标准 Maven/Gradle 结构,或目录本身即源码根 */
    private List<Path> findSourceRoots(Path root) throws IOException {
        Set<Path> roots = new LinkedHashSet<>();
        try (Stream<Path> walk = Files.walk(root, 8)) {
            walk.filter(Files::isDirectory)
                    .filter(p -> !p.toString().contains("/target/") && !p.toString().contains("/.git/"))
                    .filter(p -> {
                        String s = p.toString();
                        return s.endsWith("src/main/java") || s.endsWith("src/test/java");
                    })
                    .forEach(roots::add);
        }
        if (roots.isEmpty()) roots.add(root);
        return List.copyOf(roots);
    }

    private List<CompilationUnit> parseAll(JavaParser parser, Path root) throws IOException {
        List<CompilationUnit> units = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .forEach(p -> {
                        try {
                            parser.parse(ParseStart.COMPILATION_UNIT,
                                    Providers.provider(p, java.nio.charset.StandardCharsets.UTF_8))
                                    .getResult().ifPresent(units::add);
                        } catch (Exception e) {
                            log.warn("解析失败,跳过 {}", p, e);
                        }
                    });
        }
        return units;
    }
}
