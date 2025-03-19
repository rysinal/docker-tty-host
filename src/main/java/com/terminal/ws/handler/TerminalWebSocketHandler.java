package com.terminal.ws.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.terminal.ws.model.TerminalCommand;
import com.terminal.ws.service.TerminalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 终端WebSocket处理器
 * 专注于处理WebSocket消息和生命周期事件
 */
@Slf4j
public class TerminalWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Autowired
    private TerminalService terminalService;
    
    // 会话管理器，处理会话生命周期
    private final TerminalSessionManager sessionManager = new TerminalSessionManager();
    
    @Value("${terminal.ws.buffer.size:4096}")
    private int outputBufferSize;
    
    @Value("${terminal.ws.flush.interval:100}")
    private long outputFlushInterval;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("WebSocket连接已建立: {}", session.getId());
        
        // 创建并初始化会话
        sessionManager.createSession(session, new TerminalService());
        
        try {
            // 发送连接成功消息
            sendMessage(session, "TERMINAL_CONNECTED", Map.of(
                "data", "\r\n\u001b[32m已连接到服务器，等待终端初始化...\u001b[0m\r\n",
                "sessionId", session.getId()
            ));
        } catch (IOException e) {
            log.error("发送初始消息失败", e);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        TerminalSession terminalSession = sessionManager.getSession(session.getId());
        
        if (terminalSession == null || !terminalSession.isActive()) {
            log.warn("找不到会话或会话已非活动状态: {}", session.getId());
            sendErrorMessage(session, "终端会话不存在或已关闭");
            return;
        }
        
        try {
            String payload = message.getPayload();
            log.debug("收到WebSocket消息: {}", payload);
            
            TerminalCommand command = objectMapper.readValue(payload, TerminalCommand.class);
            log.debug("收到命令类型: {}", command.getType());
            
            // 处理不同类型的命令
            switch (command.getType()) {
                case "TERMINAL_INIT":
                    handleTerminalInit(terminalSession, command, session);
                    break;
                    
                case "TERMINAL_COMMAND":
                    handleTerminalCommand(terminalSession, command);
                    break;
                    
                case "TERMINAL_RESIZE":
                    handleTerminalResize(terminalSession, command);
                    break;
                    
                default:
                    log.warn("未知的命令类型: {}", command.getType());
                    sendErrorMessage(session, "未知的命令类型: " + command.getType());
            }
        } catch (Exception e) {
            log.error("处理WebSocket消息时发生错误", e);
            sendErrorMessage(session, "处理命令失败: " + e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("WebSocket连接已关闭: {}, 状态: {}", session.getId(), status);
        sessionManager.closeSession(session.getId());
    }
    
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("WebSocket传输错误: {}, 错误: {}", session.getId(), exception.getMessage(), exception);
        sessionManager.closeSession(session.getId());
    }
    
    /**
     * 应用关闭时的清理工作
     */
    @PreDestroy
    public void cleanup() {
        log.info("执行WebSocket处理器清理工作...");
        sessionManager.closeAllSessions();
        log.info("WebSocket处理器清理完成");
    }

    /**
     * 处理终端初始化命令
     */
    private void handleTerminalInit(TerminalSession terminalSession, TerminalCommand command, WebSocketSession session) throws IOException {
        try {
            log.info("初始化终端，大小: {}x{}", command.getCols(), command.getRows());
            
            // 启动终端会话
            terminalSession.startTerminal(command.getCols(), command.getRows(), outputBufferSize, outputFlushInterval);
            
            // 发送就绪消息
            sendMessage(session, "TERMINAL_READY", null);
        } catch (IOException e) {
            log.error("初始化终端失败", e);
            sendErrorMessage(session, "初始化终端失败: " + e.getMessage());
            throw e;
        }
    }

    /**
     * 处理终端命令
     */
    private void handleTerminalCommand(TerminalSession terminalSession, TerminalCommand command) throws IOException {
        try {
            if (!terminalSession.isTerminalAlive()) {
                log.warn("终端不再活跃，无法发送命令");
                sendErrorMessage(terminalSession.getWebSocketSession(), "终端会话已结束，请刷新页面重新连接");
                return;
            }
            
            terminalSession.sendCommand(command.getData());
        } catch (IOException e) {
            log.error("发送命令到终端失败", e);
            sendErrorMessage(terminalSession.getWebSocketSession(), "发送命令失败: " + e.getMessage());
            throw e;
        }
    }

    /**
     * 处理终端大小调整
     */
    private void handleTerminalResize(TerminalSession terminalSession, TerminalCommand command) {
        if (terminalSession.isTerminalAlive()) {
            terminalSession.resizeTerminal(command.getCols(), command.getRows());
        } else {
            log.warn("终端不再活跃，无法调整大小");
        }
    }

    /**
     * 发送错误消息给客户端
     */
    private void sendErrorMessage(WebSocketSession session, String errorMessage) {
        if (session != null && session.isOpen()) {
            try {
                // 发送错误消息
                sendMessage(session, "TERMINAL_ERROR", Map.of("error", errorMessage));
                
                // 同时发送纯文本格式的错误消息，以确保在终端中显示
                sendMessage(session, "TERMINAL_MESSAGE", Map.of(
                    "data", "\r\n\u001b[31m错误: " + errorMessage + "\u001b[0m\r\n",
                    "level", "error"
                ));
            } catch (IOException e) {
                log.error("发送错误消息失败: {}", e.getMessage());
            }
        }
    }
    
    /**
     * 发送WebSocket消息给客户端，确保所有消息都有类型字段
     */
    private void sendMessage(WebSocketSession session, String type, Map<String, Object> data) throws IOException {
        if (session != null && session.isOpen()) {
            Map<String, Object> message = new HashMap<>();
            message.put("type", type);
            
            if (data != null) {
                message.putAll(data);
            }
            
            String json = objectMapper.writeValueAsString(message);
            session.sendMessage(new TextMessage(json));
        }
    }
}

