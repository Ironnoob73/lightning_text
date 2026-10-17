> ### 🐋 AI-generated content disclosure
> This mod was generated almost entirely by AI, with only minimal human intervention.

# Lightning Text

纯客户端 NeoForge 模组：把原版 `/title` 的标题显示替换成《控制》/《明日方舟：终末地》进入新地区时的那种大标题，
并额外提供「天数」「首次进入生物群系」「首次遇到 Boss」三种自动大标题。

A client-side NeoForge mod that replaces the vanilla `/title` output with oversized "new region" style banners,
plus automatic banners for a new in-game day, for entering a biome for the first time and for the first time a boss
is actually visible on screen. Everything the player has already seen is remembered per world / server on disk.

## 功能 / Features

* **替换原版 title**：拦截 `Gui#setTitle` / `setSubtitle` / `setTimes`，所有会进入原版标题槽的内容（`/title` 命令、
  服务器数据包、其它模组调用的 `Gui#setTitle`）都改用大标题渲染。`fadeIn` / `fadeOut` / `stay` 参数照旧生效。
* **系统黑体字体**：运行时从操作系统读取 TrueType 字体（默认自动寻找 `simhei.ttf` 黑体），
  经 FreeType 烘焙成 Minecraft 的 `Font`。字号按最终像素尺寸烘焙，配合最近邻采样，因此大字号依然锐利。
  - 找不到系统字体时自动退化为原版字体（按原版 4 倍缩放），功能不受影响。
* **尽量大的字号**：初始字号为屏幕高度的 17%；文本宽度超过屏幕宽度的 92% 时按比例缩小；
  缩小下限约等于原版 title 的大小（8px 字体 ×4 ≈ 40px）。
* **天数标题**：进入新的一天时（自然天亮、睡觉跳过夜晚都会触发）显示
  - 主标题：`第X天`（前十天用「一～十」的独立翻译键，之后为 `第11天` 这种阿拉伯数字；
    英文为 `DAY X`）
  - 副标题：当时的游戏内时间，换算成 `HH:MM`（游戏内 0 刻 = 06:00）
* **生物群系标题**：第一次进入某个生物群系时显示
  - 主标题：本地化的生物群系名称
  - 副标题：注册名（如 `minecraft:plains`），字母使用标准银河字母（附魔台的字体 `minecraft:alt`）。
    由于原版银河字母字体只有 A-Z / a-z，`:`、`_`、数字等字符仍用普通字体显示，避免出现缺字符方块。
* **Boss 标题**：第一次**在屏幕上看到** Boss 类生物时显示，格式与生物群系标题完全相同（不需要攻击）
  - 判定条件：目标位于摄像机视锥内（确实出现在画面上）且从摄像机到它的视线没有被方块挡住；
    挡在身后、躲在墙后或处于隐身状态都不算
  - 主标题：生物的显示名（如「凋灵」，被命名牌命名过的生物显示其名字）
  - 副标题：实体注册名（如 `minecraft:wither`）的标准银河字母
  - 判定范围：末影龙、凋灵、`c:bosses` 实体标签里的任何生物（模组 / 数据包可扩展），
    另外额外包含**监守者**、**远古守卫者**、**鸡骑士**（僵尸骑在鸡上；它不是真正的实体类型，
    因此使用伪注册名 `minecraft:chicken_jockey`）
  - 多部位 Boss（末影龙）按本体判定；每 4 刻检查一次，发现即显示
* **按世界/服务器记录**：生物群系与 Boss 的「已见过」状态写在游戏目录下的
  `lightning_text/visited/` 里，一个世界 / 服务器一个 JSON 文件，重开游戏依然生效
  （与 Xaero 地图等闭源模组把探索数据放在客户端游戏目录的做法相同）
* **时间与队列**：天数 / 生物群系 / Boss 标题的 `fadeIn` 与 `fadeOut` 均为 0，持续时间取原版默认值（70 刻）；
  多个标题同时触发时排队依次显示，不会互相覆盖；命令标题会立即替换并清空队列。
* **雷声音效**：显示上述三种标题时播放 `minecraft:entity.lightning_bolt.thunder`，
  声调（pitch）为 0（Minecraft 会把它限制到 0.5，得到低沉的音色）。命令标题不播放声音。
* **完全客户端**：不发送任何数据包、不注册内容，服务器上无需安装。

