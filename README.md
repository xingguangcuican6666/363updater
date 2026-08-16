# 363Updater

客户端 Minecraft modpack 更新器（当前版本 `0.2.0`）。它从 Modrinth 获取兼容的 `.mrpack`，比较当前包和目标包，并以事务方式更新受管模组、`config/**` 与 `options.txt`。

## 当前实现

- 默认项目 `363fan` 会尝试通过 `config/changelog363.json` 读取当前版本；`363Changelog` 不是必需前置。
- Modrinth 项目、Minecraft 版本、加载器和版本渠道可在 ModMenu 配置。
- 默认项目为 `363fan`，默认渠道为 `release,beta`。
- 读取标准 mrpack 的 `overrides/` 与 `client-overrides/`，因此同一更新器也可用于其他 Modrinth 整合包项目。
- 每个项目、Minecraft 版本和 loader 的当前版本会记录到 `config/updater363-state.json`；updater 自己的配置和状态不会被目标 mrpack 覆盖。
- 共同配置使用三方合并：当前值仍等于旧包值时才替换；用户改过的值保留。
- 新包新增键会加入，新包删除的键/文件会按配置删除。
- JSON/JSON5、常见 TOML、properties 和 `options.txt` 支持结构化合并；其他格式仅在本地未修改时替换，或由高级选项明确允许强制替换。
- 更新前备份整个 `config/` 和 `options.txt`，默认保留最近 3 次。
- 更新后可尝试资源重载和注册的配置重载器；失败项会提示重启。
- 内置简体中文、繁体中文、英语、日语、韩语、德语、法语、西班牙语、俄语和巴西葡萄牙语。
- 受管模组更新默认关闭；开启后会保留未受整合包管理的用户模组，并在受管文件被本地修改时中止而不是覆盖。
- 实验性快速重启默认关闭，只能从主菜单启动；在受支持的 Linux/Windows 构建中从暂存模组代际启动全新 JVM，稳定后再交接窗口和提交正式 `mods/`。
- 更新受管 JAR 会明确按新 JVM 执行代码，不承诺 JVM 内原位热重载；无法安全复现启动参数时会自动隐藏快速重启并保留普通重启选项。

## Modrinth 版本支持

updater 的业务代码与加载器适配代码分离。发布时为每个 `363changelog` 支持分支构建对应 jar：

| Minecraft 分支 | 加载器 |
|---|---|
| `1.20.1-fabric` | Fabric |
| `1.20.1-forge` | Forge |
| `1.21.1` | Fabric / NeoForge |
| `26.1.2` | Fabric / NeoForge |
| `26.2` | Fabric / NeoForge |

运行时从平台适配层读取 Minecraft 版本和加载器，然后筛选 Modrinth 版本。当前版本必须能在 Modrinth 中精确找到；找不到时会停止更新，不根据日期猜测基线。

0.2.0 的快速重启矩阵覆盖以下 8 个发行产物：

| Minecraft | 加载器 | 产物 |
|---|---|---|
| 1.20.1 | Fabric | `fabric/build/libs/363updater-1.20.1-fabric-0.2.0.jar` |
| 1.20.1 | Forge | `forge/build/libs/363updater-1.20.1-forge-0.2.0.jar` |
| 1.21.1 | Fabric / NeoForge | `fabric/build/libs`、`neoforge/build/libs` |
| 26.1.2 | Fabric / NeoForge | `fabric/build/libs`、`neoforge/build/libs` |
| 26.2 | Fabric / NeoForge | `fabric/build/libs`、`neoforge/build/libs` |

## 构建

用 `updater_target` 选择与 `363changelog` 同名的构建 profile：

```bash
./tools/build_matrix.sh

# 或单独构建某个 profile
./gradlew build -Pupdater_target=26.1.2
./gradlew build -Pupdater_target=26.2
./gradlew build -Pupdater_target=1.21.1
./gradlew build -Pupdater_target=1.20.1-fabric
./gradlew build -Pupdater_target=1.20.1-forge
```

产物位于：

- Fabric: `fabric/build/libs/363updater-<mc>-fabric-0.2.0.jar`
- NeoForge: `neoforge/build/libs/363updater-<mc>-neoforge-0.2.0.jar`
- Forge 1.20.1: `forge/build/libs/363updater-1.20.1-forge-0.2.0.jar`

26.x/1.21.1 使用根项目的 Gradle 9 构建。Forge 1.20.1 会由根任务自动切换到仓库内的 Gradle 8.8 子构建；如 JDK 17 不在常见系统路径，可设置 `UPDATER_FORGE_JAVA_HOME`。

快速重启矩阵覆盖表中的全部 8 个发行产物。Fabric 使用 `fabric.modsFolder` 从暂存代际启动；Forge/NeoForge 仅在交接子进程中启用随 JAR 携带的 Java agent，以重定向生产 mods 目录并隐藏加载器早期窗口。Windows 使用 JNA 读取当前 JVM 命令行，Forge/NeoForge 的 agent 会在旧进程退出后提交正式 `mods/`，避免文件锁冲突。

## 验证

```bash
./tools/build_matrix.sh
```

当前构建矩阵与 common 单元测试已通过：8 个发行产物、57 个测试均成功。Linux 已完成运行验收；Windows 快速重启代码已纳入矩阵构建，但仍需要在 Windows HMCL 中实际启动并验证窗口交接。

## 配置协议

默认版本来源按以下顺序选择：ModMenu 中的 `currentVersionOverride`；默认 `363fan` 项目在启用联动且文件存在时使用 `config/changelog363.json`；其他情况使用 updater 的项目/MC/loader 状态。更新成功后会写入状态；对于默认 `363fan` 项目，若 changelog 文件存在且 `syncChangelog363Version` 开启，也会同步写回 `modpackVersion`。

`currentVersionOverride` 适合首次接入已有整合包或自定义项目：成功更新一次后会清空该一次性引导值，之后使用 updater 状态。自定义项目不要求安装 `363Changelog`；也可以关闭 `syncChangelog363Version`，完全使用 updater 状态。

`targetVersionOverride` 可固定目标版本。ModMenu 未安装时 updater 仍可运行，但不会显示配置界面。

`modrinthApiRoot` 默认是 `https://api.modrinth.com/v2`。它可以指向兼容的镜像或本地测试服务；API 返回的 mrpack 下载 URL 也可以使用本地 HTTP 地址。

## 本地 API 测试

仓库提供一个只实现 updater 所需端点的 mock server：

```bash
python3 tools/mock_modrinth_server.py \
  --old-package /path/to/0.20.2.mrpack \
  --target-package /path/to/0.21.1.mrpack
```

然后在 ModMenu 中设置：

- `Modrinth API root`: `http://127.0.0.1:8763/v2`
- `Modrinth project`: `363fan`
- `Current version override`: `0.20.2`
- `Minecraft version override`: `26.1.2`
- `Loader override`: `fabric`

服务提供 `/health`、`/v2/project/363fan/version` 和 `/files/<mrpack>`，下载仍经过 updater 的大小与 SHA-512 校验。

## 安全边界

updater 只处理 mrpack 索引及 `overrides/client-overrides` 中的 `mods/**`、`config/**` 和 `options.txt`，不更新资源包、shaderpacks、PCL 或其他文件。下载会限制重定向和大小并校验 SHA-512/SHA-1；路径穿越、符号链接和目标碰撞会被拒绝。更新先生成完整暂存代际和备份，正式提交失败时新客户端可继续从暂存代际运行并保留事务供重试或恢复。updater 自身受保护，目标包删除它时不会被移除。
