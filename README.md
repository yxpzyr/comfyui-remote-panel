# ComfyUI 远程面板（Android）

一个面向“手机 + Termius SSH 端口转发 + 远程 ComfyUI”的极简中文 Android 客户端。

## V1.1 功能

- 输入端口或地址，例如 `8188` / `127.0.0.1:8188` / `http://127.0.0.1:8188`
- 一键测试 ComfyUI 连接并记住地址
- 导入 **普通 ComfyUI JSON 工作流或 API JSON 工作流**
- 普通工作流会读取远程 ComfyUI `/object_info` 自动转换为可执行 API prompt
- 自动识别 `LoadImage` 输入节点；多个输入节点时让用户选择
- 从手机选择输入图片并上传到 ComfyUI `input`
- 一键提交工作流并轮询生成状态
- 左侧显示输入图，右侧显示最新输出图
- 一键保存最新输出到 `Pictures/ComfyRemote`
- “图库”查看当前 ComfyUI 历史接口返回的生成图片，点击可预览并保存
- “停止”可调用 ComfyUI `/interrupt`
- 不需要在手机浏览器打开 ComfyUI 网页

## 使用前提

1. 电脑上的 ComfyUI 已启动。
2. Termius 已建立 SSH 本地端口转发，例如：
   - Local: `127.0.0.1:8188`
   - Remote: `127.0.0.1:8188`
3. 手机浏览器原本能通过这个本地转发地址打开 ComfyUI。
4. 普通 ComfyUI `Save` JSON 和 `Export Workflow (API)` JSON 都可以使用；普通 JSON 转换时要求电脑端已安装对应自定义节点。

> 建议始终使用 SSH/Tailscale 隧道，不要把 ComfyUI 的 8188 端口直接暴露到公网。

## 最简使用流程

1. 打开 Termius，确保 ComfyUI 端口转发已连接。
2. 打开本 App。
3. 地址输入 `8188`，点“连接”。
4. 点“上传工作流 JSON”。
5. 左侧点“选择输入图”。
6. 点“开始生成”。
7. 生成完成后右侧显示输出图；点“保存输出”保存到手机。
8. 点“图库”查看服务器历史输出。

## 地址规则

- 输入 `8188` → 自动转换为 `http://127.0.0.1:8188`
- 输入 `127.0.0.1:8188` → 自动补 `http://`
- 输入完整 `http://...` / `https://...` → 原样使用

## ComfyUI API 兼容

V1 首选本地 ComfyUI 传统接口：

- `GET /system_stats`
- `POST /upload/image`
- `POST /prompt`
- `GET /history/{prompt_id}`
- `GET /history`
- `GET /view`
- `POST /interrupt`

并对部分新式 `/api/...` 路径做回退尝试。

## 编译 APK（只用手机也能做）

工程自带 `.github/workflows/build-apk.yml`。把整个项目上传到一个 GitHub 仓库后：

1. 打开仓库 → **Actions**。
2. 选择 **Build Android APK**。
3. 点 **Run workflow**。
4. 构建完成后，在该次运行底部 **Artifacts** 下载 `ComfyUI-Remote-Panel-debug`。
5. 解压得到 `app-debug.apk`，安装即可。

## 已知限制（V1）

- 只自动替换一个选定的图片加载节点；复杂多输入工作流会让你选择其中一个节点。
- 普通工作流自动转换采用 `/object_info` + `widgets_values` 映射；少数前端专用节点、子图或特殊第三方节点仍可能要求先在 ComfyUI 中展开/导出 API 格式。
- 不提供节点参数编辑器，保持界面极简。
- 图库最多加载最近约 60 张图片，避免手机内存占用过高。
- 本 App 不负责建立 SSH 隧道；隧道仍由 Termius 负责。
- 如果 ComfyUI 启用了额外登录插件/Token，V1 暂未提供认证字段。
