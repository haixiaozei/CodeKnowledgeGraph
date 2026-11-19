package com.example.codeknowledgegraph.parser.go;

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
import org.treesitter.TreeSitterGo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * F5.1b: 基于 tree-sitter 的 Go 结构解析器。
 * 模型映射: 目录 -> Type(kind=module, 简名=包名); struct -> class; interface -> interface;
 * 函数 -> Method(owner=module); 方法(receiver) -> Method(owner=struct);
 * 注释块 -> javadoc; 跨文件调用为按名启发式(包内函数/方法),接收者变量类型推断 v1 不做。
 */
@Component
public class GoAnalyzer implements LanguageParser {

    private static final Logger log = LoggerFactory.getLogger(GoAnalyzer.class);

    @Override
    public Set<String> extensions() {
        return Set.of(".go");
    }

    @Override
    public FileStructure parseFile(Path root, Path file) throws Exception {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);

        TSParser parser = new TSParser();
        parser.setLanguage(new TreeSitterGo());
        TSTree tree = parser.parseString(null, source);
        TSNode ast = tree.getRootNode();

        String moduleQn = moduleQn(root, file);
        String packageName = packageOf(ast, bytes);
        String displayPackage = packageName.isEmpty() ? lastSegment(moduleQn) : packageName;
        String relPath = relPath(root, file);

        List<TypeInfo> types = new ArrayList<>();
        List<MethodInfo> methods = new ArrayList<>();
        List<FieldInfo> fields = new ArrayList<>();
        List<Edge> localEdges = new ArrayList<>();
        List<FileStructure.NameRef> calls = new ArrayList<>();
        List<FileStructure.NameRef> imports = new ArrayList<>();
        List<String> packages = new ArrayList<>();
        if (!displayPackage.isEmpty()) packages.add(displayPackage);

        int lineCount = countLines(bytes);
        types.add(new TypeInfo(moduleQn, displayPackage, displayPackage, "module",
                "public", false, lineCount, relPath, 1, lineCount, null));

        walk(ast, bytes, moduleQn, displayPackage, relPath, types, methods, fields, localEdges, calls, imports);

