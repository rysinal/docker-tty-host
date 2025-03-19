package com.terminal.ws.service;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 终端会话服务
 * 负责创建和管理终端进程，提供与终端交互的方法
 */
@Slf4j
@Service
public class TerminalService implements Closeable {

    // 配置参数，可从application.properties中读取
    @Value("${terminal.init.timeout:500}")
    private long initTimeoutMs;
    
    @Value("${terminal.use.simple.mode:false}")
    private boolean defaultUseSimpleMode;
    
    @Value("${terminal.nsenter.path:/usr/bin/nsenter}")
    private String nsenterPath;
    
    @Value("${terminal.shell.paths:/bin/bash,/bin/sh,/bin/zsh,/usr/bin/bash,/usr/bin/sh}")
    private String shellPathsStr;
    
    // 默认nsenter路径，防止@Value注入失败
    private static final String DEFAULT_NSENTER_PATH = "/usr/bin/nsenter";
    // 默认shell路径，防止@Value注入失败
    private static final String DEFAULT_SHELL_PATHS = "/bin/bash,/bin/sh,/bin/zsh,/usr/bin/bash,/usr/bin/sh";
    
    @Getter
    private PtyProcess process;
    
    @Getter
    private InputStream inputStream;
    
    @Getter
    private OutputStream outputStream;
    
    // 是否使用简单模式（仅使用本地shell而非nsenter）
    private boolean useSimpleMode;
    
    // 终端状态
    private enum TerminalState {
        NOT_STARTED,
        STARTING,
        RUNNING,
        FAILED,
        CLOSED
    }
    
    private volatile TerminalState state = TerminalState.NOT_STARTED;
    
    /**
     * 构造函数，初始化终端服务
     */
    public TerminalService() {
        // 在构造函数中设置默认值，防止@Value注入为null
        this.nsenterPath = DEFAULT_NSENTER_PATH;
        this.shellPathsStr = DEFAULT_SHELL_PATHS;
        this.initTimeoutMs = 500;
        this.useSimpleMode = false;
    }
    
    /**
     * Spring初始化完成后的处理
     * 确保所有@Value字段已正确注入
     */
    @PostConstruct
    public void init() {
        this.useSimpleMode = defaultUseSimpleMode;
        
        // 检查并设置默认值，防止配置注入问题
        if (nsenterPath == null || nsenterPath.trim().isEmpty()) {
            nsenterPath = DEFAULT_NSENTER_PATH;
            log.warn("nsenterPath未配置或为空，使用默认值: {}", nsenterPath);
        }
        
        if (shellPathsStr == null || shellPathsStr.trim().isEmpty()) {
            shellPathsStr = DEFAULT_SHELL_PATHS;
            log.warn("shellPaths未配置或为空，使用默认值: {}", shellPathsStr);
        }
        
        log.info("终端服务初始化完成: 简单模式={}, nsenter路径={}", useSimpleMode, nsenterPath);
    }
    
    /**
     * 判断当前是否运行在Docker容器中
     * 通过检查Docker环境文件和cgroup文件确定
     * 
     * @return 是否在Docker容器中运行
     */
    private boolean isRunningInDocker() {
        try {
            // 检查Docker环境文件
            if (new java.io.File("/.dockerenv").exists()) {
                log.debug("检测到/.dockerenv文件，确认运行在Docker环境中");
                return true;
            }
            
            // 检查cgroup文件
            java.io.File cgroupFile = new java.io.File("/proc/self/cgroup");
            if (cgroupFile.exists()) {
                try {
                    String content = new String(Files.readAllBytes(Paths.get("/proc/self/cgroup")));
                    boolean inDocker = content.contains("/docker/");
                    if (inDocker) {
                        log.debug("通过/proc/self/cgroup检测到Docker环境");
                    }
                    return inDocker;
                } catch (IOException e) {
                    log.warn("读取cgroup文件时出错", e);
                }
            }
            
            log.debug("未检测到Docker环境，将使用本地shell");
            return false;
        } catch (Exception e) {
            log.error("检测Docker环境时出错", e);
            return false;
        }
    }
    
