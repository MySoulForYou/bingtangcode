package com.bingtangcode.command;

public enum CommandType {
    LOCAL,      // 纯本地只读计算与打印输出
    LOCAL_UI,   // 本地执行但需更改界面/系统状态
    PROMPT      // 生成提示词模板并注入对话，自动触发大模型 API 交互
}
