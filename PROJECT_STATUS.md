# 项目状态

**版本：V2.2.0 源码版**

已完成基础能力：
- 中文原生 Android UI
- ComfyUI 地址/端口记忆与连通检测
- 普通 UI JSON / API JSON 工作流读取
- 多工作流持久化、切换、重命名、收藏置顶、删除
- 每个工作流独立保存可编辑参数
- 多输入图片识别、安卓图片选择、预览和 multipart 上传
- 连续提交 / 跨工作流排队
- 独立任务队列、取消/中断
- history 输出恢复、最近输出预览、100 张图库
- 多输出节点选择
- 输入图片按工作流长期保留
- 多图顺序批量入队
- 高级设置折叠与个性化背景/启动页/Launcher 图标

V2.2 新增：
- 自动识别支持 MASK 的图片加载节点
- `/object_info` 动态识别自定义 MASK 图片加载节点
- 手机端蒙版遮罩编辑器
- 涂抹 / 橡皮擦 / 画笔大小 / 撤销 / 重做 / 反转 / 清空
- 蒙版以 PNG Alpha 写入，兼容 ComfyUI LoadImage 的 `MASK = 1 - alpha`
- 蒙版按工作流 + 输入节点持久保存
- 换图自动清除旧蒙版
- 清除蒙版后重新上传原图
- 批量目标图不复用当前蒙版，避免位置错位

验证状态：
- V2.1 已由用户仓库 GitHub Actions 成功完成 Android APK 构建。
- 当前运行环境没有 Android SDK/Gradle，因此 V2.2 无法在本地执行完整 `assembleDebug`。
- V2.2 已执行 Java 源码解析/结构检查、Manifest/XML 解析检查、文件引用检查和 ZIP 完整性检查。
- 上传到 GitHub 后应由仓库现有 `Build Android APK` Action 做最终 Android 类型解析、资源链接和 APK 构建验证。
