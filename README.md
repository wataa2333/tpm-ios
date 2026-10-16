# wataa · 原生 iOS TPM 工作台

将原 Android TPM App 的报修、维修和 ERP 领料迁移到 SwiftUI，保留原服务端字段、权限及工作流。应用名称 **wataa**，工程与 Scheme 名称 **TPM**。

## 接手入口

交给 Claude 或其他开发者时，先读 [项目交接说明](项目交接说明.md) 和 [CLAUDE.md](CLAUDE.md)。源码、图标、测试、XcodeGen 和云端配置均在仓库中。原 APK、登录会话、业务快照与签名资料不包含在仓库中。

| 内容 | 文档 |
| --- | --- |
| 项目目标、代码地图、最新需求、剩余工作 | [项目交接说明.md](项目交接说明.md) |
| Claude 工作上下文 | [CLAUDE.md](CLAUDE.md) |
| 最新修复和完整测试结果 | [UI回归-2026-10-16.md](UI回归-2026-10-16.md) |
| 用户历次反馈 | [UI反馈修复清单.md](UI反馈修复清单.md) |
| 原 App 与服务端流程核对 | [流程核对.md](流程核对.md) |
| 云端构建 | [Codemagic构建说明.md](Codemagic构建说明.md) / [codemagic.yaml](codemagic.yaml) |
| Windows 安装续签 | [Windows免费安装说明.md](Windows免费安装说明.md) |
| 历史记录 | [开发进度.md](开发进度.md) |

## 已验证基线

- **0.7.0（6）**，应用源码提交 `363440a0c335eb1925801c2db1016e75f9ea7160`。
- Codemagic [构建 #7](https://codemagic.io/app/6ad12999ed99dc0184b46bf6/build/6ad16d753ffd2c7f0ad3c502)：Xcode 27.1 编译、17 单元、18 UI 全通过，设备 Release IPA 导出完成。
- 未签名 IPA SHA256：`5bfb6842ebe70686eb060159a12915653e614059018ef0a9643f0500caef7ac0`。
- 自动化主要使用 DEBUG 虚构数据，生产写入及真机玻璃、动画观感尚未验收。

## 构建

需要 macOS、Xcode 27.1、iOS 27 模拟器、XcodeGen、Python 3。Windows 可编辑源码，不能本地运行 Xcode。

```bash
cd ios
xcodegen generate
cd ..
python3 ios/check-native-ui.py
xcodebuild -project ios/TPM.xcodeproj -scheme TPM \
  -configuration Release -sdk iphoneos -destination 'generic/platform=iOS' \
  -derivedDataPath build/device CODE_SIGNING_ALLOWED=NO build
```

完整打包以 `codemagic.yaml` 为准。默认全部测试，不设置 `TPM_TEST_SCOPE=navigation`。没有自动提交触发，文档修改不需重新构建。IPA 需要用户本地签名安装。

历史文档包含已经放弃的方案；冲突时以用户最新反馈、交接说明和当前源码为准。
