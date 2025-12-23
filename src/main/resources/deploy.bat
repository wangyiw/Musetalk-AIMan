@echo off
REM 部署 musetalk-java 服务脚本 (Windows 版)

REM 本地路径配置
set LOCAL_PROJECT_DIR=D:\paeleap\musetalk_java
set JAR_NAME=musetalkService-0.0.1-SNAPSHOT.jar
set LOCAL_JAR_PATH=%LOCAL_PROJECT_DIR%\target\%JAR_NAME%

REM 远程服务器配置
set REMOTE_USER=main
set REMOTE_HOST=192.168.10.101
set REMOTE_DIR=/home/main/wyw/java
set REMOTE_PATH=%REMOTE_DIR%/%JAR_NAME%
set REMOTE_SERVICE=musetalk-java.service

echo ====== Step 1: 打包项目 ======
cd /d %LOCAL_PROJECT_DIR%
call mvnw.cmd clean package -DskipTests

if not exist "%LOCAL_JAR_PATH%" (
  echo ❌ 打包失败，未找到 %LOCAL_JAR_PATH%
  exit /b 1
)
echo ✅ 打包完成: %LOCAL_JAR_PATH%

echo ====== Step 2: 上传 jar 到远程服务器 ======
scp "%LOCAL_JAR_PATH%" %REMOTE_USER%@%REMOTE_HOST%:%REMOTE_PATH%
if errorlevel 1 (
  echo ❌ 上传失败
  exit /b 1
)
echo ✅ 上传完成

echo ====== Step 3: 重启远程服务 ======
ssh %REMOTE_USER%@%REMOTE_HOST% "sudo systemctl restart %REMOTE_SERVICE% && sudo systemctl status %REMOTE_SERVICE% -n 5"
if errorlevel 1 (
  echo ❌ 服务重启失败
  exit /b 1
)
echo ✅ 服务已重启