# 项目状态

**版本：V1.9.0 源码版**

已完成：
- 中文原生 Android UI
- ComfyUI 地址/端口记忆与连通检测
- 普通 UI JSON / API JSON 工作流读取
- 多工作流持久化、切换、重命名、收藏置顶、删除
- 每个工作流独立保存可编辑参数
- 多输入图片识别、安卓图片选择、预览和 multipart 上传
- 连续提交 ComfyUI 队列；提交成功后立即允许下一张继续入队
- 跨工作流连续排队
- 独立任务队列页面与等待任务取消/当前任务中断
- history 完成态图片延迟回传兼容逻辑
- 首页最近完成输出预览和保存
- 图库最多 100 张
- 图库按时间/名称正反排序
- 图库长按多选、全选、批量下载与进度显示
- GitHub Actions APK 自动构建脚本

验证：
- 当前会话环境没有 Android SDK/Gradle，因此未生成本地 APK。
- 已使用 Java 21 + Android/JSON 编译接口桩对全部 Java 源码做静态编译检查，结果通过，无 Java 语法/类型错误。
- 最终 Android Gradle/SDK 构建仍建议通过仓库自带 GitHub Actions 完成。
