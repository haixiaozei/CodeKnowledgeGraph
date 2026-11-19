package com.example.codeknowledgegraph.parser.typescript;

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
import org.treesitter.TreeSitterTsx;
import org.treesitter.TreeSitterTypescript;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * F5.1b: 基于 tree-sitter 的 TypeScript/TSX 结构解析器。
 * 模型映射: 文件 -> Type(kind=module); class -> class; interface -> interface;
 * 顶层函数(含 const x = () => {}) -> Method(owner=module); JSDoc -> javadoc;
 * 装饰器 -> annotations(NestJS 风格 @Get('/x') 提取为 REST 端点)。
 * 跨文件调用/导入为按名启发式,由全局索引二次解析。
 */
@Component
public class TypeScriptAnalyzer implements LanguageParser {

    private static final Logger log = LoggerFactory.getLogger(TypeScriptAnalyzer.class);

    @Override
    public Set<String> extensions() {
        return Set.of(".ts", ".tsx");
    }

    @Override
    public FileStructure parseFile(Path root, Path file) throws Exception {
        // .d.ts 是类型声明文件,会产生大量重复模块,不入图
        if (file.getFileName().toString().endsWith(".d.ts")) {
            return new FileStructure("typescript", "", List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of());
        }
        String source = Files.readString(file, StandardCharsets.UTF_8);
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        boolean isTsx = file.toString().endsWith(".tsx");

        TSParser parser = new TSParser();
        parser.setLanguage(isTsx ? new TreeSitterTsx() : new TreeSitterTypescript());
        TSTree tree = parser.parseString(null, source);
        TSNode ast = tree.getRootNode();

        String moduleQn = moduleQn(root, file);
        String packageName = moduleQn.contains(".") ? moduleQn.substring(0, moduleQn.lastIndexOf('.')) : "";
        String relPath = relPath(root, file);

        List<TypeInfo> types = new ArrayList<>();
        List<MethodInfo> methods = new ArrayList<>();
        List<FieldInfo> fields = new ArrayList<>();
        List<Edge> localEdges = new ArrayList<>();
        List<FileStructure.NameRef> calls = new ArrayList<>();
        List<FileStructure.NameRef> imports = new ArrayList<>();
        List<String> packages = new ArrayList<>();
        if (!packageName.isEmpty()) packages.add(packageName);

        int lineCount = countLines(bytes);
        types.add(new TypeInfo(moduleQn, lastSegment(moduleQn), packageName, "module",
                "public", false, lineCount, relPath, 1, lineCount, null));

        walk(ast, bytes, moduleQn, packageName, relPath, types, methods, fields, localEdges, calls, imports);

        log.debug("[typescript] {}: {} 类型 / {} 方法", file.getFileName(), types.size(), methods.size());
        return new FileStructure("typescript", moduleQn, types, methods, fields, packages,
                localEdges, calls, imports);
    }

    // ==================== 遍历 ====================

