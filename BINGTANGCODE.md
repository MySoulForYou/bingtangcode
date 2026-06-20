# bingtangCode 智能助手项目指令 (BINGTANGCODE.md)

本文件定义了当前 Java 项目 `bingtangCode` 的开发准则和约束条件，大模型在阅读和编写此项目代码时必须严格遵守。

## 1. 架构与编码规范
* **JDK 规范**：使用 Java 21 开发，允许并推荐使用 Java 21 的新特性（如 Pattern Matching for switch, Record Patterns, Text Blocks 等）。
* **依赖限制**：除 `pom.xml` 中已有的基础库（JLine3, Jackson, OkHttp）外，**禁止引入任何庞大的外部框架**（如 Spring Boot, Hibernate 或向量数据库/RAG 系统），保持单 Jar 包的轻量化特性。
* **目录规范**：
  * 核心系统设计位于 `com.bingtangcode.core`。
  * 工具定义位于 `com.bingtangcode.tool`。
  * API 交互位于 `com.bingtangcode.llm`  。

## 2. 三文档共创原则
* 在推进任何新章节或大功能开发前，大模型必须与用户充分对齐，并在 `docs/` 下的相应子目录中创建并共创三份规划文档：
  1. `spec.md`：澄清要解决的问题和不包含的范围。
  2. `tasks.md`：详细定义 5~15 个具体的子开发任务及其关联文件。
  3. `checklist.md`：列出可观察、可量化的验收指标，必须包含端到端验证。

## 3. 开发细节文档规范
* 功能开发完成后，必须为本章编写对应的 `开发细节文档.md`，从关键实现原理、消息持久化及测试套件执行等多方面进行深入剖析，并以中文版输出。
