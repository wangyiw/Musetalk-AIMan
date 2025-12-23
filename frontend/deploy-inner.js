import { spawn } from 'child_process';
import os from 'os';

const isWindows = os.platform() === 'win32';
const command = isWindows ? 'powershell' : 'bash';
const args = isWindows ? ['./deploy.ps1'] : ['./deploy.sh']; // 部署内网

const child = spawn(command, args, { stdio: 'inherit' });

child.on('close', (code) => {
  console.log(`Child process exited with code ${code}`);
});