    private void walk(TSNode scope, byte[] bytes, String ownerQn, String packageName, String filePath,
                       List<TypeInfo> types, List<MethodInfo> methods, List<FieldInfo> fields,
                       List<Edge> localEdges, List<FileStructure.NameRef> calls,
                       List<FileStructure.NameRef> imports) {
        String pendingDoc = null;
        for (int i = 0; i < scope.getNamedChildCount(); i++) {
            TSNode child = scope.getNamedChild(i);
            String type = child.getType();

            if (type.equals("comment")) {
                String text = text(child, bytes);
                pendingDoc = text != null && text.strip().startsWith("/**") ? normalizeDoc(text) : pendingDoc;
                continue;
            }
            if (type.equals("import_statement")) {
                collectImports(child, bytes, imports);
                continue;
            }

            TSNode definition = unwrapExport(child);
            if (definition == null) continue;
            List<String> decorators = new ArrayList<>(decoratorsOf(child, bytes));
            decoratorsOf(definition, bytes).forEach(d -> { if (!decorators.contains(d)) decorators.add(d); });
            String doc = pendingDoc;
            pendingDoc = null;

            switch (definition.getType()) {
                case "class_declaration" -> collectClass(definition, decorators, doc, bytes, ownerQn,
                        packageName, filePath, types, methods, fields, localEdges, calls);
                case "interface_declaration" -> collectInterface(definition, doc, bytes, ownerQn,
                        packageName, filePath, types, methods);
                case "function_declaration" -> collectFunction(definition, decorators, doc, bytes,
                        ownerQn, filePath, methods, calls, true);
                case "lexical_declaration", "variable_declaration" -> {
                    // const x = () => {} / function 表达式
                    TSNode declarator = definition.getNamedChild(0);
                    if (declarator != null && declarator.getType().equals("variable_declarator")) {
                        TSNode value = fieldNode(declarator, "value");
                        if (value != null && (value.getType().equals("arrow_function")
                                || value.getType().equals("function_expression")
                                || value.getType().equals("function"))) {
                            collectArrowFunction(declarator, value, decorators, doc, bytes,
                                    ownerQn, filePath, methods, calls, true);
                        } else if (declarator.getType().equals("variable_declarator")) {
                            // 模块级常量
                            TSNode nameNode = fieldNode(declarator, "name");
                            String fname = nameNode == null ? null : text(nameNode, bytes);
                            if (fname != null) {
                                fields.add(new FieldInfo(ownerQn + "." + fname, ownerQn,
                                        fname, "", "public", true));
                            }
                        }
                    }
                }
                default -> { /* 其他顶层语句忽略 */ }
            }
        }
    }

    private void collectClass(TSNode classDef, List<String> decorators, String doc, byte[] bytes,
                               String ownerQn, String packageName, String filePath,
                               List<TypeInfo> types, List<MethodInfo> methods, List<FieldInfo> fields,
                               List<Edge> localEdges, List<FileStructure.NameRef> calls) {
        TSNode nameNode = fieldNode(classDef, "name");
        String name = nameNode == null ? null : text(nameNode, bytes);
        if (name == null) return;
        String classQn = ownerQn + "." + name;
        TSNode body = fieldNode(classDef, "body");
        int startLine = lineOf(classDef.getStartPoint());
        int endLine = lineOf(classDef.getEndPoint());
        types.add(new TypeInfo(classQn, name, packageName, "class", "public",
                hasModifier(classDef, "abstract"), endLine - startLine + 1, filePath,
                startLine, endLine, doc));

        // extends / implements(图内名称,全局解析二次匹配)
        for (int i = 0; i < classDef.getNamedChildCount(); i++) {
            TSNode c = classDef.getNamedChild(i);
            if (!c.getType().equals("class_heritage") && !c.getType().equals("extends_clause")) continue;
            for (int j = 0; j < c.getNamedChildCount(); j++) {
                TSNode clause = c.getNamedChild(j);
                String kind = switch (clause.getType()) {
                    case "extends_clause" -> "EXTENDS";
                    case "implements_clause" -> "IMPLEMENTS";
                    default -> null;
                };
                if (kind == null) continue;
                TSNode t = clause.getNamedChild(0);
                String base = t == null ? null : text(t, bytes);
                if (base != null && !base.isBlank()) {
                    localEdges.add(new Edge(classQn, base.strip(), kind));
                }
            }
        }
        for (String d : decorators) collectDecoratorRef(classQn, d, calls);

        if (body == null) return;
        List<String> pendingDecorators = new ArrayList<>();
        for (int i = 0; i < body.getNamedChildCount(); i++) {
            TSNode child = body.getNamedChild(i);

            // 装饰器是独立子节点,归属于其后第一个成员
            if (child.getType().equals("decorator")) {
                String t = text(child, bytes);
                if (t != null) pendingDecorators.add(t.strip());
                continue;
            }

            TSNode methodDef = unwrapDecorated(child);
            String mType = methodDef == null ? "" : methodDef.getType();
            if (mType.equals("method_definition")) {
                List<String> mDecorators = new ArrayList<>(pendingDecorators);
                decoratorsOf(methodDef, bytes).forEach(d -> { if (!mDecorators.contains(d)) mDecorators.add(d); });
                collectMethodDefinition(methodDef, mDecorators, bytes, classQn, filePath, methods, calls);
                pendingDecorators.clear();
                continue;
            }
            if (mType.equals("property_definition") || mType.equals("public_field_definition")
                    || mType.equals("field_definition")) {
                TSNode nameNode2 = fieldNode(methodDef, "name");
                String fname = nameNode2 == null ? null : text(nameNode2, bytes);
                if (fname != null) {
                    fields.add(new FieldInfo(classQn + "." + fname, classQn, fname, "", "public", false));
                }
                pendingDecorators.clear();
            }
            // abstract_method_signature 等签名 v1 忽略
        }
    }