/**
 * 终端会话管理器
 * 管理所有活动的终端会话及其资源
 */
@Slf4j
@Component
class TerminalSessionManager {
    private final Map<String, TerminalSession> sessions = new HashMap<>();
    
    /**
     * 创建新会话
     */
    public synchronized void createSession(WebSocketSession webSocketSession, TerminalService terminalService) {
        String sessionId = webSocketSession.getId();
        TerminalSession session = new TerminalSession(sessionId, webSocketSession, terminalService);
        sessions.put(sessionId, session);
        log.debug("创建新会话: {}", sessionId);
    }
    
    /**
     * 获取会话
     */
    public TerminalSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }
    
    /**
     * 关闭会话
     */
    public synchronized void closeSession(String sessionId) {
        TerminalSession session = sessions.remove(sessionId);
        if (session != null) {
            session.close();
            log.debug("会话已关闭并移除: {}", sessionId);
        }
    }
    
    /**
     * 关闭所有会话
     */
    public synchronized void closeAllSessions() {
        for (TerminalSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
        log.debug("所有会话已关闭");
    }
}

/**
 * 终端会话
 * 封装一个WebSocket会话的所有相关资源
 */
@Slf4j
class TerminalSession {
    private final String sessionId;
    private final WebSocketSession webSocketSession;
    private final TerminalService terminalService;
    private boolean active = true;
    
    // 消息处理器
    private TerminalMessageProcessor messageProcessor;
    
    public TerminalSession(String sessionId, WebSocketSession webSocketSession, TerminalService terminalService) {
        this.sessionId = sessionId;
        this.webSocketSession = webSocketSession;
        this.terminalService = terminalService;
    }
    
    /**
     * 启动终端
     */
    public void startTerminal(int cols, int rows, int bufferSize, long flushInterval) throws IOException {
        terminalService.startTerminalSession(cols, rows);
        
        // 创建消息处理器
        messageProcessor = new TerminalMessageProcessor(
            sessionId, webSocketSession, terminalService, bufferSize, flushInterval
        );
        messageProcessor.start();
        
        log.info("终端会话已启动: {}", sessionId);
    }
    
    /**
     * 向终端发送命令
     */
    public void sendCommand(String command) throws IOException {
        terminalService.sendCommand(command);
    }
    
    /**
     * 调整终端大小
     */
    public void resizeTerminal(int cols, int rows) {
        terminalService.resizeTerminal(cols, rows);
    }
    
    /**
     * 检查终端是否活跃
     */
    public boolean isTerminalAlive() {
        return terminalService.isTerminalAlive();
    }
    
    /**
     * 检查会话是否活跃
     */
    public boolean isActive() {
        return active;
    }
    
    /**
     * 获取WebSocket会话
     */
    public WebSocketSession getWebSocketSession() {
        return webSocketSession;
    }
    
