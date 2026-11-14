# Tide Autofish 技术说明

本文记录对 `[潮汐] tide-forge-1.20.1-2.1.jar` 的反编译分析，以及自动钓鱼模组的设计依据。分析对象：

- Tide `2.1`（Forge 1.20.1），入口 `com.li64.tide.Tide`
- Stardew Fishing `3.7`（Forge 1.20.1），入口 `com.bonker.stardewfishing.StardewFishing`

## 1. Tide 如何改掉原版钓鱼

### 1.1 原版鱼竿被替换

`com.li64.tide.mixin.ItemsMixin` 在 `Items.<clinit>` 里把 `fishing_rod` 的 `FishingRodItem` 替换为 `TideFishingRodItem`：

```java
return (FishingRodItem)(!Tide.CONFIG.general.overrideVanillaRod
    ? new FishingRodItem(properties)
    : new TideFishingRodItem(1, 32.0, properties));
```

- 默认 `overrideVanillaRod=true`，所以玩家手里的原版鱼竿实际是 `TideFishingRodItem`。
- 如果玩家把该配置关掉，原版鱼竿会产生原版 `FishingHook`，本模组无法识别，会自动提示并暂停。

### 1.2 浮标实体

`TideFishingRodItem.castHook` 在服务端生成 `TideFishingHook`（实体类型 `TideEntityTypes.FISHING_BOBBER`）。它 **不是** 原版 `FishingHook`，而是继承 `Projectile` 的独立实体。

客户端通过 `HookAccessor` 把 `TideFishingHook` 挂到 `Player.fishing` 字段上：

```java
public static TideFishingHook getHook(Player player) {
    return player.fishing instanceof HookAccessor accessor ? accessor.hook : null;
}
```

因此客户端判断“是否有 Tide 浮标”只需要 `HookAccessor.getHook(player) != null`。

### 1.3 咬钩信号

`TideFishingHook.catchingFish` 的时间线：

1. 浮标入水后 `timeUntilLured = rand(200, 600) * medium.biteTimeMultiplier()`，再按鱼饵/诱饵速度缩短。
2. `timeUntilLured` 到 0 后，`timeUntilHooked = rand(20, 80)`，同时画鱼靠近的粒子。
3. `timeUntilHooked` 到 0 时：
   - 播放水花；
   - `nibble = rand(20, 40)`；
   - `DATA_BITING = true`；
   - `selectCatch(rod)` 决定这次是鱼 / 箱子 / 物品 / 空。

`nibble` 是玩家可以反应的时间窗口（1–2 秒）。如果这段时间没有收竿，`nibble` 归零，`restartFishingSequence()` 重置，鱼跑掉。

客户端可以看到 `DATA_BITING`（`TideFishingHook.onSyncedDataUpdated` 会同步到 `biting` 字段），公开方法是：

```java
public boolean isFishHooked() { return this.biting; }
```

这就是自动钓鱼的咬钩检测点。它比“看浮标下沉的 y 速度”可靠，因为 Tide 在 `biting` 时确实会给浮标一个向下的速度，但粒子/同步延迟会让速度判断抖动。

### 1.4 收竿：一次右键同时处理“非鱼战利品”和“小游戏”

`TideFishingRodItem.use`（右击）在浮标存在时：

```java
if (isMinigameStopped(player, clientSide) && Tide.CONFIG.minigame.doMinigame) {
    if (CompatHelper.useStardewMinigame()) {
        if (catchType != FISH && catchType != ITEM) retrieveHook(...);
        else if (!client) {
            if (!stardewFishingStartMinigame(...)) retrieveHook(...);
        }
    } else if (hook.getCatchType() == FISH) {
        if (!client) {
            FishCatchMinigame.create(player);   // 发 MinigameClientMsg 开始包
            ...
        }
    } else {
        retrieveHook(...);                       // 箱子 / 物品 / 空
    }
} else if (!doMinigame) {
    hook.retrieve();                             // 关闭小游戏，直接给战利品
} else if (client) {
    CatchMinigameOverlay.interact();             // 小游戏进行中，客户端点击
}
```

关键结论：

- 对鱼：第一次右键 = 开始小游戏；小游戏进行中的右键 = 客户端 `interact()`。
- 对箱子/物品/空：第一次右键 = 立即收竿，浮标消失。
- 所以自动钓鱼只需要在 `isFishHooked()` 时发送 **一次** 正常的 `useItem`，不需要区分战利品类型。