    private void collectInterface(TSNode ifaceDef, String doc, byte[] bytes, String ownerQn,
                                   String packageName, String filePath,
                                   List<TypeInfo> types, List<MethodInfo> methods) {
        TSNode nameNode = fieldNode(ifaceDef, "name");
        String name = nameNode == null ? null : text(nameNode, bytes);
        if (name == null) return;
        String qn = ownerQn + "." + name;
        TSNode body = fieldNode(ifaceDef, "body");
        int startLine = lineOf(ifaceDef.getStartPoint());
        int endLine = lineOf(ifaceDef.getEndPoint());
        types.add(new TypeInfo(qn, name, packageName, "interface", "public",
                false, endLine - startLine + 1, filePath, startLine, endLine, doc));
        if (body == null) return;
        for (int i = 0; i < body.getNamedChildCount(); i++) {
            TSNode sig = body.getNamedChild(i);
            if (!sig.getType().equals("method_signature")) continue;
            TSNode nameNode2 = fieldNode(sig, "name");
            String mName = nameNode2 == null ? null : text(nameNode2, bytes);
            if (mName == null) continue;
            TSNode params = fieldNode(sig, "parameters");
            int paramCount = params == null ? 0 : params.getNamedChildCount();
            methods.add(new MethodInfo(qn + "." + mName + "(" + paramCount + ")", qn, mName,
                    mName + "(" + paramCount + " args)", "", "public", false, false,
                    filePath, lineOf(sig.getStartPoint()), lineOf(sig.getEndPoint()), null,
                    List.of(), null, null));
        }
    }

    private void collectFunction(TSNode funcDef, List<String> decorators, String doc, byte[] bytes,
                                  String ownerQn, String filePath, List<MethodInfo> methods,
                                  List<FileStructure.NameRef> calls, boolean isTopLevel) {
        TSNode nameNode = fieldNode(funcDef, "name");
        String name = nameNode == null ? null : text(nameNode, bytes);
        if (name == null) return;
        TSNode params = fieldNode(funcDef, "parameters");
        int paramCount = params == null ? 0 : params.getNamedChildCount();
        String id = ownerQn + "." + name + "(" + paramCount + ")";
        TSNode body = fieldNode(funcDef, "body");
        String[] rest = restEndpoint(decorators);
        methods.add(new MethodInfo(id, ownerQn, name, name + "(" + paramCount + " args)",
                "", "public", false, false,
                filePath, lineOf(funcDef.getStartPoint()), lineOf(funcDef.getEndPoint()), doc,
                decorators.stream().map(d -> d.startsWith("@") ? d.substring(1) : d).toList(),
                rest[0], rest[1]));
        if (body != null) collectCalls(body, bytes, id, calls);
    }

