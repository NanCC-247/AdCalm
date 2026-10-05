# 图标与宣传资源

AdCalm 使用薄荷色图稿作为品牌资源。具体图形、透明度和打包参数以仓库中的源文件、脚本与审计记录为准。

## 源文件与预览

- [icon-source.png](icon-source.png)：图标源文件。
- [icon-master.png](icon-master.png)：打包使用的主图稿。
- [遮罩预览](launcher-previews/launcher-masks.png)：常见启动器形状下的显示效果。
- [打包记录](launcher-previews/packaging-audit.json)：源文件指纹、资源密度和内容区域。

## Android 资源

| 资源 | 用途 |
|---|---|
| `mipmap-*/ic_launcher.png` | 各密度启动图标。 |
| `mipmap-*/ic_launcher_round.png` | 圆形启动图标入口。 |
| `mipmap-*/ic_launcher_foreground.png` | 自适应图标前景。 |
| `mipmap-anydpi-v26/` | 自适应图标前景与背景引用。 |
| `values/ic_launcher_background.xml` | 自适应背景色。 |

透明背景保持 RGBA，主体等比缩放并居中，启动器按自身规则施加遮罩。更换图稿时复核常见遮罩下的边缘、留白与主体完整性。

## 重新打包

在项目根目录运行：

```powershell
python docs/artwork/package_launcher.py
```

更换源文件后检查脚本中的图稿尺寸和背景处理，再查看预览及审计记录。资源打包不等于重新设计图标。

## 宣传图

- [少点广告，多点清静](promo/2026-10-05/01-少点广告多点清静.png)
- [本机识别，减少打扰](promo/2026-10-05/02-本机识别减少打扰.png)
- [回退与恢复，更可控](promo/2026-10-05/03-回退与恢复更可控.png)

宣传图用于介绍现有功能，手机与功能卡片为示意；实际兼容范围、权限与恢复期限见[使用指南](../guides/使用指南.md)。