    /**
     * 关闭会话及其资源
     */
    public void close() {
        active = false;
        
        // 关闭消息处理器
        if (messageProcessor != null) {
            messageProcessor.stop();
        }
        
        // 关闭终端服务
        terminalService.closeTerminalSession();
        
        log.debug("会话资源已释放: {}", sessionId);
    }
}

/**
 * 终端消息处理器
 * 负责读取终端输出并发送到WebSocket客户端
 */
@Slf4j
class TerminalMessageProcessor {
    private final String sessionId;
    private final WebSocketSession webSocketSession;
    private final TerminalService terminalService;
    private final int bufferSize;
    private final long flushInterval;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private Thread readerThread;
    private Thread senderThread;
    private TerminalMessageBuffer messageBuffer;
    private volatile boolean running = false;
    
    public TerminalMessageProcessor(
        String sessionId, 
        WebSocketSession webSocketSession, 
        TerminalService terminalService,
        int bufferSize,
        long flushInterval
    ) {
        this.sessionId = sessionId;
        this.webSocketSession = webSocketSession;
        this.terminalService = terminalService;
        this.bufferSize = bufferSize;
        this.flushInterval = flushInterval;
        this.messageBuffer = new TerminalMessageBuffer(1000);
    }
    
    /**
     * 启动消息处理
     */
    public synchronized void start() {
        if (running) {
            return;
        }
        
        running = true;
        
        // 启动读取线程
        startReaderThread();
        
        // 启动发送线程
        startSenderThread();
        
        log.debug("消息处理器已启动: {}", sessionId);
    }
    
