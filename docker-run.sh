#!/bin/bash
# 在Docker中运行terminal-ws的脚本

echo "==== 终端WebSocket Docker部署脚本 ===="

# 确保已经构建了项目的jar包
if [ ! -f "target/terminal-ws-0.0.1-SNAPSHOT.jar" ]; then
  echo "正在构建项目..."
  mvn clean package -DskipTests
  
  if [ $? -ne 0 ]; then
    echo "构建失败，请修复编译错误后重试"
    exit 1
  fi
fi

# 停止并移除旧容器（如果存在）
if docker ps -a | grep -q terminal-ws; then
  echo "停止并移除旧的容器..."
  docker stop terminal-ws
  docker rm terminal-ws
fi

# 构建Docker镜像
echo "构建Docker镜像..."
docker build -t terminal-ws:latest -f- . <<EOF
FROM openjdk:11-jre-slim

# 安装nsenter工具和调试工具
RUN apt-get update && apt-get install -y \
    util-linux \
    procps \
    iproute2 \
    lsof \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY target/terminal-ws-0.0.1-SNAPSHOT.jar /app/

# 暴露Web端口
EXPOSE 8080

# 启动应用，添加调试日志
CMD ["java", "-Dlogging.level.com.terminal=DEBUG", "-jar", "terminal-ws-0.0.1-SNAPSHOT.jar"]
EOF

if [ $? -ne 0 ]; then
  echo "Docker镜像构建失败"
  exit 1
fi

# 运行Docker容器
echo "启动容器..."
docker run -d --name terminal-ws \
  --privileged \
  --pid=host \
  -p 8080:8080 \
  terminal-ws:latest

if [ $? -ne 0 ]; then
  echo "容器启动失败"
  exit 1
fi

echo "========================================"
echo "服务已启动，访问: http://localhost:8080"
echo "查看日志: docker logs -f terminal-ws"
echo "进入容器: docker exec -it terminal-ws bash"
echo "========================================" 