package com.example.codeknowledgegraph.parser.python;

import com.example.codeknowledgegraph.parser.LanguageParser.FileStructure;
import com.example.codeknowledgegraph.parser.model.AnalysisResult.MethodInfo;
import com.example.codeknowledgegraph.parser.model.AnalysisResult.TypeInfo;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** PythonAnalyzer 解析与 natives 加载冒烟测试 */
class PythonAnalyzerTest {

    private static final String SAMPLE = """
            \"\"\"模块文档: 示例工具包\"\"\"

            import os
            from . import helper

            MAX = 100

            class Greeter:
                \"\"\"打招呼的类\"\"\"

                def __init__(self, name):
                    self.name = name

                def greet(self, times=1):
                    \"\"\"重复打招呼\"\"\"
                    return helper.shout(self.name) and os.getcwd()

            def top_level(request):
                \"\"\"处理 ping 请求\"\"\"
                return Greeter("x").greet()

            @app.get("/ping")
            def ping_handler(request):
                return top_level(request)
            """;

    @Test
    void shouldParsePythonStructure() throws Exception {
        Path dir = Files.createTempDirectory("py-test");
        Path file = dir.resolve("demo/sample.py");
        Files.createDirectories(file.getParent());
        Files.writeString(file, SAMPLE);

        FileStructure fs = new PythonAnalyzer().parseFile(dir, file);

        // 模块
        assertEquals("demo.sample", fs.moduleQn());
        TypeInfo module = fs.types().stream()
                .filter(t -> t.kind().equals("module")).findFirst().orElseThrow();
        assertEquals("demo.sample", module.qualifiedName());
        assertNotNull(module.javadoc(), "模块 docstring 应被提取");
        assertTrue(module.javadoc().contains("示例工具包"));

        // 类
        TypeInfo greeter = fs.types().stream()
                .filter(t -> t.qualifiedName().equals("demo.sample.Greeter")).findFirst().orElseThrow();
        assertEquals("class", greeter.kind());
        assertEquals("打招呼的类", greeter.javadoc());
        assertEquals(8, greeter.startLine(), "class 位于样例第 8 行");
        assertTrue(greeter.endLine() > greeter.startLine());

        // 方法: __init__(1) [去掉 self] / greet(1)
        assertTrue(fs.methods().stream().anyMatch(m ->
                m.id().equals("demo.sample.Greeter.__init__(1)")));
        MethodInfo greet = fs.methods().stream()
                .filter(m -> m.id().equals("demo.sample.Greeter.greet(1)")).findFirst().orElseThrow();
        assertEquals("重复打招呼", greet.javadoc());

        // 顶层函数 + REST 装饰器端点
        MethodInfo topLevel = fs.methods().stream()
                .filter(m -> m.name().equals("top_level")).findFirst().orElseThrow();
        assertNull(topLevel.httpMethod(), "无装饰器不应是端点");
        MethodInfo ping = fs.methods().stream()
                .filter(m -> m.name().equals("ping_handler")).findFirst().orElseThrow();
        assertEquals("GET", ping.httpMethod());
        assertEquals("/ping", ping.httpPath());

        // 调用引用: helper.shout / os.getcwd / Greeter("x").greet
        List<String> refs = fs.unresolvedCalls().stream().map(FileStructure.NameRef::name).toList();
        assertTrue(refs.contains("helper.shout"), "应包含调用引用: " + refs);
        assertTrue(refs.contains("os.getcwd"));
        assertTrue(refs.stream().anyMatch(r -> r.startsWith("Greeter")), refs.toString());

        // 类级属性
        assertTrue(fs.fields().stream().anyMatch(f -> f.name().equals("MAX")));

        // 包名
        assertTrue(fs.types().stream().allMatch(t -> t.packageName().equals("demo")));
    }
}