### 1.5 Tide 原生小游戏

`CatchMinigameOverlay` 是纯客户端静态类：

```java
public static void start(byte type, byte behavior, float area, float speed);
public static void interact();
public static boolean isActive();
private static float getMinigamePosition();
```

- `interact()` 计算 `Math.abs(getMinigamePosition())`：
  - `< 0.1` → perfect，发 `MinigameServerMsg(3)`；
  - `< area` → catch，发 `MinigameServerMsg(2)`；
  - 否则 miss，发 `MinigameServerMsg(1)`；
  - 然后 `close()`。
- `start()` 后有 `delayTime = now + 200ms`，之前点击无效。
- `timeLeft` 到 80 时小游戏超时，自动发 `MinigameServerMsg(0)`（timeout）。
- 服务端 `FishCatchMinigame.handleClientEvent`：3→`onWin(true)`，2→`onWin(false)`，1→`onFail()`，0→`onTimeout()`。
- `onWin` 调 `hook.retrieve(perfect)`，给战利品并移除浮标；`onFail` 调 `invalidateCatch()` + `retrieve()`，什么都没有。

自动点击策略：

1. `isActive` 且 `now >= delayTime`；
2. 若 `area > 0.12` 且还没到 `timeLeft >= 65`，只在 `|pos| < 0.1` 时点击（完美）；
3. 否则放宽到 `|pos| < 0.9 * area`（保证至少普通捕获）；
4. `area <= 0.12` 时直接使用 `0.9 * area`。

### 1.6 Stardew Fishing 接管

Tide 配置 `minigame.useThirdPartyMinigames=true`，且检测到 `stardew_fishing` 时：

```java
public static boolean useStardewMinigame() {
    return Tide.PLATFORM.isModLoaded("stardew_fishing")
        && Tide.CONFIG.minigame.useThirdPartyMinigames;
}
```

`StardewFishingCompat.start` 只有在战利品包含 `stardew_fishing:starts_minigame` 标签时才启动小游戏，否则 `retrieveHook` 立即给物品。

启动后：

- 服务端 `FishingHookLogic.startStardewMinigame(player)` 发送 `S2CStartMinigamePacket`；
- 客户端 `ClientProxy.openFishingScreen` 打开 `FishingScreen`；
- `FishingScreen.tick()` 每 tick 调 `FishingMinigame.tick(inputDown)`；
- 小游戏结束后 `FishingScreen.onClose()` 发 `C2SCompleteMinigamePacket(success, accuracy, gotChest)`；
- 服务端成功则 `modifyRewards` + `giveRewards`，并 `hook.discard()`；失败也 `hook.discard()`。

所以星露谷小游戏存在时：

- 咬钩 → 右键 → **不会**出现 Tide 的 `CatchMinigameOverlay`，而是出现 `FishingScreen`；
- 必须自动操作这个 Screen，否则 4 秒左右就会失败，浮标被移除。

`FishingScreen` 提供了公开方法：

```java
public void setInputDown(boolean down);   // 等价于按住/松开小游戏按钮
```

这比模拟鼠标事件稳定得多。自动操作只需要每 tick 调用它。

## 2. 星露谷小游戏物理与自动控制

### 2.1 物理

来自 `FishingMinigame.tick`：

```java
if (mouseDown) {
    if (bobberVelocity < 0) bobberVelocity *= 0.9;
    bobberVelocity += 0.7;
} else if (bobberPos > 0) {
    bobberVelocity += -0.7;
}
bobberPos += bobberVelocity;

if (bobberPos > maxBobberHeight) { bobberPos = maxBobberHeight; bobberVelocity = 0; }
else if (bobberPos <= 0) {
    bobberPos = 0;
    bobberVelocity = (bobberVelocity < -1.4) ? bobberVelocity * -0.4 : 0;
}
```

- 浮标条位置 `bobberPos ∈ [0, 142 - barSize]`；默认 `barSize=36`，即上限 106。
- 判定窗口：`[floor(bobberPos) - 2, floor(bobberPos) + barSize - 12]`，默认宽 26。
- 鱼在 `[0, 127]` 内朝随机目标移动，速度上限来自鱼种 `FishBehavior`；Tide 为每种鱼写入了 `data/stardew_fishing/fish_behaviors.json`，例如：

