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

clean();

if (!cleanOnly) {
  fs.mkdirSync(distDir, { recursive: true });
  copyFile(path.join(appRoot, 'index.html'), path.join(distDir, 'index.html'));
  copyFile(path.join(srcDir, 'main.js'), path.join(distDir, 'main.js'));
  copyFile(path.join(srcDir, 'styles.css'), path.join(distDir, 'styles.css'));
  console.log('Web UI built to app-webui/dist');
}
