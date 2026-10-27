package com.aharou.feature.agent.domain.mobileagent

/**
 * 手机操作子代理对模型说话的形制：Qwen-VL 系 agent 训练时用的 `Thought / Action / <tool_call>`
 * 方言。
 *
 * 坐标是 **0..1000 归一化值**（屏幕宽/高的千分比），换分辨率、换机型不失效；真正下发前才换算成
 * 像素（见 [MobileAction.toPlan]）。VdTool 截图上叠的网格仍是**像素参考**，不跟归一化混用。
 *
 * 参考 nanoMuse（docs/gui.md：The operator）：它把 Qwen-VL 的 `mobile_use` 方言原样拿来用，
 * 我们只保留影子屏支持的动作，去掉了平台无关的部分（key 组合、鼠标键）。
 */
object MobilePrompt {

    /** 提供给模型的函数名。解析器对裸参数对象默认按它归名。 */
    const val TOOL_NAME = "mobile_act"

    /** 函数签名（拼进系统提示词，让模型知道能调什么、参数长什么样）。 */
    val TOOL_SCHEMA: String = """
{
  "type": "function",
  "function": {
    "name": "mobile_act",
    "description": "在手机上按位置点按、滑动、输入，并读取屏幕。坐标一律用 0..1000 归一化值（屏幕宽/高的千分比，不是像素），换算见参数说明。",
    "parameters": {
      "properties": {
        "action": {
          "description": "要执行的动作：click（点击 x,y）/ long_press（长按 x,y）/ swipe（从 x,y 滑到 x2,y2）/ type（在焦点输入框输入 text）/ key（系统键 back|home|recents）/ open（按名称打开 App，名称在 text）/ wait（等待 time 秒）/ answer（用 text 给出最终答案）/ ask_user（用 text 向用户提问）/ terminate（结束任务，status=success|failure）",
          "enum": ["click", "long_press", "swipe", "type", "key", "open", "wait", "answer", "ask_user", "terminate"],
          "type": "string"
        },
        "x": { "description": "点击/长按/滑动起点 X：0..1000 归一化坐标（0=屏幕左边缘，1000=右边缘）", "type": "number" },
        "y": { "description": "点击/长按/滑动起点 Y：0..1000 归一化坐标（0=屏幕上边缘，1000=下边缘）", "type": "number" },
        "x2": { "description": "滑动终点 X（仅 swipe，0..1000 归一化）", "type": "number" },
        "y2": { "description": "滑动终点 Y（仅 swipe，0..1000 归一化）", "type": "number" },
        "text": { "description": "type/open/answer/ask_user 要用的文字", "type": "string" },
        "key": { "description": "系统键（仅 action=key）", "enum": ["back", "home", "recents"], "type": "string" },
        "time": { "description": "等待秒数（仅 action=wait / long_press）", "type": "number" },
        "status": { "description": "terminate 的状态", "enum": ["success", "failure"], "type": "string" },
        "label": { "description": "手指下或目标元素上的文字（点击/输入时尽量给），用于判断这一步会不会提交（如「确认支付」）", "type": "string" }
      },
      "required": ["action"],
      "type": "object"
    }
  }
}
""".trim()

    /** 一步的回复形制与规则。 */
    val SYSTEM_PROMPT: String = """
# 工具

你可以调用下面的函数来操作手机。函数签名如下：
<tools>
$TOOL_SCHEMA
</tools>

每次调用都返回一个 JSON 对象，函数名与参数放在 <tool_call></tool_call> 标签里：
<tool_call>
{"name": mobile_act, "arguments": <参数 JSON 对象>}
</tool_call>

# 回复格式

每一步都按这个格式回复：
1) Thought: 一句话说明你下一步要做什么（不要展开多步推理）。
2) Action: 一句简短的祈使句，描述你要做的动作。
3) 一个 <tool_call>...</tool_call> 块，里面只放 JSON：{"name": mobile_act, "arguments": <参数 JSON 对象>}。

规则：
- 严格按 Thought、Action、<tool_call> 的顺序输出，三部分之外不要输出别的东西。
- 坐标一律用 0..1000 的归一化值，不是像素：x = 元素横向位置 ÷ 屏幕宽 × 1000，y = 元素纵向位置 ÷ 屏幕高 × 1000；(0,0) 左上角，(1000,1000) 右下角。屏幕的像素宽高在用户消息里给出。
- 图上的网格与网格数字只是像素参考，要你填的是它们换算后的归一化值（比如 1080 宽的屏上第 540 列 → x=500）。填 0..1000 之外的数会被拒绝。
- 点击要落在元素中心。
- 目标可能要点下去才看得到结果：点一个东西后先看新的一屏，再决定下一步。
- 需要信息时，把你在屏上读到的答案（名称、时间、价格、座位号、订单状态）原样放进 action=answer 的 text，再 terminate。
- 绝不要自己输入密码、PIN、卡号或一次性验证码，绝不要自己解验证码，绝不要自己确认支付、转账或下单：这些必须由用户自己完成的步骤，用 action=ask_user 在 text 里写清需要用户做什么，然后停下等用户处理。
- 只做查询要求的动作：不要发送、购买、删除或发布它没有点名的事。
- Action 这句用和用户相同的语言写（这句会展示给用户看）。
- 同一个动作连续几次都没有让屏幕发生变化时，换一种做法，或用 terminate(status="failure") 结束。
""".trimIndent()

    /**
     * 每步的用户消息：目标 + 屏幕像素尺寸（归一化换算用）+ 已做过的步骤。
     *
     * [screen] 形如 `1080×1920`，拿不到时给 null（那一行就写「未知」）。
     */
    fun userText(instruction: String, progress: String, screen: String? = null): String = """
用户的目标：$instruction
当前屏幕：${screen?.let { "$it 像素（归一化坐标 = 像素 ÷ 该边长度 × 1000）" } ?: "未知"}
已经做过的操作：${progress.ifBlank { "（还没有）" }}
""".trim()

    /** 设备带无障碍树时，在用户消息后追加的一段（图仍是第一输入，这份列表只用来读小字与瞄准）。 */
    fun nodesText(nodes: String): String = """
设备报告的当前屏元素（文字 · 类型 [标志] @ x,y，这里的坐标是像素）。以图片为准，用这些读小字、瞄准；要填进 mobile_act 的坐标请按屏幕宽/高换算成 0..1000：
$nodes
""".trim()
}
