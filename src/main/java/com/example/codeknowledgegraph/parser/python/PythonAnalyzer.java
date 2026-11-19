package com.example.codeknowledgegraph.parser.python;

import com.example.codeknowledgegraph.parser.LanguageParser;
import com.example.codeknowledgegraph.parser.model.AnalysisResult.Edge;
import com.example.codeknowledgegraph.parser.model.AnalysisResult.FieldInfo;
import com.example.codeknowledgegraph.parser.model.AnalysisResult.MethodInfo;
import com.example.codeknowledgegraph.parser.model.AnalysisResult.TypeInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSPoint;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterPython;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * F5.1: 基于 tree-sitter 的 Python 结构解析器。
 * 模型映射: .py 文件 -> Type(kind=module); 顶层函数 -> Method(owner=module);
 * class -> Type(kind=class); docstring -> javadoc; 装饰器 -> annotations
 * (Flask/FastAPI 风格路由装饰器顺带提取为 REST 端点)。
 * 跨文件调用/导入为按名启发式,由调用方基于全局索引二次解析。
 */
@Component
public class PythonAnalyzer implements LanguageParser {

    private static final Logger log = LoggerFactory.getLogger(PythonAnalyzer.class);

    @Override
    public Set<String> extensions() {
        return Set.of(".py");
    }

    @Override
    public FileStructure parseFile(Path root, Path file) throws Exception {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);

        TSParser parser = new TSParser();
        parser.setLanguage(new TreeSitterPython());
        TSTree tree = parser.parseString(null, source);
        TSNode ast = tree.getRootNode();

        String moduleQn = moduleQn(root, file);
        String packageName = moduleQn.contains(".") ? moduleQn.substring(0, moduleQn.lastIndexOf('.')) : "";

        List<TypeInfo> types = new ArrayList<>();
        List<MethodInfo> methods = new ArrayList<>();
        List<FieldInfo> fields = new ArrayList<>();
        List<Edge> localEdges = new ArrayList<>();
        List<FileStructure.NameRef> calls = new ArrayList<>();
        List<FileStructure.NameRef> imports = new ArrayList<>();
        Set<String> packages = new LinkedHashSet<>();
        if (!packageName.isEmpty()) packages.add(packageName);

        int lineCount = countLines(bytes);
        String relPath = relPath(root, file);
        // module 自身也是一个 Type(容器),顶层函数挂在其下
        types.add(new TypeInfo(moduleQn, lastSegment(moduleQn), packageName, "module",
                "public", false, lineCount, relPath, 1, lineCount,
                docstringOf(ast, bytes)));

        walkStatements(ast, bytes, moduleQn, packageName, relPath,
                types, methods, fields, localEdges, calls, imports);

