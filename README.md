# SBX · Sing-box 透明代理模块

<p align="center">
  <img src="assets/banner.jpg" alt="SBX Pro · Sing-box 透明代理模块" width="100%">
</p>



一个面向 KernelSU 的 Sing-box 透明代理模块：TUN 全局代理 + 智能分流 + 分应用管理 + 订阅管理，内置 WebUI。

> English: A KernelSU module that runs sing-box in TUN mode with smart routing, per-app proxy rules, subscription management and a built-in WebUI.

## 特性

- **TUN 透明代理**：基于 sing-box TUN（auto_route + auto_redirect），全局接管流量，无需 VPN 框架
- **智能分流**：内置 geosite-cn / geoip-cn 规则集，国内域名/IP 直连，其余走代理
- **三种模式**：规则分流 / 全局代理 / 全部直连，切换即生效
- **分应用管理**：按 UID 粒度设置「规则 / 代理 / 直连 / 拒绝（断网）」，每个应用可点开查看当前规则的实际效果
- **订阅管理**：多订阅添加/删除/更新，支持 Clash YAML（行内与块状）与 Base64 链接列表（vmess / vless(+reality) / ss / trojan / hysteria2）
- **节点管理**：按订阅分组、搜索、单节点/全部测速（clash_api），选择或自动测速
- **共存设计**：自动发现 AdGuardHome 的 DNS 端口并作为上游（广告过滤不绕过）；与 NekoBox 的 VPN 模式共存（VPN 优先）
- **稳定性**：状态账本、异常退出自动清理路由规则、xtables 锁重试、网络切换自动重启、看护进程
- **WebUI**：概览 / 节点 / 应用 / 设置，跟随系统深浅色主题

## 兼容性

| 项目 | 说明 |
|------|------|
| 系统 | Android 12+ 推荐（TUN 与 auto_redirect 依赖内核支持） |
| Root | KernelSU（官方/LKM 或内建内核） |
| 规则集 | 内置 geosite-cn.srs / geoip-cn.srs（来自 SagerNet） |

## 安装

1. 从 Releases 下载 `SBX-vX.Y.Z-clean.zip`
2. KernelSU 管理器 → 模块 → 从存储安装 → 重启
3. 重启后打开 WebUI（模块卡片 → 打开 WebUI）

## 首次使用

1. 打开 WebUI → 节点页 → 订阅管理 → 填写名称与订阅链接 → 添加
2. 点「更新全部」拉取节点（后台执行，完成会提示）
3. 点「重启」使新节点生效
4. 概览页选择模式（默认规则分流），节点页选择节点或「自动测速」

## WebUI 说明

- **概览**：运行状态、流量/连接数、当前节点（自动模式下显示实际选中的节点与所属订阅）、模式切换、启停
- **节点**：订阅管理 + 按订阅分组的节点列表；支持搜索与测速
- **应用**：分应用规则。点击应用弹出详情，可看到「当前规则的实际效果」并选择四档
- **设置**：开机自启、每日自动更新订阅、主题（跟随系统/浅色/深色）、运行日志（打开即实时刷新）

## 支持的分流原则

- 规则分流：`ip_is_private` 与 geosite-cn/geoip-cn 直连，其余走代理
- 连通性检测域名（connect.rom.miui.com 等）强制直连，避免系统误判「无互联网」
- DNS：优先使用 AdGuardHome 的本地 DNS，AGH 未运行时回退 223.5.5.5

## 构建

依赖：JDK 17+、d8（r8.jar）、python3、KernelSU 设备（用于测试）。

```sh
# 1) 下载 sing-box 二进制
./tools/download-singbox.sh

# 2) 构建 tool.dex（应用名导出 + 订阅转换工具）
D8_JAR=/path/to/r8.jar HAPB_JAR=/path/to/hiddenapibypass-classes.jar ./tools/build-dex.sh

# 3) 组装模块 zip
./tools/build-module.sh
```

目录结构：

```
module/            # 模块内容（安装后对应 /data/adb/modules/sbx_proxy）
  module.prop
  customize.sh     # 安装脚本
  service.sh       # 开机启动 + 看护（自启/网络切换/AGH 端口变化/每日更新）
  sbxctl.sh        # 控制脚本（start/stop/restart/status/node/delay/traffic/labels/subs/task）
  uninstall.sh
  config.json      # sing-box 配置模板（含占位符）
  rules/           # geosite-cn.srs / geoip-cn.srs
  webroot/         # WebUI
app/               # Java 工具源码（tool.dex）：应用名导出、订阅解析与配置生成
tools/             # 构建脚本
```

## 常见问题

- **启动失败**：看设置页日志。常见原因是订阅为空或节点全部不可用
- **WiFi 断网**：已处理 IPv6 捕获与连通性检测；如仍出现，确认系统内核支持 TUN 自动重定向
- **xtables 锁**：启动内置重试（最多 3 次）
- **AdGuardHome 端口变化**：看护进程会自动重启代理使 DNS 指向新端口
- **订阅 0 节点**：确认订阅可访问；模块同时支持 Clash YAML 与 Base64 链接列表

## 许可

- 本仓库代码：MIT
- [sing-box](https://github.com/SagerNet/sing-box)：GPL-3.0（发布包中内置其二进制）
- 规则集来自 [sing-geosite](https://github.com/SagerNet/sing-geosite) / [sing-geoip](https://github.com/SagerNet/sing-geoip)
- 感谢 KernelSU / LSPosed 社区

## 致谢

- sing-box 项目与 SagerNet
- KernelSU 项目
