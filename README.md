# ir_demo1：Lucene + Qdrant Semantic 的本地 RAG 示例工程

一个基于 **Spring Boot + Lucene + Qdrant + Ollama + WebMagic** 的本地 RAG 示例项目。  
项目实现了从**抓取腾讯科技新闻**、**建立本地索引**、**执行混合检索**到**基于检索结果生成答案**的一条完整链路。

当前主链路已经是：

**Lucene lexical + Qdrant semantic + Ollama generation**

---

## 1. 项目简介

本项目最初来源于《信息检索与搜索引擎》课程示例工程，现已扩展为一个可本地运行的混合检索 RAG Demo，适合用于：

- 信息检索课程实验与课程设计
- 本地 RAG 基本流程演示
- Lucene 关键词检索入门
- 外部向量库接入实践
- Spring AI + Ollama 本地模型集成
- “抓取 -> 索引 -> 检索 -> 生成”完整链路演示

项目当前已实现：

- 腾讯科技新闻抓取
- Lucene 关键词索引与关键词检索
- Qdrant 语义向量写入与语义检索
- 关键词检索 + 语义检索的 RRF 融合
- 基于检索上下文的 Ollama 问答
- 单页 Web 问答界面
- 问答时显示“思考中”，答案逐字输出

---

## 2. 当前系统架构

### 2.1 总体流程

```text
抓取新闻
  -> 抽取字段
  -> 写入 Lucene 文档索引
  -> 写入 Qdrant 语义向量库
  -> 用户提问
  -> Lucene 关键词检索
  -> Qdrant 语义检索
  -> RRF 融合
  -> 取回新闻上下文
  -> 调用 Ollama 生成答案
```

### 2.2 当前主链路

```text
用户问题
  -> /api/rag/ask
  -> RagService.ask()
  -> IdxService.hybridSearch(question, 5)
  -> Lucene keyword search
  -> Qdrant semantic search
  -> RRF 融合
  -> 回 Lucene 按 ID 取完整新闻
  -> 拼接上下文
  -> 调用 Ollama Chat
  -> 返回 answer + sources
```

---

## 3. 技术栈

- Java 17
- Spring Boot 3.5.13
- Spring AI 1.1.4
- Apache Lucene
- Qdrant
- Ollama
- WebMagic
- HanLP

---

## 4. 核心设计

### 4.1 Lucene 负责什么

Lucene 在当前项目中主要负责：

- 本地新闻文档主索引
- 标题与正文关键词检索
- 通过业务 ID 回表获取完整文档

Lucene 当前不再承担语义向量检索职责。

### 4.2 Qdrant 负责什么

Qdrant 在当前项目中主要负责：

- 新闻语义文本的向量存储
- 基于问题文本的语义相似检索

写入 Qdrant 的语义文本来自：

```text
TITLE + "\n" + CONTENT
```

其中正文会做归一化与截断。

### 4.3 Ollama 负责什么

Ollama 在当前项目中承担两类能力：

- Embedding：为新闻文本和查询文本生成向量
- Chat：根据检索得到的新闻上下文生成答案

---

## 5. 当前已实现的混合检索方案

### 5.1 写入阶段

当爬虫抓到一篇新闻后，系统会：

1. 写入 Lucene 索引
2. 调用 `SemanticSearchService.upsertNews(...)`
3. 把新闻文本写入 Qdrant

Qdrant 中每条点数据包含：

- `id`：由业务 ID 稳定映射得到的 UUID
- `vector`：由 embedding 模型生成的向量
- `payload`：包括 `biz_id`、标题、链接、时间等元数据

### 5.2 查询阶段

用户提问后，系统会：

1. 用 Lucene 在 `TITLE` 和 `CONTENT` 上做关键词检索
2. 用 Qdrant 做语义相似检索
3. 以业务 `ID` 为键做 RRF 融合
4. 再回 Lucene 获取完整文档
5. 把新闻内容整理成上下文，送给 Ollama 生成答案

---

## 6. RAG 中大模型依据什么内容作答

大模型不是直接读取索引文件，也不是直接读取向量数组。  
它真正依据的是：**检索出来的新闻文本上下文**。

项目会把命中的新闻整理成如下格式：

```text
用户问题：
OpenClaw 最近有哪些进展？

下面是检索到的新闻上下文：
〖新闻1〗
标题：...
时间：...
链接：...
正文节选：...

〖新闻2〗
标题：...
时间：...
链接：...
正文节选：...
```

也就是说，喂给 Ollama Chat 的核心内容是：

- 用户问题
- 若干篇相关新闻的：
  - 标题
  - 发布时间
  - 链接
  - 正文节选

---

## 7. 已实现接口

### 7.1 抓取并建立索引

```http
POST /api/admin/crawl/tencent-tech?limit=10
```

作用：

- 抓取腾讯科技新闻
- 抽取字段
- 写入 Lucene
- 同步写入 Qdrant

### 7.2 重建语义索引

```http
POST /api/admin/rebuild-semantic
```

作用：

- 遍历 Lucene 中的新闻文档
- 重新写入 Qdrant 语义索引

适合用于：

- 更换 embedding 模型后重建
- Qdrant 数据丢失后重建
- 代码修改后补建向量索引

### 7.3 关键词检索

```http
GET /query/kw?kw=OpenClaw&pageNo=1&pageSize=10
```

### 7.4 混合检索

```http
GET /query/hybrid?kw=OpenClaw&topK=5
```

### 7.5 RAG 问答

```http
POST /api/rag/ask
Content-Type: application/json
```

请求体示例：

