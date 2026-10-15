# wataa：Windows 免费安装与续签

不需要 Mac，也不需要购买 Apple 开发者会员。你需要 Windows 电脑、数据线、iPhone 和可用的 Apple 账户。个人免费签名通常有效 7 天，到期前重新签名即可。

## 第一次安装

1. 从 [Sideloadly 官网](https://sideloadly.io/) 下载 Windows 64 位版本。按官网 Windows 说明安装网页版本的 iTunes 和 iCloud；官网提示不要使用 Microsoft Store 版本。
2. 用数据线连接并解锁 iPhone，在手机上选择“信任此电脑”。确认 Sideloadly 能识别手机。
3. 把本次交付的 `wataa.ipa` 拖进 Sideloadly，选择这部 iPhone，填写自己的 Apple 账户，点击 Start。Apple 密码和双重认证验证码只在本地输入，不用发给我。
4. 如果手机提示开发者不受信任，到“设置 → 通用 → VPN 与设备管理”，找到自己的 Apple 账户并信任。
5. 按手机提示启用“设置 → 隐私与安全性 → 开发者模式”，重启后确认。若尚未出现该入口，先完成首次安装，再查看。
6. 打开 **wataa**，使用原业务 App 的账号登录。业务账号与用于签名的 Apple 账户是两回事。

## 七天后怎么续签

在到期前，再次用同一个 Apple 账户、相同的应用标识安装覆盖。**不要先删除 App**，这样才能尽量保留本地数据与登录状态。

也可以启用 Sideloadly 的自动刷新功能，让它的后台程序在电脑开机、手机可连接时刷新。首次连接用 USB；无线刷新还需按官方 FAQ 在 iTunes 开启 Wi-Fi 同步，并让电脑与手机在同一局域网。不要把它理解成无需电脑的永久签名。

免费账户通常最多同时安装 3 个这类 App；具体以 Apple 和签名工具实际提示为准。

## 当前验证范围

交付的 IPA 未签名，需通过以上步骤使用你的个人证书签名，无法直接点开安装。免费签名不是 TestFlight。

iOS 27 上的真实安装、登录和业务写入仍需在你的手机上验证。Sideloadly 官网说明支持新系统，但你的 iPhone 17 / iPhone Air 上的具体兼容性，以首次签名和启动结果为准。首次业务验证建议先查看列表和详情，再选择你确认可处理的工单验证受理、签到、维修和 ERP 领料。

参考：[Apple 免费个人开发说明](https://developer.apple.com/help/account/basics/about-your-developer-account)、[Sideloadly 官方 FAQ](https://sideloadly.io/faq.html)。核对日期：2026-10-16。
