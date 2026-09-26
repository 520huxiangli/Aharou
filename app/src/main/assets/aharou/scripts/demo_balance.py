#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AiCode 余额查询示例脚本 (demo_balance.py) - 余额制 Adaptive Card 模板

本脚本演示如何为 AiCode 供应商返回余额、消费统计与快捷控制台动作的 Adaptive Card 数据。
"""

import json
import os
import sys

def main():
    # 模拟从接口返回的余额与消费明细数据
    data = {
        "type": "AdaptiveCard",
        "version": "1.5",
        "compact": {
            "type": "Row",
            "spacing": "Medium",
            "items": [
                { "type": "StatusDot", "color": "Good" },
                { "type": "TextBlock", "text": "可用余额 $18.42", "weight": "Bolder" },
                { "type": "TextBlock", "text": "(今日 $0.45)", "size": "Small", "isSubtle": True }
            ]
        },
        "body": [
            {
                "type": "ColumnSet",
                "columns": [
                    {
                        "type": "Column",
                        "width": "stretch",
                        "items": [
                            { "type": "TextBlock", "text": "当前可用余额", "size": "Small", "isSubtle": True },
                            { "type": "TextBlock", "text": "$18.42", "size": "ExtraLarge", "weight": "Bolder", "color": "Good" },
                            { "type": "TextBlock", "text": "≈ ¥132.60 CNY", "size": "Small", "isSubtle": True }
                        ]
                    },
                    {
                        "type": "Column",
                        "width": "stretch",
                        "separator": True,
                        "items": [
                            { "type": "TextBlock", "text": "本月累计消费", "size": "Small", "isSubtle": True },
                            { "type": "TextBlock", "text": "$6.58", "size": "ExtraLarge", "weight": "Bolder", "color": "Accent" },
                            { "type": "TextBlock", "text": "今日消耗 $0.45", "size": "Small", "isSubtle": True }
                        ]
                    }
                ]
            },
            { "type": "Divider" },
            {
                "type": "FactSet",
                "facts": [
                    { "title": "当前计费模式", "value": "官方原价 (无倍率)" },
                    { "title": "Token 剩余", "value": "12,450,000" },
                    { "title": "速率限制", "value": "500 RPM" }
                ]
            }
        ],
        "actions": [
            {
                "type": "Action.OpenUrl",
                "title": "管理控制台",
                "url": "https://api.openai.com",
                "icon": "external-link"
            }
        ]
    }

    print(json.dumps(data, ensure_ascii=False))

if __name__ == "__main__":
    main()
