# Tide Autofish

**作者：Rain Sextet**

一个面向 **Minecraft 1.20.1 Forge**、专门适配 **[潮汐 / Tide](https://modrinth.com/mod/tide) 2.1** 的客户端自动钓鱼模组。

通用自动钓鱼模组（例如 ForgeAutoFish）只识别原版 `FishingHook` 的咬钩信号，而 Tide 用自己的 `TideFishingHook` 实体替换了原版浮标，并且在咬钩后进入一个小游戏。星露谷钓鱼（Stardew Fishing）安装时，Tide 还会把捕鱼交给星露谷的小游戏。因此普通 autofish 会表现为“能抛竿、不咬钩、不收竿”或“收竿后小游戏失败”。

本模组把这些环节全部接管：

- **自动抛竿 / 自动重抛**：兼容 Tide 的 `holdToCast`（长按蓄力）和普通单击抛竿。
- **咬钩检测**：读取 Tide 同步到客户端的 `TideFishingHook.isFishHooked()`。
- **自动收竿**：咬钩的同一 tick 内发送正常的 `useItem` 包，不直接改服务端状态。
- **自动小游戏**：
  - Tide 原生小游戏：在指针进入判定区时自动点击，优先完美判定。
  - 星露谷钓鱼小游戏：用模型预测控制（MPC）模拟浮标未来 6 tick 的物理，自动按压/松开。
- **自动重抛**：非鱼类战利品（箱子、物品）收上来后自动再次抛竿。
- **鱼竿保护**：耐久低于阈值时自动停止。
- **前台界面下继续工作**：打开背包、聊天、容器等界面时自动钓鱼不会暂停；该功能始终开启，没有关闭它的配置项。
- **HUD / 按键 / 配置**：`V` 开关；左上角状态提示；`config/tideautofish-client.toml`。

## 环境要求

| 组件 | 版本 |
| --- | --- |
| Minecraft | 1.20.1 |
| Forge | 47.x（构建使用 47.2.0） |
| Tide | 2.1+（必需，`mods.toml` 中强制） |
| Stardew Fishing | 3.7（可选；安装后自动适配其小游戏） |

客户端功能；服务器不需要安装（即使安装到服务器，也不会执行任何自动操作）。

## 安装

1. 安装 Forge 1.20.1。
2. 把 `build/libs/tideautofish-1.1.0.jar` 放进 `mods/`。
3. 确保 `[潮汐] tide-forge-1.20.1-2.1.jar` 也在 `mods/` 中。
4. 可选：安装 `stardew_fishing-3.7-1.20.1.jar`，本模组会自动识别并接管它的小游戏。

> 本模组的 mod id 是 `tideautofish`，与 Modrinth 上 Duox 的 “Tide Autofish” 相同。**不要同时安装两者**，否则 Forge 会因重复 mod id 拒绝启动。

## 使用

1. 手持鱼竿（原版鱼竿即可；Tide 默认会把原版鱼竿替换为 Tide 鱼竿）。
2. 面向水面，按 `V` 开启。HUD 显示 `Tide Autofish: ON (waiting for bite)`。
3. 角色会自动抛竿、等待咬钩、收竿、完成小游戏并重抛。

默认 `startEnabled=false`，每次启动需要按 `V` 开启；可在配置中改为 `true`。

## 配置

`config/tideautofish-client.toml`：

```toml
[general]
    startEnabled = false          # 启动时自动开启
    showHud = true                # 显示左上角状态
    showHudWhenDisabled = false
    showStateInHud = true
    debug = false

[fishing]
    rodProtection = true          # 低耐久停止
    minDurability = 10
    castHoldTicks = 30            # holdToCast 时按住多少 tick（满蓄力需要 25）
    recastDelayTicks = 10         # 收竿后多久重抛
    maxWaitTicks = 1200           # 60 秒无咬钩则重新抛竿

[minigame]
    autoMinigame = true           # Tide 原生小游戏自动点击
    perfectClicks = true          # 优先完美判定
    stardewAutoPlay = true        # 星露谷小游戏自动操作
```

## 构建

需要 **JDK 17**（ForgeGradle 6 不支持用 JDK 21+ 直接构建 1.20.1）：

```bash
# Linux/macOS
./gradlew build

# Windows
gradlew.bat build
```

产物：`build/libs/tideautofish-1.1.0.jar`。

如果所在网络访问 `services.gradle.org` 较慢，可以修改 `gradle/wrapper/gradle-wrapper.properties` 的 `distributionUrl` 为镜像，例如腾讯云：

```properties
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.1.1-bin.zip
```

## 实现原理（简述）

详见 [`docs/TECHNICAL.md`](docs/TECHNICAL.md)。

- Tide 的 `TideFishingRodItem.use` 在浮标已存在时：
  - 咬钩且战利品是鱼 → 服务端创建 `FishCatchMinigame`（原生）或 `FishingHookLogic.startStardewMinigame`（星露谷），客户端收到小游戏开始包；
  - 战利品是箱子/物品 → 立即 `retrieveHook`。
- 因此自动钓鱼只需要：检测 `TideFishingHook`、检测 `isFishHooked()`、在咬钩时调用一次 `MultiPlayerGameMode.useItem`，然后接管小游戏。
- Tide 原生小游戏：通过反射读取 `CatchMinigameOverlay` 的 `isActive` / `area` / `delayTime` / `timeLeft`，调用 `getMinigamePosition()` 和 `interact()`。
- 星露谷小游戏：反射读取 `FishingScreen.minigame` 的 `bobberPos` / `bobberVelocity` / `fishPos` / `fishVelocity` / `barSize`，每 tick 用 MPC 计算按压方向，再调用 `FishingScreen.setInputDown`。
- 所有对 Tide / 星露谷内部的访问都用反射并做降级处理；Tide 版本不匹配时只会禁用对应功能，不会让游戏崩溃。

## 已知限制

- 必须让 Tide 接管原版鱼竿：Tide 配置 `general.overrideVanillaRod=true`（默认）。若关闭，模组会提示并暂停，因为此时使用的是原版浮标。
- 需要玩家自己面向水面；模组不会强制转动视角。
- 不支持 `starcatcher` 的第三方小游戏（Tide 对其只做 fallback，`starcatcherStartMinigame` 返回 false 时会直接收竿，仍能自动重抛）。
- 自动小游戏依赖 Tide 2.1 / Stardew Fishing 3.7 的私有字段名；如果它们大版本更新改了这些字段，对应的自动小游戏会安全禁用，但自动抛竿/收竿/重抛仍然可用。
- 前台界面下继续工作指的是模组不会主动暂停：单人游戏的暂停菜单仍会暂停世界，容器界面下部分服务器也会拒绝使用物品，这两种情况下无法凭空产生咬钩或完成服务端交互。
- 在多人服务器上使用自动钓鱼可能违反服务器规则；请自行确认。

