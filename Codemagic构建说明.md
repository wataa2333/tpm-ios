# Codemagic 免费构建 wataa

配置已准备在仓库根目录 codemagic.yaml。使用个人账户的 mac_mini_m2，Xcode 27.1，不上传 Apple ID、证书、描述文件或生产账号。

1. 打开 https://codemagic.io/ ，登录或注册个人账户。账户条款和 GitHub 私有仓库授权由你本人完成，只选择 wataa2333/tpm-ios。
2. 添加该仓库为应用，选择原生 iOS / YAML 配置，分支 main。
3. 选择工作流 wataa-ios，启动一次构建。配置没有提交即自动运行的触发器，避免每次改代码都消耗额度。
4. 工作流先运行单元和 UI 测试，通过后构建设备 Release，再导出未签名 wataa.ipa。测试失败会停止，不能把失败当成通过。
5. 成功后在 Artifacts 下载 wataa.ipa。SHA256 和源码提交号同时提供。安装仍按 Windows免费安装说明.md 使用本地个人账户签名。

官方个人计划当前每月含 500 分钟 macOS M2。不要为了此流程启用付费团队或付费机器；以账户页面实际资格与剩余额度为准。

当前状态：配置已经编写，尚未在 Codemagic 运行。GitHub Actions 38792901883 未开始执行就被额度限制阻止；GitHub 账单页显示本月已用 2000 / 2000 分钟，计费金额 0 美元，17 天后重置。此前通过的 v0.5.0 不是本轮新代码的验证结果。

官方资料：

- https://codemagic.io/pricing/
- https://docs.codemagic.io/specs-macos/xcode-27-1/
- https://docs.codemagic.io/yaml-basic-configuration/yaml-getting-started/
