# 图标资源

本次图标直接使用用户提供的透明 PNG，保留广告窗口、盾牌、禁止符号、环绕飘带以及原有薄荷渐变。

- 原始文件：`codex-clipboard-aaa1a5b5-3ee8-457a-812d-71c8fce2e8ba.png`
- 尺寸：1254 × 1254，RGBA。
- `icon-source.png` 与 `icon-master.png` 均为原始文件的完整副本，字节一致。
- SHA-256：`609ffae076e9d3d435f34e1a08b0dd519fe30b6515ab43c2c17270edc3b68d92`。
- 没有重新绘制、调色、裁切主体或清理透明边缘。圆角方块外的极低透明度像素保留原状，缩小后不影响显示。

## Android 资源

| 文件 | 内容 |
|---|---|
| `mipmap-{mdpi..xxxhdpi}/ic_launcher.png` | 完整原图按 48 / 72 / 96 / 144 / 192 像素等比缩小 |
| `mipmap-{mdpi..xxxhdpi}/ic_launcher_round.png` | 同一完整图稿，圆形显示由启动器遮罩决定 |
| `mipmap-{mdpi..xxxhdpi}/ic_launcher_foreground.png` | 108 dp 透明画布，完整图稿置于居中的 72 dp 内容区 |
| `mipmap-anydpi-v26/ic_launcher{,_round}.xml` | 自适应前景和背景引用 |
| `values/ic_launcher_background.xml` | 背景色 `#27A4B7`，来自图稿四个不透明内角区域的 RGB 中位值 |

自适应图标由不同手机启动器施加圆形、圆角方形等遮罩。主体不铺满整个 108 dp 画布，避免圆形遮罩切掉盾牌和广告窗口；外部背景承担系统遮罩和动效区域。项目最低 Android 版本为 26，正常使用自适应图标资源。

## 重新打包

在项目根目录运行：

```powershell
python docs/artwork/package_launcher.py
```

脚本只进行等比缩放、居中放置和资源打包，不会修改图稿。更换图稿后需同步脚本中的尺寸断言和背景色。

`launcher-previews/launcher-masks.png` 展示完整图稿、圆形遮罩和圆角方形遮罩。`launcher-previews/packaging-audit.json` 记录源文件指纹、密度和内容区域尺寸。