    /** const x = () => {} 形式: 名称来自 declarator,函数体来自 value */
    private void collectArrowFunction(TSNode declarator, TSNode value, List<String> decorators,
                                       String doc, byte[] bytes, String ownerQn, String filePath,
                                       List<MethodInfo> methods, List<FileStructure.NameRef> calls,
                                       boolean isTopLevel) {
        TSNode nameNode = fieldNode(declarator, "name");
        String name = nameNode == null ? null : text(nameNode, bytes);
        if (name == null) return;
        TSNode params = fieldNode(value, "parameters");
        int paramCount = params == null ? 0 : params.getNamedChildCount();
        String id = ownerQn + "." + name + "(" + paramCount + ")";
        TSNode body = fieldNode(value, "body");
        String[] rest = restEndpoint(decorators);
        methods.add(new MethodInfo(id, ownerQn, name, name + "(" + paramCount + " args)",
                "", "public", true, false,
                filePath, lineOf(value.getStartPoint()), lineOf(value.getEndPoint()), doc,
                decorators.stream().map(d -> d.startsWith("@") ? d.substring(1) : d).toList(),
                rest[0], rest[1]));
        if (body != null) collectCalls(body, bytes, id, calls);
    }

    private void collectMethodDefinition(TSNode methodDef, List<String> decorators, byte[] bytes,
                                          String classQn, String filePath,
                                          List<MethodInfo> methods, List<FileStructure.NameRef> calls) {
        TSNode nameNode = fieldNode(methodDef, "name");
        String name = nameNode == null ? null : text(nameNode, bytes);
        if (name == null) return;
        // 参数计数: 构造器/方法去掉 this
        TSNode params = fieldNode(methodDef, "parameters");
        int paramCount = params == null ? 0 : params.getNamedChildCount();
        if (paramCount > 0) {
            TSNode first = params.getNamedChild(0);
            String firstText = first == null ? "" : text(first, bytes);
            if (firstText != null && (firstText.equals("this") || firstText.startsWith("this:"))) paramCount--;
        }
        boolean isCtor = name.equals("constructor");
        String id = classQn + "." + (isCtor ? "constructor" : name) + "(" + paramCount + ")";
        TSNode body = fieldNode(methodDef, "body");
        String[] rest = restEndpoint(decorators);
        methods.add(new MethodInfo(id, classQn, isCtor ? "constructor" : name,
                name + "(" + paramCount + " args)", "", "public", false, isCtor,
                filePath, lineOf(methodDef.getStartPoint()), lineOf(methodDef.getEndPoint()), null,
                decorators.stream().map(d -> d.startsWith("@") ? d.substring(1) : d).toList(),
                rest[0], rest[1]));
        if (body != null) collectCalls(body, bytes, id, calls);
    }

    // ==================== 调用 / 导入 / REST ====================

