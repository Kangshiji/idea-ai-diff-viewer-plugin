# AI 代码变更可视化插件 — 开发计划

> 基于 [action.md](file:///c:/Users/kksj3/Documents/trae_projects/ask/action.md) 路线 B（自研 IDEA 插件方案），
> 以 demo（helloworld 模板工程）为基础进行开发。
>
> 目标平台：IntelliJ IDEA 2025.3.6.1（Gradle 插件 2.19.0 / Gradle 9.6.1 / JDK 21）

## 一、核心决策（开发前必须明确）

### 1.1 流程模型：采用「流程B」
CLI 脚本负责：执行 AI 修改 → 对比基线生成 patch → 写入 `${projectRoot}/.ai_diff/ai-changes.patch`。
**工作区文件保持 AI 修改后的状态**（CLI 不还原）。

因此插件语义为：
- **接受（Accept）**：仅将该文件标记为「已确认」，从待确认列表移除，工作区文件不变。
- **拒绝（Reject）**：基于 patch 对该文件做**反向回滚**，把文件恢复到 AI 修改前的基线内容，然后从列表移除。
- 冲突检测：拒绝时若目标文件当前内容与 patch 的"修改后"内容不匹配（说明用户又手动改过），提示冲突，禁止强制回滚。

### 1.2 Diff 展示：MVP 采用弹窗方案
点击列表中的文件 → 调用 `DiffManager.showDiff()` 弹出原生 Diff 窗口。
左侧（before）= patch 描述的基线内容（红色删除行），右侧（after）= 当前工作区文件（绿色新增行）。
完整版内嵌 DiffViewer 留作后续迭代。

### 1.3 数据契约（与 CLI 侧约定）
- Patch 路径：`${projectRoot}/.ai_diff/ai-changes.patch`（标准 unified git diff 格式，UTF-8）
- 可选元数据：`${projectRoot}/.ai_diff/meta.json`（MVP 暂只容错读取，不依赖）
- Patch 中路径相对项目根目录（`a/`、`b/` 前缀的 git 风格）

## 二、开发步骤

### 阶段 0：工程配置调整
1. 修改 `build.gradle.kts`：
   - 依赖中加入 `bundledPlugin("Git4Idea")`（PatchReader / PatchApplier 相关类在 Git 模块）
   - 添加 kotlin JVM target / 平台 `sinceBuild` 配置
2. 修改 `plugin.xml`：
   - id 改为 `com.company.ai-diff-viewer`，name 改为 `AI Diff Viewer`
   - 注册 ToolWindow（右侧栏）、`<depends>Git4Idea</depends>`
3. 包结构从 `com.example` 迁移为 `com.company.aidiff`
4. 删除模板残留：`MyMessageBundle`、`messages/*.properties`、`MyToolWindowFactory`

### 阶段 1：Spike 验证（最高风险优先）
**目标：在写任何 UI 之前，先用硬编码 patch 验证三个关键 API 在目标平台可用。**

写一个临时 Action `SpikePatchAction`，注册到 Tools 菜单，依次验证：
1. `PatchReader.readPatch()` 能否解析出 `List<TextFilePatch>`（含 hunk 行数统计）
2. `DiffManager.getInstance().showDiff()` 能否弹出原生差异窗口（构造 `DiffRequestFactory` 的 Content）
3. `PatchApplier`（或 `GenericPatchApplier`）能否把 patch 应用到内存文本，验证正/反向逻辑

验证通过后再进入正式开发；任何一步失败则降级：解析用自研 unified-diff 解析器，回滚用文本级 patch 应用。

### 阶段 2：核心服务层（不含 UI）
1. **PatchStateService（Project 级 Service）**
   - 持有当前解析结果：`List<FileChange>`（路径、hunks、新增/删除行数、变更类型、accepted 标记）
   - 提供 `reload()`：读取 `.ai_diff/ai-changes.patch` → 解析 → 通知 UI 刷新
   - 提供 `reject(file)` / `accept(file)` / 批量操作入口
   - 持久化已确认状态到 `.ai_diff/state.json`（防止 IDE 重启后已确认项复活）
2. **PatchParser**
   - 封装 `PatchReader` 调用；过滤二进制 patch 条目（`Binary content` / `GIT binary patch`）
   - 输出 UI 模型：`path`、addedLines、deletedLines、changeType(ADD/MODIFY/DELETE)
3. **PatchWatcher**
   - 通过 MessageBus 订阅 `VirtualFileManager.VFS_CHANGES`（`BulkFileListener`）
   - 只关心 `*.ai_diff/ai-changes.patch` 的创建/内容变化，触发 `PatchStateService.reload()`
   - 订阅挂在 Project 的 MessageBus connection 上，项目关闭自动释放
   - 同时提供手动「刷新」入口（外部进程写文件 VFS 可能延迟感知，手动刷新先调 `VfsUtil.refresh`）

### 阶段 3：ToolWindow UI
1. **AiDiffToolWindowFactory**（`ToolWindowFactory` + `DumbAware`）
2. 面板布局（`BorderLayout`）：
   - 顶部 `ActionToolbar`：刷新 / 接受选中 / 拒绝选中 / 全部接受 / 全部拒绝
   - 中部 `CheckboxTree`（或带勾选列的 `JBTable`）：勾选框 | 文件路径 | +N / -M | 类型
   - 底部状态栏：共 N 个文件待确认
3. 双击/单击文件 → 弹出 Diff 窗口（DiffManager）
4. 列表刷新与 Service 数据变更联动（`project.messageBus` 自定义 Topic 或简单回调）

### 阶段 4：Diff 展示与执行
1. **Diff 弹窗**：
   - before 内容：patch 的 before 文本（通过反向应用 hunks 于当前文件得到，或直接从 hunk 上下文重建）
   - after 内容：当前工作区文件内容（`FileDocumentManager` 拿 Document，含未保存修改）
   - `DiffRequestFactory.createDiffRequest(before, after, titles)` → `DiffManager.showDiff(project, request)`
2. **拒绝（Reject）执行**：
   - 对选中文件：取 patch 中该文件的 hunks，用 `GenericPatchApplier` 反向应用到当前文件文本
   - 应用失败（内容已漂移）→ 弹冲突提示，跳过该文件
   - 成功 → `WriteCommandAction.writeCommandAction(project).run { document.setText(newText); FileDocumentManager.saveDocument() }`
3. **接受（Accept）执行**：仅更新状态文件 + 刷新列表，不写磁盘
4. 全部操作前校验文件未被外部锁定、非只读

### 阶段 5：边界与稳定性
1. 空 patch / patch 不存在 → 面板显示空状态提示，不报错
2. patch 格式损坏 → `PatchReader` 抛异常时捕获，面板显示「Patch 解析失败」+ 查看详情入口
3. 编码统一 UTF-8；回滚时保留原文件 BOM 与换行符风格（CRLF/LF 探测）
4. 多项目隔离：Service/Watcher 全部 Project 级，天然隔离
5. 二进制文件条目直接过滤不入列表
6. 大 patch（>5MB）解析放后台线程（`ReadAction.nonBlocking` / `Task.Backgroundable`），UI 显示加载中
7. 日志统一走 `thisLogger()`

### 阶段 6：调试与打包
1. `./gradlew runIde` 启动沙箱 IDE
2. 构造测试场景：准备示例工程 + 手工生成的 `.ai_diff/ai-changes.patch`，走通「列表→Diff→接受/拒绝」全流程
3. `./gradlew buildPlugin` 产出 `build/distributions/*.zip`
4. `./gradlew verifyPlugin`（可选）检查兼容性

## 三、目录结构规划

```
src/main/kotlin/com/company/aidiff/
├── AiDiffToolWindowFactory.kt      # ToolWindow 注册入口
├── model/
│   └── FileChange.kt               # UI 数据模型
├── services/
│   ├── PatchStateService.kt        # 状态与业务核心
│   └── PatchParser.kt              # PatchReader 封装
├── watcher/
│   └── PatchFileWatcher.kt         # VFS 监听
├── ui/
│   ├── AiDiffPanel.kt              # 主面板（列表+工具栏）
│   └── DiffShower.kt               # Diff 弹窗封装
└── actions/
    ├── RefreshPatchAction.kt
    ├── AcceptChangesAction.kt
    └── RejectChangesAction.kt
```

## 四、里程碑

| 里程碑 | 内容 | 完成标准 |
|--------|------|----------|
| M0 | 工程配置 + Spike 验证 | 硬编码 patch 能解析、能弹 Diff、能应用 |
| M1 | Service + 监听 + 面板列表 | 真实 patch 文件变更时列表自动刷新 |
| M2 | Diff 弹窗 + Accept/Reject | 全流程可用，冲突有提示 |
| M3 | 边界处理 + 打包 | buildPlugin 出 zip，沙箱全流程回归通过 |

## 五、风险与回退方案

| 风险 | 回退方案 |
|------|----------|
| PatchReader 内部 API 在 2025.3 签名变化 | 自研 unified diff 解析器（格式简单，约 200 行） |
| GenericPatchApplier 应用失败率高 | 回滚改用「hunks 行号定位 + 文本替换」的保守实现 |
| VFS 对外部文件写入感知延迟 | 保留手动刷新按钮 + 打开 ToolWindow 时主动 refresh |
