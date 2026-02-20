# Java2Smali

一个面向 Android 的 Java→Smali 编辑与转换工具，支持多工作区、包结构文件管理、依赖浏览、搜索替换与 DEX 导出，适合移动端快速验证 Java 代码到 Smali 的转换结果。

## 项目描述

Java2Smali 主要用于在手机端完成以下工作流：

1. 编写/编辑 Java 代码
2. 一键转换并查看 Smali
3. 在工作区与包结构中组织源码
4. 导入并浏览第三方依赖（JAR/DEX）
5. 导出当前项目自己的 `classes.dex`

## 功能特性

- Java / Smali 双视图编辑与切换
- 多工作区管理（新建、切换（点击[ws]那个工作区条目）、清空）
- 包结构文件树（新建文件/文件夹、重命名、删除、移动、复制）
- 搜索替换（支持工作区范围与高亮）
- 依赖导入与浏览（JAR / DEX）
- DEX 导出（仅导出项目自身 `classes.dex`）

## 构建方式（发布构建）

```bash
./gradlew testReleaseUnitTest --no-daemon
./gradlew assembleRelease --no-daemon
```

默认 APK 输出路径：

`app/build/outputs/apk/release/app-release-unsigned.apk`

## 目录结构

- `app/src/main/java/com/java2smali/`：核心业务代码
- `app/src/main/res/`：界面与资源文件
- `app/src/test/`：单元测试

## 说明

- 推荐始终使用发布构建任务进行验证。
- `local.properties` 为本机环境配置，不纳入版本管理。

## 开源协议

当前仓库已开源；如需指定协议，可补充 `LICENSE` 文件（如 MIT / Apache-2.0）。
