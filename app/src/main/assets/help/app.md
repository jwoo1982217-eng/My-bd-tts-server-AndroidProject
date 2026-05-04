version-2

[![Q群](https://img.shields.io/badge/Q%E7%BE%A4-124841768-blue.svg)](https://jq.qq.com/?_wv=1027&k=y7WCDjEA) 
[![Issue](https://img.shields.io/badge/Github-Issue-greeb.svg)](https://github.com/jing332/tts-server-android/issues)
[![Dev](https://img.shields.io/github/actions/workflow/status/jing332/tts-server-android/test.yml?label=%E5%BC%80%E5%8F%91%E7%89%88)](https://github.com/jing332/tts-server-android/actions/workflows/test.yml)

# TTS Server 
本APP不提供语音合成服务，只是个网络TTS的搬运工，
通过插件驱动调用网络上的TTS接口。


##  1️⃣ 系统TTS
以下4个界面，在右上角更多选项中都有独立的导入、导出功能。您还可在设置中进行全部备份、恢复等操作。 

### 主界面
配置列表，用于管理TTS配置，您可使用分组功能进行一键切换多个配置。
- 可在设置中调换 `编辑`与`试听` 按钮的位置 ( <i>长按编辑按钮进行试听，反之，长按试听按钮进行编辑</i> )

### 朗读规则
用于处理朗读文本，根据用户配置的标签进行匹配TTS配置（如：旁白/对话）。
程序已内置 基于中文的双引号的 `旁白对话` 朗读规则，您可直接进行使用。

### 插件
用于扩展TTS功能，使用JS脚本进行调用互联网的上的TTS接口，如：内置的`Azure插件`。

### 替换规则
用于替换朗读文本进行纠正发音等操作，如：将“你好”替换为“您好”

高级示例：
- 将字数5以内的对话的双引号替换为【】，以达到旁白朗读的目的。
```
(启用正则表达式)
替换规则：(“)(.{1,5})(”)
替换为：【$2】
```

## 👨‍🏫 系统TTS 常见问题 
### 1. 锁屏后一段时间朗读突然停止？
> 在 `系统设置->应用->电池优化` 中将本APP与阅读APP加入电池优化白名单。
> 
> 对于本APP，您可在左侧滑菜单中单击 `电池优化白名单` 进行快捷设置。
> 
> PS: 对于国内系统，您可能还需对后台任务上锁，启用后台权限等操作。

### 2. 段落间隔时间长？
> 一般是由于网络延迟原因，因为 安卓系统TTS 服务的技术限制，导致无法预缓存音频，故每次只能同步获取。



## 2️⃣ TTS转发器
用于将安卓系统TTS转为HTTP网络接口形式，便于在网页调用。  
**配合阅读APP的网络TTS引擎调用，可变相实现预缓存一章的音频，提高段落间流畅度。**