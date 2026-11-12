# 校园活动项目后端构建学习

## 当前阶段

准备第一步 B01–B12：后端工程骨架、数据库、实体、Mapper、配置与探活接口。
已核对原始教程；当前完成 B01 工程初始化，B02–B12 待继续学习。

## 目录

- `server/`：已从教程 B01 原样提取 Maven 配置、启动类和应用配置。
- `reference/`：教学文档、Git bundle 和参考源码，不提交到自己的仓库。
- `docs/learning/`：学习记录、AI 解读、检查点、截图和日志。

## 已检查的环境

- JDK：21.0.8
- Maven：3.9.16
- Git：2.52.0.windows.1
- MySQL、Redis：尚未验证连接。

## 下一步

1. 用 IDEA 打开 `server/pom.xml`，作为 Maven 工程导入，项目 SDK 选择 JDK 21。
2. 对照教程理解 B01 的三个文件，再从 B02 统一响应与异常处理继续。
3. 按 B02–B12 逐步实现、验证并提交，最终验收 C1。

## 验证结果

已执行 `mvn -f server/pom.xml -DskipTests compile`，结果为 `BUILD SUCCESS`。
日志见 `docs/learning/logs/B01-compile-online.log`。首次离线检查缺少依赖，随后已联网下载并编译成功。

参考仓库位于 `reference/source`，分支为 `tutorial/from-zero`，共 65 个提交。
自己的工程当前仅包含 B01，不是参考仓库最新版本。

尚未初始化数据库或启动应用，C1 尚未验收。B12 时 `/api/ping` 返回 401 是教程预期。
