#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AiCode 套餐余量示例脚本 (demo_subscription.py) - 订阅制 Adaptive Card 模板

本脚本演示如何为 AiCode 供应商返回声明式 Adaptive Card 格式的余量数据。
支持收起态 (compact) 与展开态 (body) 的自定义排版。
"""

import json
import os
import sys

def main():
    # 模拟订阅制 5h / 7d / 1m 三周期余量
    data = {
        "type": "AdaptiveCard",
        "version": "1.5",
        "compact": {
            "type": "ColumnSet",
            "spacing": "Medium",
            "columns": [
                {
                    "type": "Column",
                    "width": "stretch",
                    "items": [
                        { "type": "TextBlock", "text": "5h 80%", "size": "Small", "weight": "Bolder", "color": "Good" },
                        { "type": "ProgressBar", "value": 80, "color": "Good", "height": 3 }
                    ]
                },
                {
                    "type": "Column",
                    "width": "stretch",
                    "items": [
                        { "type": "TextBlock", "text": "7d 65%", "size": "Small", "weight": "Bolder", "color": "Accent" },
                        { "type": "ProgressBar", "value": 65, "color": "Accent", "height": 3 }
                    ]
                },
                {
                    "type": "Column",
                    "width": "stretch",
                    "items": [
                        { "type": "TextBlock", "text": "1m 42%", "size": "Small", "weight": "Bolder", "color": "#8B5CF6" },
                        { "type": "ProgressBar", "value": 42, "color": "#8B5CF6", "height": 3 }
                    ]
                }
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
                            { "type": "TextBlock", "text": "5h 周期", "size": "Small", "isSubtle": True },
                            { "type": "TextBlock", "text": "80%", "size": "Medium", "weight": "Bolder", "color": "Good" },
                            { "type": "ProgressBar", "value": 80, "color": "Good" },
                            { "type": "TextBlock", "text": "4.0 / 5.0 小时", "size": "Small", "isSubtle": True }
                        ]
                    },
                    {
                        "type": "Column",
                        "width": "stretch",
                        "separator": True,
                        "items": [
                            { "type": "TextBlock", "text": "7d 周期", "size": "Small", "isSubtle": True },
                            { "type": "TextBlock", "text": "65%", "size": "Medium", "weight": "Bolder", "color": "Accent" },
                            { "type": "ProgressBar", "value": 65, "color": "Accent" },
                            { "type": "TextBlock", "text": "4.6 / 7.0 天", "size": "Small", "isSubtle": True }
                        ]
                    },
                    {
                        "type": "Column",
                        "width": "stretch",
                        "separator": True,
                        "items": [
                            { "type": "TextBlock", "text": "1m 周期", "size": "Small", "isSubtle": True },
                            { "type": "TextBlock", "text": "42%", "size": "Medium", "weight": "Bolder", "color": "#8B5CF6" },
                            { "type": "ProgressBar", "value": 42, "color": "#8B5CF6" },
                            { "type": "TextBlock", "text": "12.6 / 30 天", "size": "Small", "isSubtle": True }
                        ]
                    }
                ]
            }
        ]
    }

    print(json.dumps(data, ensure_ascii=False))

if __name__ == "__main__":
    main()