    /**
     * 启动终端进程
     * 创建PTY进程并设置输入输出流
     * 
     * @param columns 列数
     * @param rows 行数
     * @throws IOException 如果启动进程失败
     */
    public synchronized void startTerminalSession(int columns, int rows) throws IOException {
        // 检查当前状态
        if (state == TerminalState.RUNNING || state == TerminalState.STARTING) {
            log.warn("终端会话已经在运行中或正在启动");
            return;
        }
        
        state = TerminalState.STARTING;
        
        try {
            // 判断是否在Docker环境中
            boolean inDocker = isRunningInDocker();
            log.info("环境检测: Docker环境={}", inDocker);
            
            // 获取命令解释器命令（优先使用宿主机命名空间）
            String[] command = getOsCommand();
            log.info("启动终端命令: {}", String.join(" ", command));
            
            // 设置环境变量
            Map<String, String> envs = new HashMap<>(System.getenv());
            envs.put("TERM", "xterm-256color");
            
            // 创建PTY进程
            this.process = new PtyProcessBuilder()
                    .setCommand(command)
                    .setEnvironment(envs)
                    .setInitialColumns(columns)
                    .setInitialRows(rows)
                    .setRedirectErrorStream(true)
                    .start();
            
            this.inputStream = process.getInputStream();
            this.outputStream = process.getOutputStream();
            
            // 检查进程是否成功启动
            if (this.process == null || !process.isAlive()) {
                state = TerminalState.FAILED;
                log.error("终端进程启动失败");
                throw new IOException("无法启动终端进程");
            }
            
            // 等待进程初始化
            boolean initSuccess = waitForProcessInit();
            
            if (!initSuccess) {
                state = TerminalState.FAILED;
                throw new IOException("终端进程启动后立即退出");
            }
            
            // 发送初始化命令
            sendInitCommand();
            
            state = TerminalState.RUNNING;
            log.info("终端会话已启动，PID: {}, 终端大小: {}x{}, 运行模式: {}", 
                    process.pid(), columns, rows, 
                    useSimpleMode ? "简单模式" : "宿主机模式");
            
        } catch (IOException e) {
            log.error("启动终端进程失败: {}", e.getMessage());
            
            // 如果失败且非简单模式，尝试使用简单模式重试
            if (!useSimpleMode) {
                log.info("尝试使用简单模式启动终端");
                useSimpleMode = true;
                startTerminalSession(columns, rows);
                return;
            }
            
            state = TerminalState.FAILED;
            closeResources();
            throw e;
        }
    }
    
    /**
     * 等待进程初始化完成
     * 
     * @return 初始化是否成功
     */
    private boolean waitForProcessInit() {
        try {
            // 等待进程初始化，通过轮询检查进程状态
            long startTime = System.currentTimeMillis();
            long timeout = initTimeoutMs;
            
            while (System.currentTimeMillis() - startTime < timeout) {
                if (!process.isAlive()) {
                    log.error("终端进程启动后立即退出，退出码: {}", process.exitValue());
                    return false;
                }
                
                // 短暂休眠避免CPU高占用
                TimeUnit.MILLISECONDS.sleep(50);
            }
            
            // 最后检查一次
            if (!process.isAlive()) {
                log.error("终端进程启动后退出，退出码: {}", process.exitValue());
                return false;
            }
            
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("等待终端进程初始化被中断", e);
            return false;
        }
    }
    
