#!/bin/bash

server="ubuntu@121.5.175.226"
dir="~/sites"
websiteName="ai-guidance-shaoxing-front"

echo "🛠️ 开始构建 $websiteName"

# 1. 构建项目
npm run build || { echo "❌ 构建失败"; exit 1; }

# 2. 打包（macOS 安全兼容）
COPYFILE_DISABLE=1 tar --exclude="._*" -zcf build.tar.gz dist

# 3. 上传
scp build.tar.gz ${server}:${dir} || { echo "❌ 上传失败"; exit 1; }

# 4. 远程部署
ssh $server "bash -s" <<EOF
set -e

echo "📂 切换到部署目录：$dir"
cd $dir

echo "📦 解压..."
rm -rf $websiteName
tar zxf build.tar.gz
rm build.tar.gz

echo "📁 重命名 dist -> $websiteName"
mv dist $websiteName

echo "🔄 重载 nginx"
sudo /usr/sbin/nginx -s reload

echo "✅ 部署成功：$websiteName"
EOF

# 5. 本地清理
rm build.tar.gz