## 细节说明 / Notes

* 天数从「第 1 天」开始计（`dayTime / 24000 + 1`），与 F3 的 Day 计数差 1。
* 因为替换的是原版标题槽，所有来源（`/title` 命令、服务器数据包、其它模组调用 `Gui#setTitle`）都会变成大标题；
  文本过长时会自动缩小，不会超出屏幕。
* 银河字母字体（`minecraft:alt`）只包含 A-Z / a-z，所以注册名里的 `:`、`_`、数字仍然使用普通字体，
  避免出现原版的「缺字符」方块。
* 被关闭的功能（`enable*Title`）不会记录任何东西，因此之后再打开仍然能触发「第一次」标题。

## 记录文件 / Visit records

```
<游戏目录>/lightning_text/visited/
├── world_新的世界.490ceeb2.json      ← 单人世界（按存档文件夹名）
└── server_play.example.net_25565.json ← 服务器（按服务器地址）
```

```json
{
  "format": 1,
  "key": "world:新的世界",
  "biomes": [ "minecraft:river", "minecraft:plains" ],
  "bosses": [ "minecraft:wither", "minecraft:chicken_jockey" ]
}
```

* 单人世界以存档文件夹名为键，服务器以 `ip:port` 为键（文件名里的非法字符会替换成 `_`，
  并附加一个短哈希避免重名）；子目录会自动创建。
* 写入采用「先写 `.tmp` 再原子替换」，游戏崩溃也不会留下半个文件；文件损坏时只会重新开始记录。
* 想重新看到所有标题，删掉对应文件（或删掉整个 `visited` 文件夹）即可。
* 文件全部在客户端游戏目录里，服务器与存档本体完全不受影响。

## 配置 / Config

`config/lightning_text-client.toml`（也可以在「模组列表 → 配置」界面里修改）

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `enableDayTitle` | `true` | 是否显示天数标题 |
| `enableBiomeTitle` | `true` | 是否显示生物群系标题 |
| `enableBossTitle` | `true` | 是否显示 Boss 标题 |
| `enableTitleSound` | `true` | 上述三种标题是否播放雷声 |
| `fontPath` | `""` | 自定义字体文件路径；留空则自动寻找黑体等系统字体 |
| `titleScale` | `2.5` | 最大标题尺寸倍率（0.25 ~ 3.0） |
| `biomeDetectDelay` | `20` | 进入生物群系后需停留多少刻才显示标题（防止在边界上反复触发） |

自动寻找的字体（按顺序）：`%WINDIR%/Fonts/simhei.ttf`（黑体）、`Deng.ttf`（等线）、`msyh.ttc`（微软雅黑）、
`simsun.ttc`（宋体），以及 Linux / macOS 上常见的 Noto CJK、文泉驿、STHeiti 等。

## 构建 / Build

```bash
./gradlew build
```

产物在 `build/libs/lightning_text-<version>.jar`。

## 实现要点（便于移植到其它版本）

* 只需要四个入口：
  1. `mixin/GuiMixin`：`Gui#setTitle` / `setSubtitle` / `setTimes` / `resetTitleTimes` / `clear` + `renderTitle`；
  2. `client/track/WorldTracker`：每个客户端 tick 读取 `level.getDayTime()` 与 `level.getBiome(pos)`；
  3. `client/track/BossTracker`：每 4 刻用 `levelRenderer.getFrustum()` + `EntityRenderDispatcher#shouldRender`
     判断 Boss 是否在屏幕上，再用 `level.clip`（`ClipContext.Block.VISUAL`）确认没有遮挡；
  4. `client/font/SystemFontManager`：用 `TrueTypeGlyphProvider` + `FontSet` 把系统 TTF 变成 `Font`。
* 持久化集中在 `client/store/VisitedStore`，只用 Gson（Minecraft 自带）读写世界/服务器对应的 JSON。
* 没有使用任何第三方库：字体渲染用的是 Minecraft 自带的 LWJGL FreeType，配置用的是 NeoForge 的 `ModConfigSpec`。
* 移植时要改的通常是：`GuiGraphics` 的绘制 API（1.21.2+ 改成了 `GuiRenderer` 的 `submit*` 方法）、
  字体 `Font` / `FontSet` 的构造方式、以及 `DeltaTracker` 相关签名。