    /**
     * 发送初始化命令到终端
     * 设置提示符并输出初始信息
     */
    private void sendInitCommand() {
        try {
            // 发送初始命令以确保终端激活并产生输出
            String initCommand;
            if (useSimpleMode) {
                // 简单模式下的初始化命令 - 修复数字无法回显问题
                initCommand = "stty echo -icanon -icrnl -isig; export PS1='$ '; echo '终端已就绪（简单模式）'\n";
            } else {
                // 宿主机模式 - 修复数字无法回显问题
                initCommand = "stty echo -icanon -icrnl -isig; export PS1='\\h:\\w\\$ '; echo '终端已就绪（宿主机模式）'\n";
            }
            
            if (outputStream != null && process != null && process.isAlive()) {
                outputStream.write(initCommand.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
                log.debug("发送了初始化命令: {}", initCommand.replace("\n", "\\n"));
            }
        } catch (Exception e) {
            log.warn("发送初始化命令失败", e);
        }
    }
    
    /**
     * 调整终端大小
     * 
     * @param columns 列数
     * @param rows 行数
     * @return 是否调整成功
     */
    public boolean resizeTerminal(int columns, int rows) {
        if (process != null && process.isAlive() && state == TerminalState.RUNNING) {
            process.setWinSize(new WinSize(columns, rows));
            log.debug("终端大小已调整为 {}x{}", columns, rows);
            return true;
        } else {
            log.warn("无法调整终端大小，进程未启动或已退出");
            return false;
        }
    }
    
    /**
     * 向终端发送命令
     * 
     * @param command 命令
     * @throws IOException 如果发送命令失败
     */
    public void sendCommand(String command) throws IOException {
        if (outputStream != null && process != null && process.isAlive() && state == TerminalState.RUNNING) {
            try {
                // 简单命令不记录日志，减少日志量
                if (command.length() > 1) {
                    log.debug("发送命令到终端，长度: {}", command.length());
                }
                
                outputStream.write(command.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            } catch (IOException e) {
                log.error("发送命令到终端失败", e);
                throw e;
            }
        } else {
            log.warn("无法发送命令，终端进程未启动或已退出，当前状态: {}", state);
            throw new IOException("终端进程未启动或已退出");
        }
    }
    
    /**
     * 检查终端是否活跃
     * 
     * @return 终端是否活跃
     */
    public boolean isTerminalAlive() {
        return process != null && process.isAlive() && state == TerminalState.RUNNING;
    }
    
    /**
     * 关闭终端会话
     * 实现Closeable接口，支持try-with-resources
     */
    @Override
    public void close() {
        closeTerminalSession();
    }
    
    /**
     * 关闭终端会话
     * 清理所有资源
     */
    public synchronized void closeTerminalSession() {
        if (state == TerminalState.CLOSED) {
            return;
        }
        
        closeResources();
        state = TerminalState.CLOSED;
        log.info("终端会话已关闭");
    }
    
    /**
     * 关闭所有资源
     */
    private void closeResources() {
        // 关闭进程
        if (process != null && process.isAlive()) {
            process.destroy();
            
            // 尝试等待进程正常退出
            try {
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                    // 如果等待超时，强制终止
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // 强制终止
                process.destroyForcibly();
            }
        }
        
        // 关闭输入流
        if (inputStream != null) {
            try {
                inputStream.close();
            } catch (IOException e) {
                log.debug("关闭输入流时出错", e);
            }
            inputStream = null;
        }
        
        // 关闭输出流
        if (outputStream != null) {
            try {
                outputStream.close();
            } catch (IOException e) {
                log.debug("关闭输出流时出错", e);
            }
            outputStream = null;
        }
        
        process = null;
    }
    
    /**
     * 检查nsenter命令是否可用
     * 
     * @return nsenter命令是否可用
     */
    private boolean isNsenterAvailable() {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"which", "nsenter"});
            int exitCode = process.waitFor();
            boolean available = exitCode == 0;
            log.debug("nsenter命令{}可用", available ? "" : "不");
            return available;
        } catch (Exception e) {
            log.warn("检查nsenter命令时出错", e);
            return false;
        }
    }
    
    /**
     * 查找宿主机上可用的shell
     * 
     * @return 可用的shell路径
     */
    private String findHostShell() {
        // 尝试查找宿主机上的shell
        String[] possibleShells;
        try {
            possibleShells = shellPathsStr.split(",");
        } catch (Exception e) {
            log.warn("解析shell路径字符串出错，使用默认值", e);
            possibleShells = DEFAULT_SHELL_PATHS.split(",");
        }
        
        try {
            // 检查可用的shell
            for (String shell : possibleShells) {
                try {
                    if (shell == null || shell.trim().isEmpty()) {
                        continue;
                    }
                    
                    Process checkProcess = Runtime.getRuntime().exec(
                        new String[] {"ls", shell.trim()} // 直接检查文件是否存在
                    );
                    int exitCode = checkProcess.waitFor();
                    if (exitCode == 0) {
                        log.debug("找到可用shell: {}", shell);
                        return shell.trim();
                    }
                } catch (Exception e) {
                    log.trace("检查shell {}时出错: {}", shell, e.getMessage());
                }
            }
            log.warn("未找到可用的shell，将使用默认shell");
        } catch (Exception e) {
            log.error("查找shell时出错", e);
        }
        
        return "/bin/sh"; // 默认值
    }
    
    /**
     * 根据运行环境获取命令解释器
     * 
     * @return 命令解释器命令数组
     */
    private String[] getOsCommand() {
        String osName = System.getProperty("os.name").toLowerCase();
        log.debug("操作系统: {}", osName);
        
        // 如果已经设置为简单模式，直接使用本地shell
        if (useSimpleMode) {
            log.debug("使用简单模式 - 本地shell");
            return getLocalShellCommand();
        }
        
        // 如果在Docker容器内运行，使用nsenter访问宿主机
        if (isRunningInDocker() && isNsenterAvailable()) {
            try {
                // 防止nsenterPath为null
                String nsenterPathToUse = (nsenterPath != null && !nsenterPath.trim().isEmpty()) 
                    ? nsenterPath.trim() 
                    : DEFAULT_NSENTER_PATH;
                
                // 检查nsenter命令是否可用
                java.io.File nsenterFile = new java.io.File(nsenterPathToUse);
                if (nsenterFile.exists() && nsenterFile.canExecute()) {
                    // 使用nsenter进入宿主机命名空间
                    String hostShell = findHostShell();
                    String[] command = new String[]{
                        nsenterPathToUse, 
                        "-t", "1",     // 目标进程ID (宿主机init进程)
                        "-m",          // 进入挂载命名空间
                        "-u",          // 进入UTS命名空间
                        "-i",          // 进入IPC命名空间
                        "-n",          // 进入网络命名空间
                        "-p",          // 进入PID命名空间
                        hostShell      // 在宿主机上运行的命令
                    };
                    
                    log.info("使用nsenter连接宿主机: {}", String.join(" ", command));
                    return command;
                } else {
                    log.warn("nsenter命令不可用 (路径: {}), 将使用容器内shell", nsenterPathToUse);
                }
            } catch (Exception e) {
                log.error("检查nsenter命令时出错: {}", e.getMessage());
            }
        }
        
        // 如果不在Docker中或nsenter不可用，则使用普通shell
        return getLocalShellCommand();
    }
    
    /**
     * 获取本地shell命令
     * 
     * @return 本地shell命令数组
     */
    private String[] getLocalShellCommand() {
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win")) {
            log.debug("使用Windows命令解释器");
            return new String[]{"cmd.exe"};
        } else {
            // Linux、macOS等类Unix系统
            // 尝试检测常用shell
            String[] shells;
            try {
                shells = shellPathsStr.split(",");
            } catch (Exception e) {
                log.warn("解析shell路径字符串出错，使用默认值", e);
                shells = DEFAULT_SHELL_PATHS.split(",");
            }
            
            for (String shell : shells) {
                try {
                    shell = shell.trim();
                    if (shell.isEmpty()) {
                        continue;
                    }
                    
                    if (new java.io.File(shell).exists()) {
                        log.debug("使用本地shell: {}", shell);
                        return new String[]{shell, "-i"}; // 使用交互模式
                    }
                } catch (Exception e) {
                    log.debug("检查shell {} 时出错: {}", shell, e.getMessage());
                }
            }
            // 默认使用sh
            log.debug("使用默认shell: /bin/sh");
            return new String[]{"/bin/sh", "-i"};
        }
    }
    
    /**
     * 获取当前终端状态
     * 
     * @return 终端状态字符串
     */
    public String getTerminalStatus() {
        if (process == null) {
            return "未启动";
        }
        
        if (!process.isAlive()) {
            try {
                int exitCode = process.exitValue();
                return String.format("已退出，退出码: %d", exitCode);
            } catch (Exception e) {
                return "状态未知";
            }
        }
        
        return String.format("运行中，PID: %d", process.pid());
    }
} 