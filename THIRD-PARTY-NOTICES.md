# 第三方軟體授權說明

本文件列出 SNMP Monitor 使用的所有第三方套件，以及各自的授權條款與版權宣告。

- **本專案授權：** Apache License 2.0（見 [`LICENSE`](LICENSE)），版權宣告見 [`NOTICE`](NOTICE)。
- **授權全文：** 見 [`licenses/`](licenses/) 目錄；Apache License 2.0 全文即根目錄的 `LICENSE`。
- **散布方式：** `mvn package` 產生的可執行 JAR（fat JAR）會把下列「執行期套件」整包打進去。打包時，本文件、`LICENSE`、`NOTICE` 與 `licenses/` 會一併放進 JAR 的 `META-INF/` 目錄。
- **盤點基準：** Java 套件以 `mvn dependency:list`（含遞移相依）、`pom.xml` 目前版本為準；網頁前端套件以 Vite 建置時實際打包的模組為準，版本見 `frontend/package-lock.json`。

---

## 一、執行期套件（隨 JAR 散布）

| 套件 | 版本 | 授權 | 本專案採用 |
|---|---|---|---|
| org.snmp4j:snmp4j | 3.7.7 | Apache-2.0 | Apache-2.0 |
| org.mindrot:jbcrypt | 0.4 | ISC | ISC |
| org.eclipse.jetty:jetty-server | 11.0.20 | EPL-2.0 或 Apache-2.0（雙授權） | Apache-2.0 |
| org.eclipse.jetty:jetty-http | 11.0.20 | EPL-2.0 或 Apache-2.0（雙授權） | Apache-2.0 |
| org.eclipse.jetty:jetty-io | 11.0.20 | EPL-2.0 或 Apache-2.0（雙授權） | Apache-2.0 |
| org.eclipse.jetty:jetty-util | 11.0.20 | EPL-2.0 或 Apache-2.0（雙授權）；內含 UnixCrypt（另有宣告） | Apache-2.0 |
| org.eclipse.jetty:jetty-servlet | 11.0.20 | EPL-2.0 或 Apache-2.0（雙授權） | Apache-2.0 |
| org.eclipse.jetty:jetty-servlets | 11.0.20 | EPL-2.0 或 Apache-2.0（雙授權） | Apache-2.0 |
| org.eclipse.jetty:jetty-security | 11.0.20 | EPL-2.0 或 Apache-2.0（雙授權） | Apache-2.0 |
| org.eclipse.jetty.toolchain:jetty-jakarta-servlet-api | 5.0.2 | EPL-2.0（另有 GPL-2.0 WITH Classpath-exception-2.0 次要授權） | EPL-2.0 |
| com.fasterxml.jackson.core:jackson-databind | 2.16.1 | Apache-2.0 | Apache-2.0 |
| com.fasterxml.jackson.core:jackson-core | 2.16.1 | Apache-2.0；內含 FastDoubleParser（MIT，及其衍生來源 MIT / BSL-1.0 / BSD-2-Clause） | Apache-2.0 |
| com.fasterxml.jackson.core:jackson-annotations | 2.16.1 | Apache-2.0 | Apache-2.0 |
| org.slf4j:slf4j-api | 2.0.9 | MIT | MIT |
| ch.qos.logback:logback-classic | 1.4.11 | EPL-1.0 或 LGPL-2.1（雙授權） | EPL-1.0 |
| ch.qos.logback:logback-core | 1.4.11 | EPL-1.0 或 LGPL-2.1（雙授權） | EPL-1.0 |
| org.xerial:sqlite-jdbc | 3.44.1.0 | Apache-2.0；部分程式碼 BSD（Zentus）；內含 SQLite 原生函式庫（公眾領域） | Apache-2.0 / BSD |

### 網頁前端套件（打包進 JAR 的 `static/assets/*.js`）

網頁介面（`frontend/`，Vue 3 + Naive UI）在建置時編譯並打包成 JS／CSS 放進 JAR，以下 npm 套件的程式碼因此隨 JAR 散布。除 `@juggle/resize-observer`（Apache-2.0，未附 NOTICE）外皆為 MIT。各套件的版權與授權全文彙整於 [`licenses/frontend-npm-packages.txt`](licenses/frontend-npm-packages.txt)。

