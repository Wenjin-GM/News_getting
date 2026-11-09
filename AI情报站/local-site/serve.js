const http = require("http");
const fs = require("fs");
const path = require("path");

const root = __dirname;
const port = process.env.PORT || 8765;
const types = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".ico": "image/x-icon",
};

http
  .createServer((req, res) => {
    let p = decodeURIComponent(req.url.split("?")[0]);
    if (p === "/") p = "/index.html";
    const f = path.join(root, path.normalize(p));
    if (!f.startsWith(root)) {
      res.writeHead(403);
      return res.end("403");
    }
    fs.readFile(f, (err, buf) => {
      if (err) {
        res.writeHead(404);
        return res.end("404 Not Found");
      }
      res.writeHead(200, { "Content-Type": types[path.extname(f).toLowerCase()] || "application/octet-stream" });
      res.end(buf);
    });
  })
  .listen(port, "127.0.0.1", () => {
    console.log("AI 平台情报台（本地版）已启动:  http://127.0.0.1:" + port);
    console.log("按 Ctrl+C 停止。");
  });
