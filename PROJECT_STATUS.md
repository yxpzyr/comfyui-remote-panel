# 项目状态

**版本：V1.0.0 源码版**

已完成：
- 中文原生 Android UI
- ComfyUI 地址/端口记忆与连通检测
- API JSON 工作流导入与校验
- LoadImage 自动识别/多节点选择
- 安卓输入图选择、预览与 multipart 上传
- `/prompt` 提交与 `/history/{id}` 轮询
- 输出图获取、首页预览与相册保存
- 服务器历史图库、预览与保存
- `/interrupt` 停止请求
- GitHub Actions APK 自动构建脚本

当前环境限制：本会话容器未预装 Android SDK/Gradle，无法在本地完成 APK 二进制编译验证；工程已包含无需电脑的 GitHub Actions 构建流程。