| 套件 | 版本 | 授權 |
|---|---|---|
| @css-render/plugin-bem | 0.15.14 | MIT |
| @css-render/vue3-ssr | 0.15.14 | MIT |
| @emotion/hash | 0.8.0 | MIT |
| @intlify/core-base | 11.4.14 | MIT |
| @intlify/message-compiler | 11.4.14 | MIT |
| @intlify/shared | 11.4.14 | MIT |
| @juggle/resize-observer | 3.4.0 | Apache-2.0 |
| @vicons/ionicons5 | 0.13.0 | MIT |
| @vue/devtools-api | 6.6.4 | MIT |
| @vue/devtools-kit | 8.2.1 | MIT |
| @vue/devtools-shared | 8.2.1 | MIT |
| @vue/reactivity | 3.5.43 | MIT |
| @vue/runtime-core | 3.5.43 | MIT |
| @vue/runtime-dom | 3.5.43 | MIT |
| @vue/shared | 3.5.43 | MIT |
| async-validator | 4.2.5 | MIT |
| birpc | 2.9.0 | MIT |
| css-render | 0.15.14 | MIT |
| date-fns | 4.4.0 | MIT |
| date-fns-tz | 3.2.0 | MIT |
| evtd | 0.2.4 | MIT |
| hookable | 5.5.3 | MIT |
| lodash-es | 4.18.1 | MIT |
| naive-ui | 2.45.3 | MIT |
| nostics | 1.3.0 | MIT |
| perfect-debounce | 2.1.0 | MIT |
| seemly | 0.3.10 | MIT |
| treemate | 0.3.11 | MIT |
| vdirs | 0.1.8 | MIT |
| vooks | 0.2.12 | MIT |
| vue | 3.5.43 | MIT |
| vue-i18n | 11.4.14 | MIT |
| vue-router | 5.4.0 | MIT |
| vueuc | 0.4.66 | MIT |

### 雙授權套件的選擇說明

- **Jetty：** 選用 Apache-2.0，與本專案授權相同，不需額外義務。
- **Jakarta Servlet API：** 選用 EPL-2.0。JAR 內 `LICENSE.md` 另列出 Apache-2.0，但 `NOTICE.md` 宣告的是 EPL-2.0 / GPL-2.0+CPE，兩者不一致，因此採兩邊都有列出的 EPL-2.0。原樣散布，未修改。
- **Logback：** 選用 EPL-1.0，避免 LGPL-2.1。LGPL 要求使用者能替換、重新連結函式庫，但 fat JAR 是把 Logback 整包打進去，較難滿足這項要求。

---

## 二、版權宣告

### SNMP4J 3.7.7
```
Copyright (C) 2003-2023 Frank Fock (SNMP4J.org)
Licensed under the Apache License, Version 2.0
```
原始碼：https://snmp4j.org

### jBCrypt 0.4
```
Copyright (c) 2006 Damien Miller <djm@mindrot.org>
```
ISC 授權，全文見 [`licenses/ISC-jBCrypt.txt`](licenses/ISC-jBCrypt.txt)。ISC 要求所有副本都附上版權與許可聲明。

### Eclipse Jetty 11.0.20
```
All contributions are the property of the respective authors or of
entities to which copyright has been assigned by the authors (eg. employer).
Eclipse Jetty, and Jetty are trademarks of the Eclipse Foundation.
```
原始碼：https://github.com/jetty/jetty.project

jetty-util 內含 UnixCrypt，必須附上其版權宣告：
```
The UnixCrypt.java code implements the one way cryptography used by
Unix systems for simple password protection.  Copyright 1996 Aki Yoshida,
modified April 2001  by Iris Van den Broeke, Daniel Deville.
Permission to use, copy, modify and distribute UnixCrypt
for non-commercial or commercial purposes and without fee is
granted provided that the copyright notice appears in all copies.
```

### Jakarta Servlet API 5.0.2
```
All content is the property of the respective authors or their employers.
Eclipse Project for Servlet is a trademark of the Eclipse Foundation.
```
EPL-2.0 全文見 [`licenses/EPL-2.0.txt`](licenses/EPL-2.0.txt)。原始碼：https://github.com/eclipse-ee4j/servlet-api 、https://github.com/eclipse/jetty.toolchain

### Jackson 2.16.1
```
Copyright 2007-, Tatu Saloranta (tatu.saloranta@iki.fi)
```
NOTICE 全文已轉載於 [`NOTICE`](NOTICE)。

jackson-core 內含 FastDoubleParser：
```
Copyright © 2023 Werner Randelshofer, Switzerland. MIT License.
```
- MIT 全文：[`licenses/MIT-FastDoubleParser.txt`](licenses/MIT-FastDoubleParser.txt)
- 衍生來源（fast_double_parser，BSL-1.0；fast_float，MIT；bigint，BSD-2-Clause）的宣告與授權全文：[`licenses/FastDoubleParser-NOTICE.txt`](licenses/FastDoubleParser-NOTICE.txt)、[`licenses/FastDoubleParser-thirdparty-LICENSE.txt`](licenses/FastDoubleParser-thirdparty-LICENSE.txt)

> 備註：jackson-core JAR 內名為 `FastDoubleParser-LICENSE` 的檔案實際內容是 Apache-2.0 全文，與其 NOTICE 所述的 MIT 不一致。這是上游打包的問題，本專案另附 FastDoubleParser 原始專案的 MIT 授權全文。

### SLF4J 2.0.9
```
Copyright (c) 2004-2022 QOS.ch Sarl (Switzerland)
All rights reserved.
```
MIT 授權，全文見 [`licenses/MIT-SLF4J.txt`](licenses/MIT-SLF4J.txt)。

