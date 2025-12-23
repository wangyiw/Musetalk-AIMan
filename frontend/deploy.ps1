
$server = "lighthouse@host.paeleap.com"
$dir = "sites"
$websiteName = "ai-guidance-front"

# build website

npm run build

# pack build folder

tar zcf build.tar.gz dist

# send to server

scp build.tar.gz ${server}:$dir

Remove-Item .\build.tar.gz

# define bash script

$deployScript = @"
echo deploying
echo switching to $dir
cd $dir
echo unzipping
sudo -u www-data tar zxf build.tar.gz --no-same-owner
echo remove build.tar.gz
sudo rm -r $websiteName build.tar.gz
echo rename dist to $websiteName
sudo mv dist $websiteName
echo reloading
sudo nginx -s reload
"@

# substitue all '\r\n' by '\n'

$deployScript = $deployScript -replace "`r`n", "`n"

# run script

ssh $server bash -c $deployScript

