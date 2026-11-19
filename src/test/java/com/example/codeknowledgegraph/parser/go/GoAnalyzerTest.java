package com.example.codeknowledgegraph.parser.go;

import com.example.codeknowledgegraph.parser.LanguageParser.FileStructure;
import com.example.codeknowledgegraph.parser.model.AnalysisResult.TypeInfo;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** GoAnalyzer 解析测试 */
class GoAnalyzerTest {

    private static final String SAMPLE = """
            // Package user 提供用户领域逻辑
            package user

            import (
            	"fmt"
            	"github.com/google/uuid"
            )

            // User 用户实体
            type User struct {
            	ID   string
            	Name string
            }

            // Repo 用户仓库接口
            type Repo interface {
            	FindByID(id string) (*User, error)
            }

            // New 创建用户
            func New(name string) *User {
            	return &User{ID: uuid.NewString(), Name: name}
            }

            // Save 保存用户
            func (u *User) Save(r Repo) error {
            	fmt.Println(u.Name)
            	return nil
            }
            """;

    @Test
    void shouldParseGoStructure() throws Exception {
        Path dir = Files.createTempDirectory("go-test");
        Path file = dir.resolve("internal/user/user.go");
        Files.createDirectories(file.getParent());
        Files.writeString(file, SAMPLE);

        FileStructure fs = new GoAnalyzer().parseFile(dir, file);

        // 模块(目录推导) + 包名
        assertEquals("internal.user", fs.moduleQn());
        TypeInfo module = fs.types().stream()
                .filter(t -> t.kind().equals("module")).findFirst().orElseThrow();
        assertEquals("user", module.simpleName());

        // struct -> class, 注释 -> javadoc, 字段
        TypeInfo user = fs.types().stream()
                .filter(t -> t.qualifiedName().equals("internal.user.User")).findFirst().orElseThrow();
        assertEquals("class", user.kind());
        assertEquals("User 用户实体", user.javadoc());
        assertTrue(fs.fields().stream().anyMatch(f -> f.ownerType().equals("internal.user.User")
                && f.name().equals("ID") && f.type().equals("string")));

        // interface
        assertTrue(fs.types().stream().anyMatch(t ->
                t.qualifiedName().equals("internal.user.Repo") && t.kind().equals("interface")));

        // 包级函数 + 方法(receiver 归属 struct, self 参数剔除)
        assertTrue(fs.methods().stream().anyMatch(m ->
                m.id().equals("internal.user.New(1)")));
        var save = fs.methods().stream()
                .filter(m -> m.name().equals("Save")).findFirst().orElseThrow();
        assertEquals("internal.user.User.Save(1)", save.id(), "receiver 不计入参数");
        assertEquals("Save 保存用户", save.javadoc());

        // 调用引用: fmt.Println(包函数) + uuid.NewString
        var refs = fs.unresolvedCalls().stream().map(FileStructure.NameRef::name).toList();
        assertTrue(refs.contains("fmt.Println"), refs.toString());
        assertTrue(refs.contains("uuid.NewString"), refs.toString());

        // 导入路径
        assertTrue(fs.unresolvedImports().stream().anyMatch(r -> r.name().equals("github.com/google/uuid")));
    }
}
