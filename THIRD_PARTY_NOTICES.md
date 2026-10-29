# Third-party notices

The Windows / Go edition includes dependencies listed in go.mod and go.sum. Source license texts are in docs/desktop/licenses/; the portable Windows release includes them in licenses/.

The standalone Android APK uses Android platform APIs and does not bundle the JDK, Android SDK, the optional JVM JSON test library, a browser engine, or advertising / analytics SDKs. Build tools are downloaded from their official publishers for local development only; test dependencies are not included in the APK.

- modernc.org/sqlite and modernc.org/libc: BSD-3-Clause; SQLite engine: public domain. https://gitlab.com/cznic/sqlite
- github.com/skip2/go-qrcode: MIT. https://github.com/skip2/go-qrcode
- github.com/dustin/go-humanize: MIT.
- github.com/google/uuid: BSD-3-Clause.
- github.com/mattn/go-isatty: MIT.
- github.com/ncruces/go-strftime: MIT.
- github.com/remyoudompheng/bigfft: BSD-3-Clause.
- golang.org/x/sys: BSD-3-Clause.
- modernc.org/mathutil and modernc.org/memory: BSD-3-Clause.
- Go standard library and runtime: BSD-3-Clause, with additional notices in the bundled Go LICENSE/PATENTS files.

Source adapter field layouts were informed by NewsNow (https://github.com/newsnext/newsnow), MIT © ourongxing. The application is independently implemented in Go; no login cookies, credentials, or bundled data from NewsNow are included.

## NewsNow MIT license

MIT License

Copyright (c) 2024 ourongxing

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