### Logback 1.4.11
```
Logback: the reliable, generic, fast and flexible logging framework.
Copyright (C) 1999-2015, QOS.ch. All rights reserved.
```
本專案依 EPL-1.0 使用，全文見 [`licenses/EPL-1.0.txt`](licenses/EPL-1.0.txt)。

以目的碼形式散布時，EPL-1.0 要求說明原始碼的取得方式：Logback 原始碼可從 https://github.com/qos-ch/logback（1.4.11 版的 tag）或 Maven Central 的 `logback-classic-1.4.11-sources.jar`、`logback-core-1.4.11-sources.jar` 取得。本專案未修改 Logback。

### sqlite-jdbc 3.44.1.0
```
Copyright (c) 2006, David Crawshaw.  All rights reserved.   （Zentus 原始程式碼，BSD 授權）
```
- 主要程式碼採 Apache-2.0。
- Zentus 部分的 BSD 授權全文：[`licenses/BSD-sqlite-jdbc-zentus.txt`](licenses/BSD-sqlite-jdbc-zentus.txt)
- 內含的 SQLite 原生函式庫屬公眾領域（https://www.sqlite.org/copyright.html），沒有授權義務。

原始碼：https://github.com/xerial/sqlite-jdbc

---

## 三、測試與建置用套件（不隨 JAR 散布）

以下套件只在開發、測試或建置時使用，不會打包進 JAR，因此沒有散布義務。列出來供參考。

| 套件 | 版本 | 授權 |
|---|---|---|
| org.junit.jupiter:junit-jupiter（含 api / params / engine） | 5.10.2 | EPL-2.0 |
| org.junit.platform:junit-platform-commons / engine | 1.10.2 | EPL-2.0 |
| org.opentest4j:opentest4j | 1.3.0 | Apache-2.0 |
| org.apiguardian:apiguardian-api | 1.1.2 | Apache-2.0 |
| org.mockito:mockito-core / mockito-junit-jupiter | 5.11.0 | MIT |
| net.bytebuddy:byte-buddy / byte-buddy-agent | 1.14.12 | Apache-2.0 |
| org.objenesis:objenesis | 3.3 | Apache-2.0 |
| Maven 外掛：surefire 3.2.5、shade 3.5.1 | — | Apache-2.0 |
| Maven 外掛：jacoco-maven-plugin 0.8.11 | — | EPL-2.0 |
| Maven 外掛：frontend-maven-plugin 2.0.2、maven-clean-plugin 3.4.0 | — | Apache-2.0 |
| Node.js（建置時由 Maven 下載） | 24.21.0 | MIT 等（見 Node.js 發行版 LICENSE） |
| 前端建置與測試工具：vite、@vitejs/plugin-vue、typescript、vue-tsc、vitest、@vue/test-utils、jsdom、@types/node | 見 `frontend/package-lock.json` | MIT／Apache-2.0（typescript） |

---

## 四、相容性檢查結論

| 項目 | 結果 |
|---|---|
| 是否有 GPL / AGPL 等強著作權延伸（strong copyleft）授權 | **無。** 唯一涉及 LGPL / GPL 的是雙授權選項（Logback、Servlet API），本專案都選擇非 GPL 的授權。 |
| EPL-1.0 / EPL-2.0（Logback、Servlet API） | 屬於檔案層級的弱著作權延伸。本專案原樣散布、未修改其原始碼，只需附上授權全文與原始碼取得方式（已在上方提供），可以和 Apache-2.0 專案一起散布。 |
| MIT / ISC / BSD / BSL-1.0 | 寬鬆授權，只需保留版權與許可聲明（已收錄於 `licenses/`）。 |
| Apache-2.0 第 4(d) 條 NOTICE 轉載 | Jackson、Jetty、Servlet API 的 NOTICE 已轉載於 `NOTICE`。 |
| SQLite | 公眾領域，沒有義務。 |
| 網頁前端（`frontend/`） | 第三方 npm 套件皆為 MIT 或 Apache-2.0，已列於第一節並附授權全文；執行時不引用任何外部 CDN 或網路資源。 |
| 加密軟體 | Jetty（UnixCrypt）與 jBCrypt 含雜湊演算法；SNMP4J 含 SNMPv3 所用的 DES / AES 加密。若產品要出口，請依所在地的出口管制法規自行確認。 |

---

## 五、更新本文件

新增或升級相依套件時：

1. 執行 `mvn dependency:list -DincludeScope=runtime` 確認完整清單（含遞移相依）；前端套件以 Vite 建置實際打包的模組確認（`frontend/node_modules/<套件>/package.json` 的 `license`）。
2. 查看新套件的 POM `<licenses>`，以及 JAR 內的 `META-INF/LICENSE*`、`NOTICE*`。
3. 更新本文件的表格與版權宣告；有 NOTICE 的 Apache-2.0 套件要轉載到 `NOTICE`；新的授權類型要把全文放進 `licenses/`；前端套件更新 `licenses/frontend-npm-packages.txt`。