        log.debug("[python] {}: {} 类型 / {} 方法 / {} 调用引用",
                file.getFileName(), types.size(), methods.size(), calls.size());
        return new FileStructure("python", moduleQn, types, methods, fields,
                localEdges, calls, imports);
    }

    // ==================== 遍历 ====================

    private void walkStatements(TSNode scope, byte[] bytes, String ownerQn,
                                 String packageName, String filePath,
                                 List<TypeInfo> types, List<MethodInfo> methods,
                                 List<FieldInfo> fields, List<Edge> localEdges,
                                 List<FileStructure.NameRef> calls,
                                 List<FileStructure.NameRef> imports) {
        for (int i = 0; i < scope.getNamedChildCount(); i++) {
            TSNode child = scope.getNamedChild(i);
            String childType = child.getType();
            if (childType.equals("import_statement") || childType.equals("import_from_statement")) {
                collectImports(child, bytes, imports);
                continue;
            }
            TSNode definition = unwrapDecorated(child);
            if (definition != null && definition.getType().equals("class_definition")) {
                collectClass(definition, decoratorsOf(child, bytes), bytes, ownerQn, packageName, filePath,
                        types, methods, fields, localEdges, calls);
                continue;
            }
            if (definition != null && definition.getType().equals("function_definition")) {
                collectFunction(definition, decoratorsOf(child, bytes), bytes, ownerQn, filePath,
                        methods, calls, true);
                continue;
            }
            // 其余语句: 提取模块级属性(assignment 左值为 identifier)
            if (childType.equals("expression_statement")) {
                TSNode assign = child.getNamedChild(0);
                if (assign != null && assign.getType().equals("assignment")) {
                    TSNode left = assign.getNamedChild(0);
                    if (left != null && left.getType().equals("identifier")) {
                        String fname = text(left, bytes);
                        if (fname != null) {
                            fields.add(new FieldInfo(ownerQn + "." + fname, ownerQn,
                                    fname, "", "public", false));
                        }
                    }
                }
            }
        }
    }

    private void collectClass(TSNode classDef, List<String> decorators, byte[] bytes,
                               String ownerQn, String packageName, String filePath,
                               List<TypeInfo> types, List<MethodInfo> methods,
                               List<FieldInfo> fields, List<Edge> localEdges,
                               List<FileStructure.NameRef> calls) {
        String name = fieldNameText(classDef, "name", bytes);
        if (name == null) return;
        String classQn = ownerQn + "." + name;
        TSNode body = fieldNode(classDef, "body");
        int startLine = lineOf(classDef.getStartPoint());
        int endLine = lineOf(classDef.getEndPoint());
        types.add(new TypeInfo(classQn, name, packageName, "class", "public",
                false, endLine - startLine + 1, filePath, startLine, endLine,
                docstringOf(body, bytes)));

        // 基类(跨文件基类由全局解析按名称匹配 EXTENDS)
        TSNode supers = fieldNode(classDef, "superclasses");
        if (supers != null) {
            for (int i = 0; i < supers.getNamedChildCount(); i++) {
                String base = text(supers.getNamedChild(i), bytes);
                if (base != null && !base.isBlank()) {
                    localEdges.add(new Edge(classQn, base.strip(), "EXTENDS"));
                }
            }
        }
        for (String d : decorators) collectDecoratorRef(classQn, d, calls);

        // 类体: 方法与类级属性
        if (body == null) return;
        for (int i = 0; i < body.getNamedChildCount(); i++) {
            TSNode child = body.getNamedChild(i);
            TSNode methodDef = unwrapDecorated(child);
            if (methodDef != null && methodDef.getType().equals("function_definition")) {
                List<String> mDecorators = decoratorsOf(child, bytes);
                collectFunction(methodDef, mDecorators, bytes, classQn, filePath, methods, calls, false);
                continue;
            }
            // 类级属性: assignment 左值为 identifier
            if (child.getType().equals("expression_statement")) {
                TSNode assign = child.getNamedChild(0);
                if (assign != null && assign.getType().equals("assignment")) {
                    TSNode left = assign.getNamedChild(0);
                    if (left != null && left.getType().equals("identifier")) {
                        String fname = text(left, bytes);
                        if (fname != null) {
                            fields.add(new FieldInfo(classQn + "." + fname, classQn,
                                    fname, "", "public", false));
                        }
                    }
                }
            }
        }
    }

    private void collectFunction(TSNode funcDef, List<String> decorators, byte[] bytes,
                                  String ownerQn, String filePath,
                                  List<MethodInfo> methods, List<FileStructure.NameRef> calls,
                                  boolean isTopLevel) {
        String name = fieldNameText(funcDef, "name", bytes);
        if (name == null) return;
        TSNode params = fieldNode(funcDef, "parameters");
        int paramCount = params == null ? 0 : params.getNamedChildCount();
        if (!isTopLevel && paramCount > 0) {
            TSNode first = params.getNamedChild(0);
            String firstText = first == null ? "" : text(first, bytes);
            if (firstText != null && (firstText.equals("self") || firstText.equals("cls")
                    || firstText.startsWith("self:") || firstText.startsWith("cls:"))) {
                paramCount--;
            }
        }
        String id = ownerQn + "." + name + "(" + Math.max(0, paramCount) + ")";
        TSNode body = fieldNode(funcDef, "body");
        int startLine = lineOf(funcDef.getStartPoint());
        int endLine = lineOf(funcDef.getEndPoint());
        String[] rest = restEndpoint(decorators);
        methods.add(new MethodInfo(id, ownerQn, name,
                name + "(" + Math.max(0, paramCount) + " args)",
                "", "public", true, false,
                filePath, startLine, endLine, docstringOf(body, bytes),
                decorators.stream().map(d -> d.startsWith("@") ? d.substring(1) : d).toList(),
                rest[0], rest[1]));

        if (body != null) collectCalls(body, bytes, id, calls);
    }

    /** 收集函数体内所有 call 引用: f() -> "f"; obj.m() -> "obj.m"; self.m() -> "self.m" */
    private void collectCalls(TSNode node, byte[] bytes, String fromMethodId,
                               List<FileStructure.NameRef> calls) {
        if (node.getType().equals("call")) {
            TSNode fn = fieldNode(node, "function");
            if (fn != null) {
                String ref = callRef(fn, bytes);
                if (ref != null) calls.add(new FileStructure.NameRef(fromMethodId, ref));
            }
        }
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            collectCalls(node.getNamedChild(i), bytes, fromMethodId, calls);
        }
    }

    private String callRef(TSNode fn, byte[] bytes) {
        if (fn.getType().equals("identifier")) return text(fn, bytes);
        if (fn.getType().equals("attribute")) {
            TSNode obj = fieldNode(fn, "object");
            TSNode attr = fieldNode(fn, "attribute");
            String attrName = attr == null ? null : text(attr, bytes);
            if (attrName == null) return null;
            String objName = obj == null ? null : text(obj, bytes);
            return objName == null ? attrName : objName + "." + attrName;
        }
        return null;
    }

    /** import 语句:提取被导入名称(from a.b import C -> "a.b.C"; import a.b -> "a.b") */
    private void collectImports(TSNode node, byte[] bytes, List<FileStructure.NameRef> imports) {
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            TSNode c = node.getNamedChild(i);
            String t = c.getType();
            if (t.equals("dotted_name") || t.equals("aliased_import")) {
                String name = text(c, bytes);
                if (name != null && !name.isBlank()) {
                    imports.add(new FileStructure.NameRef("", name.strip()));
                }
            }
        }
    }

    private void collectDecoratorRef(String owner, String decorator,
                                      List<FileStructure.NameRef> calls) {
        String d = decorator.startsWith("@") ? decorator.substring(1) : decorator;
        int paren = d.indexOf('(');
        if (paren > 0) calls.add(new FileStructure.NameRef(owner, d.substring(0, paren)));
    }

    // ==================== REST 端点(FastAPI/Flask 风格装饰器) ====================

    private String[] restEndpoint(List<String> decorators) {
        for (String d : decorators) {
            String s = d.startsWith("@") ? d.substring(1) : d;
            int paren = s.indexOf('(');
            if (paren < 0) continue;
            String head = s.substring(0, paren);   // 如 app.get / router.post / route
            int dot = head.lastIndexOf('.');
            String tail = dot >= 0 ? head.substring(dot + 1) : head;
            String method = switch (tail) {
                case "get", "route_get" -> "GET";
                case "post" -> "POST";
                case "put" -> "PUT";
                case "delete" -> "DELETE";
                case "patch" -> "PATCH";
                default -> null;
            };
            if (method == null && (tail.equals("route") || tail.equals("api_route"))) method = "ANY";
            if (method == null) continue;
            String path = stringArg(s.substring(paren));
            String full = path == null ? "" : path;
            if (!full.isEmpty() && !full.startsWith("/")) full = "/" + full;
            if (full.isEmpty()) full = "/";
            return new String[]{method, full};
        }
        return new String[]{null, null};
    }

    /** 从 "(...)" 中提取第一个字符串字面量参数 */
    private String stringArg(String parens) {
        int q = parens.indexOf('"');
        if (q < 0) q = parens.indexOf('\'');
        if (q < 0) return null;
        char quote = parens.charAt(q);
        int end = parens.indexOf(quote, q + 1);
        if (end < 0) return null;
        return parens.substring(q + 1, end);
    }

    // ==================== 工具方法 ====================

    private TSNode unwrapDecorated(TSNode node) {
        if (node.getType().equals("decorated_definition")) {
            for (int i = 0; i < node.getNamedChildCount(); i++) {
                TSNode c = node.getNamedChild(i);
                if (c.getType().equals("class_definition") || c.getType().equals("function_definition")) {
                    return c;
                }
            }
            return null;
        }
        return node;
    }

    private List<String> decoratorsOf(TSNode node, byte[] bytes) {
        List<String> out = new ArrayList<>();
        if (node.getType().equals("decorated_definition")) {
            for (int i = 0; i < node.getNamedChildCount(); i++) {
                TSNode c = node.getNamedChild(i);
                if (c.getType().equals("decorator")) {
                    String t = text(c, bytes);
                    if (t != null) out.add(t.strip());
                }
            }
        }
        return out;
    }

    private String docstringOf(TSNode body, byte[] bytes) {
        if (body == null) return null;
        for (int i = 0; i < body.getNamedChildCount(); i++) {
            TSNode stmt = body.getNamedChild(i);
            if (!stmt.getType().equals("expression_statement")) continue;
            TSNode expr = stmt.getNamedChild(0);
            if (expr == null || !expr.getType().equals("string")) continue;
            String raw = text(expr, bytes);
            if (raw == null) return null;
            String doc = raw.replaceAll("\"\"\"|'''", "").strip();
            doc = doc.replaceAll("\\s+", " ");
            return doc.isEmpty() ? null : (doc.length() > 200 ? doc.substring(0, 200) + "…" : doc);
        }
        return null;
    }

    private String moduleQn(Path root, Path file) {
        Path rel = root.toAbsolutePath().relativize(file.toAbsolutePath());
        String noExt = rel.toString().replace('\\', '/');
        if (noExt.endsWith(".py")) noExt = noExt.substring(0, noExt.length() - 3);
        if (noExt.endsWith("/__init__")) noExt = noExt.substring(0, noExt.length() - "/__init__".length());
        return noExt.replace('/', '.');
    }

    private String relPath(Path root, Path file) {
        return root.toAbsolutePath().relativize(file.toAbsolutePath()).toString().replace('\\', '/');
    }

    private String lastSegment(String qn) {
        return qn.substring(qn.lastIndexOf('.') + 1);
    }

    private int countLines(byte[] bytes) {
        int lines = 1;
        for (byte b : bytes) if (b == '\n') lines++;
        return lines;
    }

    private int lineOf(TSPoint point) {
        return point.getRow() + 1; // tree-sitter 行号 0-based -> 1-based
    }

    private TSNode fieldNode(TSNode node, String field) {
        TSNode n = node.getChildByFieldName(field);
        return n == null || n.isNull() ? null : n;
    }

    private String fieldNameText(TSNode node, String field, byte[] bytes) {
        TSNode n = fieldNode(node, field);
        return n == null ? null : text(n, bytes);
    }

    private String text(TSNode node, byte[] bytes) {
        int s = node.getStartByte();
        int e = node.getEndByte();
        if (s < 0 || e > bytes.length || s > e) return null;
        return new String(bytes, s, e - s, StandardCharsets.UTF_8);
    }
}