| 鱼 | top_speed | accel | avg_distance | variation |
| --- | --- | --- | --- | --- |
| `tide:carp` | 1.92 | 0.28 | 40 | 4 |
| `tide:tuna` | 3.84 | 0.46 | 55 | 6 |
| `tide:coelacanth` | 5.12 | 0.58 | 92 | 29 |
| `tide:shooting_starfish` | 7.04 | 0.76 | 88 | 25 |
| `tide:alpha_fish` | 6.72 | 0.73 | 90 | 27 |
| 默认 | 1.5 | 0.7 | 55 | 10 |

- 得分：初始 24，鱼在窗口内每 tick +1，到 120 成功；不在窗口内每 tick `-2 + lineStrength`（默认 -1），到 0 失败。因此大约需要 96 个“在窗口内”的 tick 才能成功。

### 2.2 控制策略：短时域 MPC

由于输入只有“按住/松开”，且物理完全确定，可以用模型预测控制：

```
for 每条长度 6 的输入序列 (2^6 = 64 条):
    从当前 bobberPos/bobberVelocity 出发模拟 6 tick
    鱼按当前 fishVelocity 线性外推（并 clamp 到 0..127）
    cost += (fish - 窗口中心)^2
    如果鱼在窗口外，cost += 60
选择 cost 最小的序列，执行它的第一个输入
```

- 视界 6 tick：足够抵消浮标动量，又不会因为鱼随机换目标而过度预测。
- 每 tick 64×6 = 384 次简单浮点运算，20 TPS 下开销可以忽略。
- 决策在 `ClientTickEvent` END 读取状态，作用于下一次 `FishingScreen.tick`，存在 1 tick 延迟；离线模拟已包含这个延迟。

离线模拟（复刻 `FishingMinigame` 物理与鱼 AI，每组 25 局）：

| 鱼种 | 简单“鱼在上方就按” | PD 控制 | MPC（视界 6） |
| --- | --- | --- | --- |
| `tide:tuna` | 94% | 100% | 100% |
| `tide:coelacanth` | 95% | 100% | 99–100% |
| `tide:alpha_fish` | 2% | 41% | 100% |
| `tide:shooting_starfish` | 0% | 16% | 98–100% |
| `stardew:catfish` (top 8) | 96% | 100% | 100% |

MVP 的胜率在困难鱼上明显不足，这也是选择 MPC 的原因。完美度（accuracy）在最快鱼上约 67–74%，对应星露谷品质 0–1；这是“先保证钓上来”的取舍。上表是 Python 复刻与 Java 控制器（`sim/java/StardewSim.java`）多组随机种子的结果区间。

### 2.3 需要读取的私有字段

`FishingScreen` / `FishingMinigame` 没有暴露全部状态，反射读取：

| 字段 | 类型 | 用途 |
| --- | --- | --- |
| `FishingScreen.minigame` | `FishingMinigame` | 入口 |
| `FishingScreen.status` | 枚举 | 只在 `MINIGAME` 阶段注入输入 |
| `FishingMinigame.bobberPos` | double | 浮标位置 |
| `FishingMinigame.bobberVelocity` | double | 浮标速度 |
| `FishingMinigame.fishPos` | double | 鱼位置 |
| `FishingMinigame.fishVelocity` | double | 鱼速度 |
| `FishingMinigame.barSize` | int | 判定窗口大小 |
| `FishingMinigame.maxBobberHeight` | int | 浮标上限 |

## 3. 自动钓鱼状态机

```
IDLE ──有鱼竿──> CAST_CHARGING ──松开/立即抛──> WAIT_HOOK
  ▲                    │                              │
  │                    └── 120 tick 超时 ──> RECAST_DELAY
  │                                                   │
  │                                            WAIT_HOOK ──浮标出现──> WAITING
  │                                                                       │
  │                                                     isFishHooked() ───┤
  │                                                                       ▼
  │                                                                   REELING
  │                                                                       │
  │                     ┌─────────────────────────────────────────────────┤
  │                     ▼                                                 ▼
  │            TIDE_MINIGAME（原生 overlay）                     STARDEW_MINIGAME（Screen）
  │                     │                                                 │
  │                     └──────────── 小游戏结束 / 浮标消失 ──────────────┘
  │                                                                       │
  └────────────────────────── RECAST_DELAY <──────────────────────────────┘
```

