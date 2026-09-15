# ComfyUI 远程面板（Android）V2.0

一个面向“手机 + Termius/Tailscale/SSH 转发 + 远程 ComfyUI”的中文 Android 控制端。

## V2.0 重点升级

V2.0 在 V1.9 稳定后台队列基础上加入“多输出节点选择”：工作流可检测多个 ComfyUI output_node，并为每个输出点提供独立开关；提交任务时只保留勾选的输出节点。同时正式加入全新的霓虹节点沙发 App 图标。详见 `V2.0_CHANGELOG.md`。


- **多工作流常驻**：工作流只需导入一次，之后保存在 App 私有目录中；支持同时保存多个工作流、快速切换、重命名、收藏置顶和删除。
- **普通 UI JSON + API JSON**：普通 ComfyUI UI 工作流会结合远程 `/object_info` 自动转换，API JSON 可直接使用。
- **连续排队生成**：一个任务成功提交到 ComfyUI 后，手机端立即恢复输入区；可马上换下一张图继续提交，不需要等上一张生成完。
- **跨工作流排队**：工作流 A 入队后可立刻切到工作流 B 再入队；真正的 GPU 执行顺序仍由 ComfyUI 队列决定。
- **独立生成队列页面**：显示上传中、等待中、生成中、同步结果、已完成、失败和已取消；等待任务可单独取消，运行中的任务可请求中断。
- **history 输出兼容增强**：任务完成后如果 prompt-specific history 暂时没有图片，会继续轮询并回退检查完整 history，避免过早误报失败。
- **100 张增强图库**：最多读取最近 100 张输出；支持时间升/降序、名称 A-Z/Z-A。
- **图库长按多选**：长按进入多选，可全选、取消选择、批量下载，并显示下载进度。
- **工作流参数分别记忆**：每个工作流单独保存提示词/开关等可编辑参数，不互相覆盖。
- **最近输出预览**：最近完成的可下载输出仍会回显在主界面，可单张或批量保存。

## 使用前提

1. 电脑上的 ComfyUI 已启动。
2. 手机可以通过 Termius/Tailscale/SSH 隧道访问 ComfyUI，例如 `127.0.0.1:8188`。
3. 推荐不要把 ComfyUI 端口直接暴露到公网。

## 最简使用流程

1. 打开 App，输入 `8188` 或完整 ComfyUI 地址并测试连接。
2. 点“＋ 导入工作流”，选择一个 ComfyUI JSON。
3. 以后再次打开 App 时，这个工作流仍然存在，不需要重复导入。
4. 选择输入图，调整需要的提示词/开关，点“＋ 加入队列”。
5. 等状态显示“已加入队列”后，立即选择下一张图再次入队。
6. 可切换到另一个已保存工作流继续提交任务。
7. 点“队列”查看所有任务状态。
8. 点“图库”查看最近 100 张图片，排序或长按多选批量下载。

## 地址规则

- `8188` → `http://127.0.0.1:8188`
- `127.0.0.1:8188` → 自动补 `http://`
- 完整 `http://...` / `https://...` → 原样使用

## 主要 ComfyUI 接口

- `GET /system_stats`
- `GET /object_info`
- `POST /upload/image`
- `POST /prompt`
- `GET /queue`
- `POST /queue`
- `GET /history/{prompt_id}`
- `GET /history`
- `GET /view`
- `POST /interrupt`

并对部分 `/api/...` 路径做回退尝试。

## 构建 APK（GitHub Actions）

工程自带 `.github/workflows/build-apk.yml`：

1. 将整个项目上传/推送到 GitHub 仓库。
2. 打开仓库 → **Actions** → **Build Android APK**。
3. 等待构建完成。
4. 在运行页面底部 **Artifacts** 下载 `ComfyUI-Remote-Panel-debug`。
5. 解压后安装 `app-debug.apk`。

## V1.9 数据保存

- 工作流正文保存在 App 私有目录 `workflows_v18`，避免把大型 JSON 全塞进 SharedPreferences。
- 当前工作流、服务器地址、任务队列状态等轻量信息保存在 SharedPreferences。
- 手机相册下载目录为 `Pictures/ComfyRemote`。

## 当前限制

- App 不负责建立 SSH/Tailscale 隧道。
- 如果 ComfyUI 启用了额外登录插件/Token，当前版本仍未提供认证字段。
- App 允许连续提交多个任务，但是否并行执行由 ComfyUI 和电脑端硬件决定；默认情况下应理解为“连续入队”，不是让多个任务同时抢同一块 GPU。