```json
{
  "question": "OpenClaw 最近有哪些进展？"
}
```

返回结果示例：

```json
{
  "question": "OpenClaw 最近有哪些进展？",
  "answer": "...",
  "sources": [
    {
      "title": "...",
      "url": "...",
      "publishTime": "..."
    }
  ]
}
```

---

## 8. 新闻字段设计

当前新闻写入 Lucene 时包含以下字段：

- `ID`
- `URL`
- `TITLE`
- `CONTENT`
- `SOURCE`
- `AUTHOR`
- `PUBLISH_TIME`
- `KEYWORD`

其中，RAG 上下文当前主要使用：

- `TITLE`
- `URL`
- `PUBLISH_TIME`
- `CONTENT`

---

## 9. 前端页面说明

首页位于：

```text
src/main/resources/static/index.html
```

当前页面已优化为问答式单页界面，支持：

- 顶部“抓取并建立索引”按钮
- 输入问题并提交
- 每次提问先清空上一次答案
- 本次答案未返回前显示“思考中...”
- 新答案返回后逐字输出
- 展示参考新闻列表

适合作为本地演示页面直接使用。

---

## 10. 目录结构建议阅读顺序

```text
src/main/java/cn/edu/bistu/cs/ir
├── controller
│   ├── AdminController.java
│   ├── QueryController.java
│   └── RagController.java
├── crawler
│   ├── CrawlerService.java
│   └── TencentTechNewsCrawler.java
├── index
│   ├── IdxService.java
│   └── NewsLucenePipeline.java
├── model
│   ├── AskRequest.java
│   ├── AskResponse.java
│   └── NewsArticle.java
├── service
│   ├── RagService.java
│   └── SemanticSearchService.java
└── IrDemoApplication.java
```

建议阅读顺序：

1. `RagController`
2. `RagService`
3. `IdxService`
4. `SemanticSearchService`
5. `NewsLucenePipeline`
6. `CrawlerService`

---

## 11. 环境要求

### 11.1 开发环境

- JDK 17
- Maven 3.9+
- IntelliJ IDEA（推荐）

### 11.2 模型服务

需要本机安装并启动 Ollama。

建议至少拉取：

```bash
ollama pull qwen2.5:7b
ollama pull nomic-embed-text
```

### 11.3 向量库

需要启动 Qdrant。

如果你是 Windows + WSL2 + Docker 的组合，可以把 Qdrant 放在 WSL2 的 Docker 中运行，而 Spring Boot 与 Ollama 继续运行在 Windows。

---

## 12. 配置说明

核心配置文件：

```text
src/main/resources/application.properties
```

当前主要配置包括：

```properties
# 工作目录
irdemo.dir.home=workspace
irdemo.dir.idx=${irdemo.dir.home}/idx
irdemo.dir.crawler=${irdemo.dir.home}/crawler

# Ollama
spring.ai.ollama.base-url=http://localhost:11434
spring.ai.model.chat=ollama
spring.ai.ollama.chat.options.model=qwen2.5:7b
spring.ai.ollama.chat.options.temperature=0.2
spring.ai.ollama.chat.options.num-ctx=8192

spring.ai.model.embedding=ollama
spring.ai.ollama.embedding.options.model=nomic-embed-text

# Qdrant
spring.ai.vectorstore.qdrant.host=localhost
spring.ai.vectorstore.qdrant.port=6334
spring.ai.vectorstore.qdrant.collection-name=tencent_news_semantic
spring.ai.vectorstore.qdrant.use-tls=false
spring.ai.vectorstore.qdrant.initialize-schema=true
```

---

## 13. 启动方式

### 13.1 启动 Ollama

确认服务可访问：

```bash
curl http://localhost:11434/api/tags
```

### 13.2 启动 Qdrant

例如使用 Docker Compose 启动 Qdrant。

### 13.3 启动 Spring Boot

可以在 IDEA 中直接运行：

```text
cn.edu.bistu.cs.ir.IrDemoApplication
```

也可以使用 Maven：

```bash
mvn spring-boot:run
```

---

## 14. 使用流程

### 第一步：抓取并建立索引

方法一：打开首页，点击“抓取并建立索引”

方法二：

```bash
curl -X POST "http://localhost:8080/api/admin/crawl/tencent-tech?limit=10"
```

### 第二步：测试混合检索

```bash
curl "http://localhost:8080/query/hybrid?kw=OpenClaw&topK=5"
```

### 第三步：发起问答

```bash
curl -X POST "http://localhost:8080/api/rag/ask" \
  -H "Content-Type: application/json" \
  -d "{\"question\":\"OpenClaw 最近有哪些进展？\"}"
```

---

## 15. 适合继续扩展的方向

### 15.1 文本切片
当前更偏“文章级”向量检索，后续可以改为：

- chunk 切片
- chunk 级召回
- chunk 级引用

### 15.2 更好的混合排序
当前使用 RRF 融合，后续可以继续实验：

- 加权融合
- 二阶段重排
- reranker

### 15.3 更好的引用展示
例如：

- 在回答中标注来源编号
- 支持点击高亮对应新闻
- 展示检索得分

### 15.4 Prompt 调试面板
当前后端已可打印上下文，后续可以前端化展示：

- 检索命中文本
- 最终 userPrompt
- 大模型返回内容

---

## 16. 项目定位

这是一个**面向教学、实验与二次改造的本地混合 RAG 示例项目**。  
它不是生产级知识库系统，但非常适合用于以下主题实践：

- 信息抓取
- 倒排索引
- 关键词检索
- 向量检索
- 混合检索
- RAG
- 本地大模型接入
- 前后端联调

---

