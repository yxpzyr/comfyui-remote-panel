# 项目状态

**版本：V2.3.0 源码版**

基础能力继续保留：
- 中文原生 Android UI、ComfyUI 地址/端口记忆与连通检测
- UI JSON / API JSON 工作流读取与多工作流持久化
- 每工作流独立输入图、参数、替换开关与蒙版
- 单张/批量顺序生成、跨工作流排队、队列取消/中断
- V2.2 手机端 MASK 蒙版编辑器
- 高级设置折叠、背景/启动页/Launcher 图标个性化

V2.3 新增/调整：
- 图库由“图片列表”改为“任务列表”
- 上一页 / 下一页 / 页码回车跳转
- 每页 5 / 10 / 20 个任务可选并记忆
- 删除 5 秒图库全量自动刷新；只在进入/恢复页面和手动刷新时同步元数据
- 仅请求当前页任务的最终图缩略图，减少移动数据和卡顿
- 主页最近输出只加载最终图，多图任务显示 `📁 N`
- 新增任务全部图片二级页面，可查看并多选下载过程图
- 图库直接长按仅多选各任务最终图
- GenerationManager 不再见到第一张输出就结束任务，而是等待整个 prompt completed
- 运行中持久记录输出首次出现顺序；ImageRef 新增 sourceNodeId / generatedAt / outputOrder
- 最终图按 generatedAt 最新、outputOrder 最大判定；旧 V2.2 数据回退到列表最后一张

验证状态：
- V2.1、V2.2 均已由用户仓库 GitHub Actions 成功完成 Android APK 构建并实机测试。
- 当前环境没有完整 Android SDK，因此 V2.3 需上传 GitHub 后由现有 `Build Android APK` Action 做最终 Android 类型解析、资源链接和 APK 构建验证。
- 本地会执行源码结构、XML/Manifest、版本号、ZIP 完整性和关键行为静态检查。