    /**
     * 停止消息处理
     */
    public synchronized void stop() {
        running = false;
        
        // 中断线程
        if (readerThread != null) {
            readerThread.interrupt();
        }
        
        if (senderThread != null) {
            senderThread.interrupt();
        }
        
        // 等待线程结束
        try {
            if (readerThread != null) {
                readerThread.join(1000);
            }
            
            if (senderThread != null) {
                senderThread.join(1000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("等待线程结束时被中断");
        }
        
        log.debug("消息处理器已停止: {}", sessionId);
    }
    
    /**
     * 启动读取线程
     */
    private void startReaderThread() {
        readerThread = new Thread(() -> {
            try {
                readTerminalOutput();
            } catch (Exception e) {
                log.error("读取终端输出线程异常: {}", e.getMessage(), e);
            }
        });
        
        readerThread.setDaemon(true);
        readerThread.setName("terminal-reader-" + sessionId);
        readerThread.start();
    }
    
    /**
     * 启动发送线程
     */
    private void startSenderThread() {
        senderThread = new Thread(() -> {
            try {
                sendTerminalOutput();
            } catch (Exception e) {
                log.error("发送终端输出线程异常: {}", e.getMessage(), e);
            }
        });
        
        senderThread.setDaemon(true);
        senderThread.setName("terminal-sender-" + sessionId);
        senderThread.start();
    }
    
    /**
     * 读取终端输出
     */
    private void readTerminalOutput() {
        try {
            // 等待终端进程启动
            waitForTerminalProcess();
            
            // 发送欢迎消息
            sendWelcomeMessage();
            
            // 读取终端输出
            byte[] buffer = new byte[bufferSize];
            int bytesRead;
            
            // 跟踪状态
            long startTime = System.currentTimeMillis();
            boolean receivedOutput = false;
            int noOutputCounter = 0;
            
            while (running && webSocketSession.isOpen() && terminalService.isTerminalAlive()) {
                // 检查输入流
                if (terminalService.getInputStream() == null) {
                    log.error("终端输入流为空");
                    break;
                }
                
                try {
                    // 读取数据
                    bytesRead = terminalService.getInputStream().read(buffer);
                    
                    if (bytesRead > 0) {
                        // 处理输出
                        String output = new String(buffer, 0, bytesRead);
                        log.debug("读取到终端输出: {} 字节", bytesRead);
                        
                        // 将输出封装为消息并添加到缓冲区
                        Map<String, Object> outputMap = new HashMap<>();
                        outputMap.put("type", "TERMINAL_OUTPUT");
                        outputMap.put("data", output);
                        
                        String outputJson = objectMapper.writeValueAsString(outputMap);
                        messageBuffer.add(outputJson);
                        
                        if (!receivedOutput) {
                            receivedOutput = true;
                            log.info("收到首次终端输出，耗时: {}ms", System.currentTimeMillis() - startTime);
                        }
                        
                        noOutputCounter = 0;
                    } else if (bytesRead == -1) {
                        log.warn("终端输出流结束");
                        break;
                    } else {
                        // 检查是否需要激活终端
                        handleNoOutput(noOutputCounter++, startTime, receivedOutput);
                        Thread.sleep(50);
                    }
                } catch (IOException e) {
                    log.error("读取终端输出时出错: {}", e.getMessage());
                    
                    // 发送错误消息
                    sendErrorMessageToBuffer("读取终端输出失败: " + e.getMessage());
                    break;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("终端输出读取线程被中断");
                    break;
                }
            }
            
            // 终端进程结束通知
            if (!terminalService.isTerminalAlive()) {
                sendTerminatedMessage();
            }
        } catch (Exception e) {
            log.error("终端输出读取线程异常", e);
            sendErrorMessageToBuffer("终端输出读取错误: " + e.getMessage());
        } finally {
            log.info("终端输出读取线程结束: {}", sessionId);
        }
    }
    
    /**
     * 等待终端进程启动
     */
    private void waitForTerminalProcess() throws InterruptedException {
        int waitCount = 0;
        while (terminalService.getProcess() == null && waitCount < 50 && running && webSocketSession.isOpen()) {
            Thread.sleep(100);
            waitCount++;
        }
        
        if (terminalService.getProcess() == null) {
            log.error("等待终端进程启动超时");
            sendErrorMessageToBuffer("等待终端进程启动超时");
            throw new InterruptedException("等待终端进程启动超时");
        }
    }
    
    /**
     * 发送欢迎消息
     */
    private void sendWelcomeMessage() {
        try {
            Map<String, Object> welcomeMap = new HashMap<>();
            welcomeMap.put("type", "TERMINAL_MESSAGE");
            welcomeMap.put("data", "\r\n\u001b[36m终端进程已启动，PID: " + terminalService.getProcess().pid() + "\u001b[0m\r\n");
            welcomeMap.put("level", "info");
            
            String welcomeJson = objectMapper.writeValueAsString(welcomeMap);
            messageBuffer.add(welcomeJson);
        } catch (Exception e) {
            log.error("发送欢迎消息失败", e);
        }
    }
    
    /**
     * 处理无输出情况
     */
    private void handleNoOutput(int counter, long startTime, boolean receivedOutput) {
        try {
            // 尝试定期发送回车激活终端
            if (counter > 100) {
                log.debug("尝试发送回车激活终端");
                terminalService.sendCommand("\n");
                return;
            }
            
            // 如果5秒后仍未收到输出，发送警告
            if (!receivedOutput && (System.currentTimeMillis() - startTime) > 5000) {
                log.warn("5秒内未收到任何终端输出，尝试激活终端");
                
                // 发送回车激活终端
                terminalService.sendCommand("\n");
                Thread.sleep(500);
                
                // 发送警告和提示
                Map<String, Object> warningMap = new HashMap<>();
                warningMap.put("type", "TERMINAL_MESSAGE");
                warningMap.put("data", "\r\n\u001b[33m警告: 未收到任何终端输出，已尝试激活终端...\u001b[0m\r\n");
                warningMap.put("level", "warning");
                
                Map<String, Object> tipMap = new HashMap<>();
                tipMap.put("type", "TERMINAL_MESSAGE");
                tipMap.put("data", "\r\n\u001b[33m提示: 请尝试按回车键或输入命令\u001b[0m\r\n");
                tipMap.put("level", "info");
                
                messageBuffer.add(objectMapper.writeValueAsString(warningMap));
                messageBuffer.add(objectMapper.writeValueAsString(tipMap));
            }
        } catch (Exception e) {
            log.error("尝试激活终端失败", e);
        }
    }
    
    /**
     * 发送终端结束消息
     */
    private void sendTerminatedMessage() {
        try {
            Map<String, Object> endMap = new HashMap<>();
            endMap.put("type", "TERMINAL_MESSAGE");
            endMap.put("data", "\r\n\u001b[31m终端会话已结束\u001b[0m\r\n");
            endMap.put("level", "error");
            endMap.put("event", "terminated");
            
            messageBuffer.add(objectMapper.writeValueAsString(endMap));
        } catch (Exception e) {
            log.error("发送终端结束消息失败", e);
        }
    }
    
    /**
     * 发送错误消息到缓冲区
     */
    private void sendErrorMessageToBuffer(String errorMessage) {
        try {
            Map<String, Object> errorMap = new HashMap<>();
            errorMap.put("type", "TERMINAL_MESSAGE");
            errorMap.put("data", "\r\n\u001b[31m错误: " + errorMessage + "\u001b[0m\r\n");
            errorMap.put("level", "error");
            
            messageBuffer.add(objectMapper.writeValueAsString(errorMap));
        } catch (Exception e) {
            log.error("创建错误消息失败", e);
        }
    }
    
    /**
     * 发送终端输出
     */
    private void sendTerminalOutput() {
        StringBuilder batchBuffer = new StringBuilder(bufferSize);
        long lastFlushTime = System.currentTimeMillis();
        
        try {
            while (running && webSocketSession.isOpen()) {
                try {
                    // 从缓冲区获取消息
                    String json = messageBuffer.poll(100);
                    
                    // 如果获取到消息，添加到批处理缓冲区
                    if (json != null) {
                        // 创建或追加到JSON数组
                        if (batchBuffer.length() == 0) {
                            batchBuffer.append("[");
                        } else {
                            batchBuffer.append(",");
                        }
                        batchBuffer.append(json);
                        
                        // 继续收集消息直到达到一定大小
                        while (batchBuffer.length() < bufferSize / 2) {
                            String nextJson = messageBuffer.poll(0);
                            if (nextJson == null) {
                                break;
                            }
                            batchBuffer.append(",").append(nextJson);
                        }
                    }
                    
                    // 检查是否需要发送
                    long now = System.currentTimeMillis();
                    boolean timeToFlush = (now - lastFlushTime) >= flushInterval;
                    boolean hasData = batchBuffer.length() > 0;
                    
                    if (hasData && (timeToFlush || batchBuffer.length() >= bufferSize / 2)) {
                        // 完成JSON数组
                        batchBuffer.append("]");
                        
                        // 创建批量消息
                        Map<String, Object> batchMap = new HashMap<>();
                        batchMap.put("type", "TERMINAL_BATCH");
                        batchMap.put("messages", objectMapper.readValue(batchBuffer.toString(), Object.class));
                        
                        // 发送批量消息
                        String batchJson = objectMapper.writeValueAsString(batchMap);
                        webSocketSession.sendMessage(new TextMessage(batchJson));
                        lastFlushTime = now;
                        
                        // 清空缓冲区
                        batchBuffer.setLength(0);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("终端输出发送线程被中断");
                    break;
                } catch (IOException e) {
                    if (e.getMessage() != null && e.getMessage().contains("TEXT_PARTIAL_WRITING")) {
                        log.warn("发送WebSocket消息时出错(会话可能已关闭): {}", e.getMessage());
                        break;
                    } else {
                        log.error("发送终端输出批量消息失败", e);
                        Thread.sleep(500);
                    }
                } catch (Exception e) {
                    log.error("处理终端输出发送时出错", e);
                    Thread.sleep(500);
                }
            }
            
            // 发送剩余的数据
            if (batchBuffer.length() > 0 && webSocketSession.isOpen()) {
                try {
                    batchBuffer.append("]");
                    
                    Map<String, Object> batchMap = new HashMap<>();
                    batchMap.put("type", "TERMINAL_BATCH");
                    batchMap.put("messages", objectMapper.readValue(batchBuffer.toString(), Object.class));
                    
                    webSocketSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(batchMap)));
                } catch (Exception e) {
                    log.error("发送最后的终端输出批量消息失败", e);
                }
            }
        } catch (Exception e) {
            log.error("终端输出发送线程异常", e);
        } finally {
            log.info("终端输出发送线程结束: {}", sessionId);
        }
    }
}

/**
 * 终端消息缓冲区
 * 用于在读取线程和发送线程之间传递消息
 */
class TerminalMessageBuffer {
    private final java.util.concurrent.BlockingQueue<String> queue;
    
    public TerminalMessageBuffer(int capacity) {
        this.queue = new java.util.concurrent.ArrayBlockingQueue<>(capacity);
    }
    
    /**
     * 添加消息到缓冲区
     */
    public void add(String message) {
        // 如果缓冲区满，则丢弃最旧的消息
        while (!queue.offer(message)) {
            queue.poll();
        }
    }
    
    /**
     * 从缓冲区获取消息
     */
    public String poll(long timeoutMs) throws InterruptedException {
        if (timeoutMs <= 0) {
            return queue.poll();
        } else {
            return queue.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }
} 