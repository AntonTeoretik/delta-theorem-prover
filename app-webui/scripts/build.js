const fs = require('fs');
const path = require('path');

const appRoot = path.resolve(__dirname, '..');
const distDir = path.join(appRoot, 'dist');
const srcDir = path.join(appRoot, 'src');
const cleanOnly = process.argv.includes('--clean');

function clean() {
  fs.rmSync(distDir, { recursive: true, force: true });
}

function copyFile(from, to) {
  fs.mkdirSync(path.dirname(to), { recursive: true });
  fs.copyFileSync(from, to);
}

function copyDirRecursive(fromDir, toDir) {
  fs.mkdirSync(toDir, { recursive: true });
  const entries = fs.readdirSync(fromDir, { withFileTypes: true });

  for (const entry of entries) {
    const sourcePath = path.join(fromDir, entry.name);
    const targetPath = path.join(toDir, entry.name);
    if (entry.isDirectory()) {
      copyDirRecursive(sourcePath, targetPath);
    } else {
      copyFile(sourcePath, targetPath);
    }
  }
}

clean();

if (!cleanOnly) {
  fs.mkdirSync(distDir, { recursive: true });
  copyFile(path.join(appRoot, 'index.html'), path.join(distDir, 'index.html'));
  copyDirRecursive(srcDir, distDir);
  console.log('Web UI built to app-webui/dist');
}
