# 只有安卓手机时如何拿到 APK

当前工程已经带 GitHub Actions 自动编译文件：`.github/workflows/build-apk.yml`。

最省事的路径：

1. 手机浏览器打开 GitHub，创建一个空仓库，例如 `comfyui-remote-panel`。
2. 把本项目文件上传到仓库（也可以把 ZIP 交给支持 Git 的工具/Codex 上传）。
3. 打开仓库的 **Actions** 标签。
4. 选择 **Build Android APK** → **Run workflow**。
5. 等待绿色成功标记。
6. 打开这次运行，在 **Artifacts** 下载 `ComfyUI-Remote-Panel-debug`。
7. 解压并安装 `app-debug.apk`。

无需 Android Studio，也无需电脑。

> Debug APK 使用 GitHub Actions 的临时调试签名，适合自用测试。正式分发版本应建立自己的稳定签名密钥。