        log.debug("[go] {}: {} 类型 / {} 方法", file.getFileName(), types.size(), methods.size());
        return new FileStructure("go", moduleQn, types, methods, fields, packages,
                localEdges, calls, imports);
    }

    // ==================== 遍历 ====================

    private void walk(TSNode scope, byte[] bytes, String moduleQn, String packageName, String filePath,
                       List<TypeInfo> types, List<MethodInfo> methods, List<FieldInfo> fields,
                       List<Edge> localEdges, List<FileStructure.NameRef> calls,
                       List<FileStructure.NameRef> imports) {
        String pendingDoc = null;
        for (int i = 0; i < scope.getNamedChildCount(); i++) {
            TSNode child = scope.getNamedChild(i);
            String type = child.getType();

            if (type.equals("comment")) {
                String text = text(child, bytes);
                if (text != null && text.strip().startsWith("//")) {
                    pendingDoc = (pendingDoc == null ? "" : pendingDoc + " ")
                            + text.strip().replaceAll("^//\\s?", "");
                    if (pendingDoc.length() > 200) pendingDoc = pendingDoc.substring(0, 200) + "…";
                }
                continue;
            }
            if (type.equals("import_declaration")) {
                collectImports(child, bytes, imports);
                pendingDoc = null;
                continue;
            }
            if (type.equals("package_clause")) { pendingDoc = null; continue; }

            if (type.equals("type_declaration")) {
                collectTypes(child, pendingDoc, bytes, moduleQn, packageName, filePath, types, fields);
                pendingDoc = null;
                continue;
            }
            if (type.equals("function_declaration")) {
                collectFunction(child, pendingDoc, bytes, moduleQn, filePath, methods, calls);
                pendingDoc = null;
                continue;
            }
            if (type.equals("method_declaration")) {
                collectMethod(child, pendingDoc, bytes, moduleQn, filePath, types, methods, calls);
                pendingDoc = null;
                continue;
            }
            pendingDoc = null;
        }
    }

    private void collectTypes(TSNode typeDecl, String doc, byte[] bytes, String moduleQn,
                               String packageName, String filePath,
                               List<TypeInfo> types, List<FieldInfo> fields) {
        for (int i = 0; i < typeDecl.getNamedChildCount(); i++) {
            TSNode spec = typeDecl.getNamedChild(i);
            if (!spec.getType().equals("type_spec")) continue;
            TSNode nameNode = fieldNode(spec, "name");
            String name = nameNode == null ? null : text(nameNode, bytes);
            TSNode value = fieldNode(spec, "type");
            if (name == null || value == null) continue;
            String kind = switch (value.getType()) {
                case "struct_type" -> "class";
                case "interface_type" -> "interface";
                default -> null; // 别名/基础类型 v1 不入图
            };
            if (kind == null) continue;
            String qn = moduleQn + "." + name;
            TSNode structBody = null;
            if (value.getType().equals("struct_type")) {
                for (int j = 0; j < value.getNamedChildCount(); j++) {
                    TSNode c = value.getNamedChild(j);
                    if (c.getType().equals("field_declaration_list")) { structBody = c; break; }
                }
            }
            int startLine = lineOf(spec.getStartPoint());
            int endLine = lineOf(spec.getEndPoint());
            types.add(new TypeInfo(qn, name, packageName, kind, "public", false,
                    endLine - startLine + 1, filePath, startLine, endLine, doc));
            // struct 字段
            if (structBody != null) {
                for (int j = 0; j < structBody.getNamedChildCount(); j++) {
                    TSNode fd = structBody.getNamedChild(j);
                    if (!fd.getType().equals("field_declaration")) continue;
                    TSNode fnameNode = fd.getNamedChild(0);
                    String fname = fnameNode == null ? null : text(fnameNode, bytes);
                    TSNode ftype = fieldNode(fd, "type");
                    String ftypeText = ftype == null ? "" : text(ftype, bytes);
                    if (fname != null) {
                        fields.add(new FieldInfo(qn + "." + fname, qn, fname,
                                ftypeText == null ? "" : ftypeText, "public", false));
                    }
                }
            }
        }
    }

    private void collectFunction(TSNode funcDecl, String doc, byte[] bytes, String moduleQn,
                                  String filePath, List<MethodInfo> methods,
                                  List<FileStructure.NameRef> calls) {
        TSNode nameNode = fieldNode(funcDecl, "name");
        String name = nameNode == null ? null : text(nameNode, bytes);
        if (name == null) return;
        TSNode params = fieldNode(funcDecl, "parameters");
        int paramCount = params == null ? 0 : params.getNamedChildCount();
        String id = moduleQn + "." + name + "(" + paramCount + ")";
        TSNode body = fieldNode(funcDecl, "body");
        methods.add(new MethodInfo(id, moduleQn, name, name + "(" + paramCount + " args)",
                "", "public", true, false,
                filePath, lineOf(funcDecl.getStartPoint()), lineOf(funcDecl.getEndPoint()), doc,
                List.of(), null, null));
        if (body != null) collectCalls(body, bytes, id, calls);
    }

    private void collectMethod(TSNode methodDecl, String doc, byte[] bytes, String moduleQn,
                                String filePath, List<TypeInfo> types, List<MethodInfo> methods,
                                List<FileStructure.NameRef> calls) {
        TSNode nameNode = fieldNode(methodDecl, "name");
        String name = nameNode == null ? null : text(nameNode, bytes);
        if (name == null) return;
        // receiver: (s *MyStruct) -> 结构体类型名
        String receiverType = receiverTypeName(methodDecl, bytes);
        if (receiverType == null) return;
        String ownerQn = moduleQn + "." + receiverType;
        TSNode params = fieldNode(methodDecl, "parameters");
        int paramCount = params == null ? 0 : params.getNamedChildCount();
        // 注意: Go 语法中 receiver 是独立字段,不在 parameters 内,无需减一
        String id = ownerQn + "." + name + "(" + paramCount + ")";
        TSNode body = fieldNode(methodDecl, "body");
        methods.add(new MethodInfo(id, ownerQn, name, name + "(" + paramCount + " args)",
                "", "public", false, false,
                filePath, lineOf(methodDecl.getStartPoint()), lineOf(methodDecl.getEndPoint()), doc,
                List.of(), null, null));
        if (body != null) collectCalls(body, bytes, id, calls);
    }

    /** receiver (s *MyStruct) / (s MyStruct) -> MyStruct */
    private String receiverTypeName(TSNode methodDecl, byte[] bytes) {
        TSNode receiver = fieldNode(methodDecl, "receiver");
        if (receiver == null) return null;
        TSNode param = receiver.getNamedChild(0);
        if (param == null) return null;
        TSNode type = fieldNode(param, "type");
        if (type == null) return null;
        // pointer_type -> 里面的 type_identifier
        TSNode base = type;
        if (type.getType().equals("pointer_type")) {
            base = type.getNamedChild(0);
            if (base == null) return null;
        }
        return text(base, bytes);
    }

    // ==================== 调用 / 导入 ====================

    private void collectCalls(TSNode node, byte[] bytes, String fromMethodId,
                               List<FileStructure.NameRef> calls) {
        if (node.getType().equals("call_expression")) {
            TSNode fn = fieldNode(node, "function");
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
        if (fn.getType().equals("selector_expression")) {
            TSNode operand = fieldNode(fn, "operand");
            TSNode field = fieldNode(fn, "field");
            String fieldName = field == null ? null : text(field, bytes);
            if (fieldName == null) return null;
            String opName = operand == null ? null : text(operand, bytes);
            return opName == null ? fieldName : opName + "." + fieldName;
        }
        return null;
    }

    private void collectImports(TSNode node, byte[] bytes, List<FileStructure.NameRef> imports) {
        // v1: 记录 import 路径;Go import 路径(URL)与模块名(目录)不对应,DEPENDS_ON 留待后续
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            TSNode c = node.getNamedChild(i);
            if (c.getType().equals("import_spec_list")) {
                for (int j = 0; j < c.getNamedChildCount(); j++) {
                    TSNode spec = c.getNamedChild(j);
                    if (!spec.getType().equals("import_spec")) continue;
                    TSNode pathNode = spec.getNamedChild(0);
                    String path = pathNode == null ? null : text(pathNode, bytes);
                    if (path != null) {
                        imports.add(new FileStructure.NameRef("", path.replaceAll("^\"|\"$", ""), -1));
                    }
                }
            }
        }
    }

    // ==================== 工具方法 ====================

    private String packageOf(TSNode ast, byte[] bytes) {
        for (int i = 0; i < ast.getNamedChildCount(); i++) {
            TSNode c = ast.getNamedChild(i);
            if (c.getType().equals("package_clause")) {
                TSNode name = fieldNode(c, "name");
                return name == null ? "" : text(name, bytes);
            }
        }
        return "";
    }

    private String moduleQn(Path root, Path file) {
        Path rel = root.toAbsolutePath().relativize(file.toAbsolutePath());
        String parent = rel.getParent() == null ? "" : rel.getParent().toString();
        if (parent.isEmpty()) return "main";
        return parent.replace('\\', '.').replace('/', '.');
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
