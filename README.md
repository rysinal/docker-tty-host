# Terminal WebSocket

基于Java实现的WebSocket终端服务，可通过浏览器访问宿主机的Bash环境，类似SSH的功能。特别适合Docker容器内部署后访问宿主机环境，可以自动判断是否在容器内，非容器环境则直接打开主机的tty终端。

- 容器内打开容器外部宿主机tty（宿主机模式）
![image](https://github.com/user-attachments/assets/188642e1-08bc-40b8-bbc6-3b00cc047a99)

- 非容器打开主机tty（简单模式）
![image](https://github.com/user-attachments/assets/f95898f7-6963-4631-92aa-256226bc9f6f)


## 功能特点

- 基于WebSocket的实时通信
- 使用pty4j创建伪终端
- 支持从Docker容器内部访问宿主机Shell
- 使用nsenter命令进入宿主机命名空间
- 支持终端大小自适应调整
- 完全支持ANSI转义序列和颜色
- 基于xterm.js的浏览器终端模拟

## 技术栈

- 后端：Spring Boot + WebSocket + pty4j
- 前端：HTML + CSS + JavaScript + xterm.js
- 宿主机连接：nsenter

## 系统要求

- JDK 11+
- Maven 3.6+
- 支持现代浏览器
- Docker环境（用于容器内访问宿主机）
- nsenter命令（通常包含在util-linux软件包中）

## 宿主机连接原理

当应用程序在Docker容器内运行时，它会自动检测Docker环境并使用nsenter命令进入宿主机的命名空间：

```bash
/usr/bin/nsenter -t 1 -m -u -i -n -p /bin/bash -l
```

其中：
- `-t 1` 目标PID为1（宿主机的init进程）
- `-m` 进入挂载命名空间
- `-u` 进入UTS命名空间
- `-i` 进入IPC命名空间
- `-n` 进入网络命名空间
- `-p` 进入PID命名空间
- `/bin/bash -l` 在宿主机上运行登录shell

## 快速开始

### 构建项目

```bash
mvn clean package
```

### 运行项目

```bash
java -jar target/terminal-ws-0.0.1-SNAPSHOT.jar
```

### 在Docker中运行

```bash
docker run -d --name terminal-ws \
  --privileged \
  --pid=host \
  -p 8080:8080 \
  -v /path/to/terminal-ws.jar:/app/terminal-ws.jar \
  openjdk:11-jre \
  java -jar /app/terminal-ws.jar
```

> **注意**：需要使用`--privileged`参数，使容器具有运行nsenter的权限

### 访问Web终端

打开浏览器访问：http://localhost:8080

## 工作原理

1. Spring Boot启动WebSocket服务
2. 前端通过xterm.js创建终端界面
3. 用户访问网页时建立WebSocket连接
4. 后端检测是否在Docker环境中运行：
   - 如果是，使用nsenter连接到宿主机环境
   - 如果不是，使用本地bash shell
5. WebSocket双向传输数据：
   - 前端输入 -> WebSocket -> 后端 -> Bash进程
   - Bash输出 -> 后端 -> WebSocket -> 前端显示

## 特别说明

该项目主要用于演示目的，在生产环境使用时请注意添加适当的安全措施，如：

- 添加用户认证
- 添加SSL/TLS加密
- 限制终端访问权限
- 增加会话超时机制
- 限制评估容器权限
