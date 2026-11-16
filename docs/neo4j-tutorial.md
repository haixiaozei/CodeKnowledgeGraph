# Neo4j 学习教程(基于本项目真实数据)

> 学习方式:打开浏览器访问 **http://localhost:7474**(Neo4j Browser)
> 用户名 `neo4j`,密码 `codekg123`,把本文的查询粘贴到顶部输入框运行。
> 你看到的每一个节点、每一条关系,都是你自己的代码分析出来的。

---

## 一、核心概念:图 = 节点 + 关系

关系型数据库用"表格 + 外键",图数据库用**节点(Node)+ 关系(Relationship)**,天生适合"谁依赖谁"这类递归查询。

本项目的图模型(`GraphStoreService.java` 写入的):

```
(:Project)-[:HAS_TYPE? 无,通过 project 属性关联]

(:Package)-[:CONTAINS]->(:Type)-[:HAS_METHOD]->(:Method)
                        (:Type)-[:HAS_FIELD]->(:Field)
                        (:Type)-[:EXTENDS]->(:Type)        继承
                        (:Type)-[:IMPLEMENTS]->(:Type)     实现
                        (:Type)-[:DEPENDS_ON]->(:Type)     依赖
                        (:Method)-[:CALLS]->(:Method)      调用
```

三个术语:

| 术语 | 类比 MySQL | 本项目例子 |
|---|---|---|
| **节点 Node** | 一行记录 | `CodeAnalyzer` 这个类 |
| **标签 Label** | 表名 | `:Type` `:Method` `:Field` `:Package` `:Project` |
| **关系 Relationship** | 外键(但有方向,且是一等公民) | `ApiController -DEPENDS_ON-> CodeAnalyzer` |

节点和关系都可以带**属性(Properties)**,如 `m.signature = "analyze(String, Path)"`。

---

## 二、Cypher 查询基础

### 2.1 MATCH:找节点

圆括号 `()` 表示节点,冒号后是标签。

```cypher
-- 所有 Type 节点(相当于 SELECT * FROM Type)
MATCH (t:Type) RETURN t;

-- 带条件过滤(WHERE 类似 SQL)
MATCH (t:Type)
WHERE t.project = 'CodeKnowledgeGraph' AND t.kind = 'class'
RETURN t.qualifiedName, t.simpleName, t.lineCount
ORDER BY t.lineCount DESC
LIMIT 10;

-- 按属性直接定位(大括号内是属性匹配,类似主键查找)
MATCH (t:Type {simpleName: 'CodeAnalyzer'})
RETURN t.qualifiedName, t.visibility, t.isAbstract;
```

### 2.2 关系查询:图的精髓

`-[:关系类型]->` 表示"沿着这条有向边走"。

```cypher
-- CodeAnalyzer 的所有方法
MATCH (:Type {qualifiedName:'com.example.codeknowledgegraph.parser.CodeAnalyzer'})-[:HAS_METHOD]->(m:Method)
RETURN m.name, m.signature;

-- 反向:这个方法属于哪个类
MATCH (t:Type)-[:HAS_METHOD]->(:Method {name:'analyze'})
RETURN t.qualifiedName;

-- 谁依赖了 CodeAnalyzer?(影响分析的雏形)
MATCH (a:Type)-[:DEPENDS_ON]->(b:Type {simpleName:'CodeAnalyzer'})
RETURN a.qualifiedName;

-- CodeAnalyzer 依赖谁?
MATCH (:Type {simpleName:'CodeAnalyzer'})-[:DEPENDS_ON]->(b:Type)
RETURN b.qualifiedName;

-- 不限定关系类型,r 可以拿到关系本身
MATCH (a:Type)-[r]->(b:Type)
WHERE a.simpleName = 'ApiController'
RETURN type(r) AS 关系, b.simpleName;
```

### 2.3 变长路径:为什么图数据库强

`*1..5` 表示"走 1 到 5 步",这就是**影响分析**的核心——SQL 里要写递归 CTE,这里一个星号搞定:

```cypher
-- 传递依赖:谁(直接或间接)依赖了 AnalysisResult?
MATCH path = (d:Type)-[:DEPENDS_ON*1..5]->(t:Type {simpleName:'AnalysisResult'})
RETURN d.qualifiedName, length(path) AS 距离
ORDER BY 距离;

-- 循环依赖:A 依赖 B,B 又绕回来依赖 A
MATCH path = (a:Type)-[:DEPENDS_ON*1..6]->(a)
RETURN [n IN nodes(path) | n.simpleName] AS 环;
```

### 2.4 聚合与函数

```cypher
-- 每种标签有多少节点
MATCH (n) RETURN labels(n) AS 标签, count(*) AS 数量 ORDER BY 数量 DESC;

-- 每个项目的类型数
MATCH (t:Type) RETURN t.project, count(*) AS 类型数;

-- 图里"被依赖最多"的 10 个类(架构中的核心类)
MATCH ()-[r:DEPENDS_ON]->(t:Type)
RETURN t.qualifiedName, count(r) AS 被依赖次数
ORDER BY 被依赖次数 DESC LIMIT 10;

-- 被调用最多的方法(热点代码)
MATCH ()-[r:CALLS]->(m:Method)
RETURN m.id, count(r) AS 调用次数
ORDER BY 调用次数 DESC LIMIT 10;
```

