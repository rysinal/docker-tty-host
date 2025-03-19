package com.terminal.ws.model;

import lombok.Data;

/**
 * 终端命令消息
 */
@Data
public class TerminalCommand {
    
    /**
     * 操作类型
     */
    private String type;
    
    /**
     * 数据内容
     */
    private String data;
    
    /**
     * 终端列数
     */
    private int cols;
    
    /**
     * 终端行数
     */
    private int rows;
} 