    private void collectCalls(TSNode node, byte[] bytes, String fromMethodId,
                               List<FileStructure.NameRef> calls) {
        if (node.getType().equals("call_expression") || node.getType().equals("new_expression")) {
            String fnField = node.getType().equals("new_expression") ? "constructor" : "function";
            TSNode fn = fieldNode(node, fnField);
            if (fn != null) {
                String ref = callRef(fn, bytes);
                if (ref != null) {
                    TSNode args = fieldNode(node, "arguments");
                    int argCount = args == null ? 0 : args.getNamedChildCount();
                    calls.add(new FileStructure.NameRef(fromMethodId, ref, argCount));
                }
            }
        }
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            collectCalls(node.getNamedChild(i), bytes, fromMethodId, calls);
        }
    }

    private String callRef(TSNode fn, byte[] bytes) {
        if (fn.getType().equals("identifier")) return text(fn, bytes);
        if (fn.getType().equals("member_expression")) {
            TSNode obj = fieldNode(fn, "object");
            TSNode prop = fieldNode(fn, "property");
            String propName = prop == null ? null : text(prop, bytes);
            if (propName == null) return null;
            String objName = obj == null ? null : text(obj, bytes);
            return objName == null ? propName : objName + "." + propName;
        }
        return null;
    }

    private void collectImports(TSNode node, byte[] bytes, List<FileStructure.NameRef> imports) {
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            TSNode c = node.getNamedChild(i);
            if (c.getType().equals("string")) {
                String name = text(c, bytes);
                if (name != null) {
                    imports.add(new FileStructure.NameRef("", name.replaceAll("^['\"]|['\"]$", ""), -1));
                }
            }
        }
    }

    private void collectDecoratorRef(String owner, String decorator,
                                      List<FileStructure.NameRef> calls) {
        String d = decorator.startsWith("@") ? decorator.substring(1) : decorator;
        int paren = d.indexOf('(');
        if (paren > 0) calls.add(new FileStructure.NameRef(owner, d.substring(0, paren), -1));
    }

    /** NestJS 风格装饰器路由: @Get('/x') / @Post ... */
    private String[] restEndpoint(List<String> decorators) {
        for (String d : decorators) {
            String s = d.startsWith("@") ? d.substring(1) : d;
            int paren = s.indexOf('(');
            if (paren < 0) continue;
            String head = s.substring(0, paren);
            int dot = head.lastIndexOf('.');
            String tail = dot >= 0 ? head.substring(dot + 1) : head;
            String method = switch (tail.toLowerCase()) {
                case "get" -> "GET";
                case "post" -> "POST";
                case "put" -> "PUT";
                case "delete" -> "DELETE";
                case "patch" -> "PATCH";
                case "all", "route", "api_route" -> "ANY";
                default -> null;
            };
            if (method == null) continue;
            String path = stringArg(s.substring(paren));
            String full = path == null ? "" : path;
            if (!full.isEmpty() && !full.startsWith("/")) full = "/" + full;
            if (full.isEmpty()) full = "/";
            return new String[]{method, full};
        }
        return new String[]{null, null};
    }

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

    /** export 语句解包;装饰器视为声明自身的一部分(tree-sitter TS 装饰器是声明的子节点) */
    private TSNode unwrapExport(TSNode node) {
        if (node.getType().equals("export_statement")) {
            for (int i = 0; i < node.getNamedChildCount(); i++) {
                TSNode c = node.getNamedChild(i);
                String t = c.getType();
                if (t.equals("class_declaration") || t.equals("function_declaration")
                        || t.equals("interface_declaration") || t.equals("lexical_declaration")
                        || t.equals("variable_declaration")) {
                    return c;
                }
            }
            return null;
        }
        return node;
    }

    private TSNode unwrapDecorated(TSNode node) {
        return node; // TS 装饰器是声明节点的子节点,无需解包
    }

    private List<String> decoratorsOf(TSNode node, byte[] bytes) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            TSNode c = node.getNamedChild(i);
            if (c.getType().equals("decorator")) {
                String t = text(c, bytes);
                if (t != null) out.add(t.strip());
            }
        }
        return out;
    }

    private boolean hasModifier(TSNode node, String keyword) {
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            TSNode c = node.getNamedChild(i);
            if (c.getType().equals("abstract")) return true;
            if (c.getType().equals(keyword)) return true;
        }
        return false;
    }

    private String normalizeDoc(String raw) {
        String doc = raw.replaceAll("/\\*\\*|\\*/", "").replaceAll("^\\s*\\*\\s?", " ")
                .replaceAll("\\s+", " ").strip();
        return doc.isEmpty() ? null : (doc.length() > 200 ? doc.substring(0, 200) + "…" : doc);
    }

    private String moduleQn(Path root, Path file) {
        Path rel = root.toAbsolutePath().relativize(file.toAbsolutePath());
        String noExt = rel.toString().replace('\\', '/');
        if (noExt.endsWith(".tsx") || noExt.endsWith(".ts")) noExt = noExt.substring(0, noExt.lastIndexOf('.'));
        if (noExt.endsWith("/index")) noExt = noExt.substring(0, noExt.length() - "/index".length());
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
        return point.getRow() + 1;
    }

    private TSNode fieldNode(TSNode node, String field) {
        TSNode n = node.getChildByFieldName(field);
        return n == null || n.isNull() ? null : n;
    }

    private String text(TSNode node, byte[] bytes) {
        int s = node.getStartByte();
        int e = node.getEndByte();
        if (s < 0 || e > bytes.length || s > e) return null;
        return new String(bytes, s, e - s, StandardCharsets.UTF_8);
    }
}