### 2.5 写操作

本项目 `GraphStoreService` 用到的四种:

```cypher
-- 创建(重复执行会产生重复数据)
CREATE (t:Type {qualifiedName: 'demo.Foo', simpleName: 'Foo'});

-- MERGE:存在则匹配,不存在则创建(幂等,重要!)
MERGE (t:Type {qualifiedName: 'demo.Foo'})
SET t.kind = 'class', t.lineCount = 100;

-- 删除节点及其所有关系(DETACH 很关键,否则有关系的节点删不掉)
MATCH (n) WHERE n.project = '不想要的项目名'
DETACH DELETE n;

-- 只删关系
MATCH (a:Type)-[r:DEPENDS_ON]->(b:Type) WHERE a.simpleName='X' AND b.simpleName='Y'
DELETE r;

-- 清空整个数据库(慎用!)
MATCH (n) DETACH DELETE n;
```

### 2.6 UNWIND:批量写入

项目里批量导入用的技巧,把一个列表参数展开成多行:

```cypher
-- 参数 rows = [{qn:'a.Foo',pkg:'a'},{qn:'a.Bar',pkg:'a'}]
UNWIND $rows AS row
CREATE (t:Type {qualifiedName: row.qn})
SET t.packageName = row.pkg;
```

对应 `GraphStoreService.replaceProject()` 里的写法——一批几百个节点一次请求写入,比循环单条快得多。

---

## 三、对照本项目源码

每个 API 背后的 Cypher 都在 `GraphQueryService.java`,建议按这个顺序读:

| 文件 | 学习点 |
|---|---|
| `GraphStoreService.java` | MERGE/CREATE/UNWIND 批量写入、DETACH DELETE 清理 |
| `GraphQueryService.overview()` | 基础 MATCH + 多关系类型 `[:EXTENDS\|IMPLEMENTS\|DEPENDS_ON]` |
| `GraphQueryService.typeDetail()` | OPTIONAL MATCH、聚合 count()、关联查询 |
| `GraphQueryService.impactAnalysis()` | 变长路径 `*1..N` + min(length(path)) |
| `GraphQueryService.findCycles()` | 环检测 `(a)-[*]->(a)` + 列表推导 |
| `GraphQueryService.search()` | 字符串函数 toLower/CONTAINS |

Java 侧通过 `Neo4jClient` 执行:

```java
neo4j.query("MATCH (t:Type {project: $p}) RETURN t.qualifiedName AS id, ...")
    .bind(project).to("p")     // 参数绑定,防注入,永远不要字符串拼接
    .fetch().all();            // 返回 Collection<Map<String,Object>>
```

---

## 四、练习题(用你的真实数据)

在 Neo4j Browser 里试着写出来(答案在文末):

1. 找出 `CodeKnowledgeGraph` 项目里所有 `private` 的字段
2. 找出"上帝类":方法数最多的前 3 个类
3. 找出所有"孤立类":不依赖任何类、也没被任何类依赖的类
4. 统计接口和实现:列出每个 interface 和它的实现类
5. 挑战:找出从 `ApiController` 出发,3 步以内能通过 CALLS 到达的方法

---

## 五、答案

```cypher
-- 1
MATCH (t:Type)-[:HAS_FIELD]->(f:Field)
WHERE t.project='CodeKnowledgeGraph' AND f.visibility='private'
RETURN t.simpleName, f.name, f.type;

-- 2
MATCH (t:Type)-[:HAS_METHOD]->(m)
WHERE t.project='CodeKnowledgeGraph'
RETURN t.simpleName, count(m) AS 方法数
ORDER BY 方法数 DESC LIMIT 3;

-- 3
MATCH (t:Type)
WHERE t.project='CodeKnowledgeGraph'
  AND NOT (t)-[:DEPENDS_ON|EXTENDS|IMPLEMENTS]->()
  AND NOT ()-[:DEPENDS_ON|EXTENDS|IMPLEMENTS]->(t)
RETURN t.qualifiedName;

-- 4
MATCH (i:Type {kind:'interface'})<-[:IMPLEMENTS]-(impl:Type)
WHERE i.project='CodeKnowledgeGraph'
RETURN i.simpleName AS 接口, collect(impl.simpleName) AS 实现类;

-- 5
MATCH path = (:Type {simpleName:'ApiController'})-[:HAS_METHOD]->()-[:CALLS*1..3]->(m:Method)
RETURN DISTINCT m.id LIMIT 20;
```

---

## 六、进阶资源

- 官方入门(Neo4j Graph Academy,免费互动课程):https://graphacademy.neo4j.com/
- Cypher 官方手册:https://neo4j.com/docs/cypher-manual/current/
- 本地浏览器 http://localhost:7474 左侧的数据库图标可以看所有节点/关系概览,点 "sample code" 有交互式示例
