# 363Updater

客户端 Minecraft modpack 配置更新器。它从 Modrinth 获取兼容的 `.mrpack`，比较当前包和目标包，并只更新 `config/**` 与 `options.txt`。

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

## 构建

用 `updater_target` 选择与 `363changelog` 同名的构建 profile：

```bash
./gradlew build -Pupdater_target=26.1.2
./gradlew build -Pupdater_target=26.2
./gradlew build -Pupdater_target=1.21.1
./gradlew build -Pupdater_target=1.20.1-fabric
./gradlew build -Pupdater_target=1.20.1-forge
```

产物位于：

- Fabric: `fabric/build/libs/363updater-<mc>-fabric-0.1.0.jar`
- NeoForge: `neoforge/build/libs/363updater-<mc>-neoforge-0.1.0.jar`
- Forge 1.20.1: `forge/build/libs/363updater-1.20.1-forge-0.1.0.jar`

26.x/1.21.1 使用根项目的 Gradle 9 构建。Forge 1.20.1 会由根任务自动切换到仓库内的 Gradle 8.8 子构建；如 JDK 17 不在常见系统路径，可设置 `UPDATER_FORGE_JAVA_HOME`。

## 配置协议

默认版本来源按以下顺序选择：ModMenu 中的 `currentVersionOverride`；默认 `363fan` 项目在启用联动且文件存在时使用 `config/changelog363.json`；其他情况使用 updater 的项目/MC/loader 状态。更新成功后会写入状态；对于默认 `363fan` 项目，若 changelog 文件存在且 `syncChangelog363Version` 开启，也会同步写回 `modpackVersion`。

`currentVersionOverride` 适合首次接入已有整合包或自定义项目：成功更新一次后会清空该一次性引导值，之后使用 updater 状态。自定义项目不要求安装 `363Changelog`；也可以关闭 `syncChangelog363Version`，完全使用 updater 状态。

`targetVersionOverride` 可固定目标版本。ModMenu 未安装时 updater 仍可运行，但不会显示配置界面。

## 安全边界

updater 不替换模组 jar，不更新资源包、shaderpacks、PCL 或其他 mrpack 文件。写入范围被限制为 `config/**` 和 `options.txt`，并拒绝目录穿越及符号链接目标。更新前的完整备份先在临时目录构建，完成后再发布；事务失败时会恢复更新前的配置。
