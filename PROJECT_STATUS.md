# ComfyUI Remote Panel

**版本：V2.4.1 源码版**

V2.4 is based on the permanently signed V2.3 `main` configuration and keeps the V2.3 task-level gallery/final-output behavior.

## V2.4 main changes

- Multi-select workflow JSON import with sequential parsing and partial-failure reporting.
- One-level logical workflow folders with stable UUIDs, `未分类`, and virtual `★ 常用` / `全部工作流` views.
- Safe folder deletion: workflows move to `未分类` instead of being deleted.
- Move workflows between folders without changing workflow IDs, preserving remembered inputs, masks, parameters and output selections.
- Folder-scoped duplicate names.
- Mask editor supports two-finger pinch zoom and two-finger pan up to roughly 10x while single-finger paint/erase remains intact.
- Permanent PKCS12 release signing remains configured in Gradle and GitHub Actions.

## Verification status

- Source-level brace/syntax sanity checks completed for modified Java files.
- Version/signing workflow references checked.
- This environment does not contain a full Android SDK/Gradle Android toolchain; GitHub Actions remains the authoritative `assembleRelease` verification step after upload.


V2.4.1: 已将 V2.4 的分类筛选重构为文件管理器式工作流库，并新增整目录导入。版本 15 / 2.4.1。