要点：

- **抛竿**：调用 `MultiPlayerGameMode.useItem(player, hand)`；随后保持 `castHoldTicks` 再发送 `releaseUsingItem()`。不再依赖 `player.isUsingItem()` 判断模式：即使界面打开导致客户端“使用中”状态被取消，服务端仍然会收到 release 包并完成抛竿；如果浮标已经出现，则直接进入 `WAITING`。
- **前台界面**：除 Stardew 的 `FishingScreen` 外，任何 Screen 都不会暂停状态机；HUD 不绘制在 GUI 上，但抛竿、收竿、小游戏自动操作继续。该行为始终开启，没有配置开关。
- **收竿**：同样调用 `useItem`，不直接调用 Tide 的 `retrieve`，这样服务端走正常的 `TideFishingRodItem.use` 路径。
- **箱子/物品**：收竿后浮标立即消失，状态机自动进入 `RECAST_DELAY` 并重抛。
- **小游戏失败**：Tide 原生小游戏超时后浮标会 `restartFishingSequence()` 留在水里，状态机回到 `WAITING`；星露谷失败会移除浮标，状态机重抛。
- **超时保护**：`WAITING` 超过 `maxWaitTicks`（默认 60 秒）会主动收竿重抛，避免抛到岸上后永远等待。
- **鱼竿保护**：耐久 ≤ `minDurability` 时关闭自动钓鱼并提示。

## 4. 反射映射表

| 目标 | 成员 | 说明 |
| --- | --- | --- |
| `com.li64.tide...HookAccessor` | `static TideFishingHook getHook(Player)` | 取当前 Tide 浮标 |
| `com.li64.tide...TideFishingHook` | `boolean isFishHooked()` | 咬钩 |
| `com.li64.tide...CatchMinigameOverlay` | `static boolean isActive` | 原生小游戏进行中 |
| | `static float area` | 判定区 |
| | `static long delayTime` | 200ms 初始延迟 |
| | `static float timeLeft` | 已进行时间（0..80） |
| | `static float getMinigamePosition()` | 指针位置 |
| | `static void interact()` | 提交点击 |
| `com.bonker...FishingScreen` | `FishingMinigame minigame` | 小游戏实例 |
| | `Status status` | 只在 `MINIGAME` 注入 |
| | `void setInputDown(boolean)` | 按住/松开 |
| `com.bonker...FishingMinigame` | `bobberPos/bobberVelocity/fishPos/fishVelocity/barSize/maxBobberHeight` | MPC 状态 |

所有反射都在 `TideBridge` 中懒加载并 `try/catch`；缺少 Stardew 时只关闭星露谷支持，不影响 Tide 原生路径。

## 5. 为什么不用 Mixin

参考实现（Duox 的 Tide Autofish）用 Mixin 注入 `CatchMinigameOverlay.render`。本模组选择纯 Forge 事件 + 反射，原因：

1. **构建简单**：不需要 `MixinGradle`、refmap、Tide 的 deobf 依赖；项目的 Gradle 配置只依赖 Forge。
2. **可选兼容**：星露谷钓鱼是可选模组，反射失败可以优雅降级；Mixin 目标类缺失或字段改名更容易导致启动失败。
3. **足够低延迟**：`RenderGuiEvent.Post` 每帧执行，Tide 原生小游戏点击精度足够；星露谷以 20 TPS 的 Screen tick 为节拍，反射开销可以忽略。

代价是依赖 Tide 2.1 / Stardew 3.7 的成员名；大版本升级后可能需要更新 `TideBridge`，但失败时不会崩溃。

## 6. 测试

- `sim/minigame_sim.py`：复刻星露谷小游戏物理与鱼 AI，用于对比控制器和调参。
- `sim/tune2.py` / `tune3.py`：MPC 参数扫描。
- 构建验证：`gradlew build`（JDK 17）。
- 游戏内验证建议：
  1. 原版环境（只有 Tide）：抛竿 → 等咬钩 → 原生小游戏自动完美/普通捕获；
  2. 加 Stardew Fishing：确认出现星露谷 Screen，HUD 显示 `(minigame)`，小游戏自动完成；
  3. 钓到箱子/物品时自动重抛；
  4. 关闭 Tide 的 `holdToCast`，确认单击抛竿路径；
  5. 耐久耗尽前自动停止。
