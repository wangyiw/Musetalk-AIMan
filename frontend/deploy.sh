#!/bin/bash

server="main@192.168.10.101"
tmpDir="front-tmp"
deployDir="/usr/local/deploy/guidance/front"
websiteName="ai-guidance-front"

# 本地打包前端
echo "🔧 正在构建前端项目..."
npm run build

# 打包 dist 文件夹
echo "📦 打包 dist..."
tar zcf build.tar.gz dist

# 上传到远程服务器临时目录
echo "🚀 上传到服务器..."
ssh $server "mkdir -p ~/$tmpDir"
scp build.tar.gz ${server}:~/$tmpDir/

# 删除本地包
rm build.tar.gz

# 定义部署脚本
deployScript=$(cat << EOF
echo "🔧 正在部署到 $deployDir"

# 创建目标目录
sudo mkdir -p $deployDir

# 进入临时目录
cd ~/$tmpDir

# 解压
sudo tar zxf build.tar.gz -C $deployDir --strip-components=1

# 删除临时文件
rm build.tar.gz
cd ~
rm -rf $tmpDir

# 设置权限（可选）
sudo chown -R www-data:www-data $deployDir

# 重启 nginx（可选）
sudo /usr/sbin/nginx -s reload

echo "✅ 前端部署完成：$deployDir"
EOF
)

# 远程执行部署脚本
ssh $server "bash -s" << EOF
$deployScript
EOF