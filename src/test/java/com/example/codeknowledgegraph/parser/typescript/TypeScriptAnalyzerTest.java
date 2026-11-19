package com.example.codeknowledgegraph.parser.typescript;

import com.example.codeknowledgegraph.parser.LanguageParser.FileStructure;
import com.example.codeknowledgegraph.parser.model.AnalysisResult.TypeInfo;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** TypeScriptAnalyzer 解析测试 */
class TypeScriptAnalyzerTest {

    private static final String SAMPLE = """
            import { Repository } from './repository';

            export const VERSION = '1.0';

            export interface UserRepo {
              find(id: number): Promise<User>;
            }

            /**
             * 用户服务
             */
            @Component()
            export class UserService extends Base implements UserRepo {
              private cache = new Map();

              constructor(repo: Repository) {
                super();
              }

              @Get('/users/:id')
              async find(id: number): Promise<User> {
                return this.repo.find(id);
              }
            }

            export function helper(x: number) {
              return new UserService(null);
            }
            """;

    @Test
    void shouldParseTypeScriptStructure() throws Exception {
        Path dir = Files.createTempDirectory("ts-test");
        Path file = dir.resolve("src/user/service.ts");
        Files.createDirectories(file.getParent());
        Files.writeString(file, SAMPLE);

        FileStructure fs = new TypeScriptAnalyzer().parseFile(dir, file);

        // 模块
        assertEquals("src.user.service", fs.moduleQn());

        // 接口
        assertTrue(fs.types().stream().anyMatch(t ->
                t.qualifiedName().equals("src.user.service.UserRepo") && t.kind().equals("interface")));

        // 类 + JSDoc + 继承/实现
        TypeInfo cls = fs.types().stream()
                .filter(t -> t.qualifiedName().equals("src.user.service.UserService")).findFirst().orElseThrow();
        assertEquals("class", cls.kind());
        assertEquals("用户服务", cls.javadoc());
        assertTrue(fs.localEdges().stream().anyMatch(e ->
                e.kind().equals("EXTENDS") && e.from().equals(cls.qualifiedName()) && e.to().equals("Base")));
        assertTrue(fs.localEdges().stream().anyMatch(e ->
                e.kind().equals("IMPLEMENTS") && e.to().equals("UserRepo")));

        // 装饰器 -> REST 端点(NestJS 风格)
        var find = fs.methods().stream()
                .filter(m -> m.name().equals("find") && m.httpPath() != null).findFirst().orElseThrow();
        assertEquals("GET", find.httpMethod());
        assertEquals("/users/:id", find.httpPath());

        // 构造器(this 去参) + 类属性
        assertTrue(fs.methods().stream().anyMatch(m ->
                m.id().equals("src.user.service.UserService.constructor(1)")));
        assertTrue(fs.fields().stream().anyMatch(f -> f.name().equals("cache")));

        // 箭头函数常量 -> 模块方法 + 字段 VERSION
        assertTrue(fs.methods().stream().anyMatch(m -> m.name().equals("helper")));
        assertTrue(fs.fields().stream().anyMatch(f -> f.name().equals("VERSION")));

        // 调用引用: this.repo.find / new UserService
        var refs = fs.unresolvedCalls().stream().map(FileStructure.NameRef::name).toList();
        assertTrue(refs.contains("this.repo.find"), refs.toString());
        assertTrue(refs.stream().anyMatch(r -> r.startsWith("UserService")), refs.toString());

        // 导入
        assertTrue(fs.unresolvedImports().stream().anyMatch(r -> r.name().equals("./repository")));
    }
}
