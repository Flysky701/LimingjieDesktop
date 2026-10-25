# 国服数据库真实下载与占用恢复

日期：2026-10-25，19:50–19:51（Asia/Shanghai）。对应 `ACCEPTANCE.md` L2-06 / FB-08。真实公开下载、生产校验、Windows占用暂存、退出进程后安装及旧库备份均通过。

## 范围与方式

只使用全新 `.tools/cndb-live-probe/fresh-test-data`，没有读取或修改用户data、旧项目、账号或凭据，也未操作模拟器。测试入口是临时Java程序，JDK21直接编译运行，classpath只读使用现有 `desktop/app/build/compose/binaries/main-release/app/LimingjieAssistant/app/*.jar`；没有运行Gradle、重建JAR或更改生产代码。

下载、校验、安装和暂存均调用现有 `DesktopLabyrinthCnDatabaseRepository`：默认 `OkHttpSource`、真实 `DesktopCandidateValidator` / `BundledSQLiteDriver` 和生产 `AtomicFiles.move`。OkHttp仅增加只读事件日志，保留正常网络行为。没有假HTTP响应、没有伪造移动异常；日志不记录GitHub重定向中的签名查询参数。

为构造可检查的旧文件，在这个全新目录内创建12,288字节的合成SQLite，仅含测试标记表；它用于文件占用和备份断言，不冒充旧版本国服数据库。对该目标打开真实 `BundledSQLiteDriver` 只读连接，开始读事务并读出标记，保持连接直到本轮更新返回。没有使用额外的Windows句柄或注入错误。

## 实际结果

- 共2个逻辑GET：公开manifest和数据库各1次，正常跟随GitHub的302重定向，最终均HTTP200；没有应用层重试或额外下载。
- 公开版本为 `202610141448`，数据库44,163,072字节，SHA-256为 `f4a81d5bd752be8605f8b6f2fe2655232632fd6d6a2d6478534fe8156df3c8b2`。生产流程已验证manifest校验和、最小字节数、SQLite文件头、只读quick_check及逻辑表完整性。
- 真实SQLite读事务使Windows无法替换目标。生产原子移动完成既定重试后，候选文件保存为 `master_cn.pending.db`，同时写入待安装元数据；返回“数据库更新已下载，将在下次启动时生效”。从请求到暂存完成56,036ms。
- 占用期间原测试数据库SHA-256不变，当前版本元数据仍不存在，没有把待安装版本误报为已安装。pending文件的SHA-256与manifest及pending元数据一致。随后关闭SQLite连接，下载进程退出（PID48812）。
- 在另一个新JVM（PID51604）重新构造同一路径的Repository，构造阶段在首次读取前安装pending，提示“已安装上次下载的国服数据库”。已安装字节数与校验和完全一致，pending数据库和元数据均被删除，`master_cn.previous.db` 与原测试库逐字节身份一致（SHA-256相同）。该阶段HTTP请求数为0。
- 对已安装文件的只读校验再次返回Valid：`UNIT_DATA / UNIT_SKILL_DATA / SKILL_DATA / SKILL_ACTION` 全部识别成功，无issues。其中unit锚点12/12，skill引用86/86，action引用266/266。

下载与恢复两个进程均exit0。这里验证的是**新JVM调用生产Repository构造路径来模拟应用重启**，没有额外重开Compose界面；它覆盖实际启动使用的待安装恢复逻辑，不声称完成了第二次GUI操作验证。

## 证据与保留数据

- `docs/desktop/evidence/l2/cndb-live-download.txt`：原始HTTP状态、真实占用方式、暂存结果、旧文件不变和待安装元数据。
- `docs/desktop/evidence/l2/cndb-live-restart.txt`：新进程安装通知、完整校验结果、旧库备份、pending清理和0网络请求。

临时探针 `CnDatabaseLiveProbe.java`、编译class及公开数据库只保留本机忽略目录 `.tools/cndb-live-probe/`，不提交、不随发行包分发。源地址沿用上游：manifest为 `https://github.com/wbero/autopcr-db-builder/releases/latest/download/manifest.json`，本次实际数据库为 `https://github.com/wbero/autopcr-db-builder/releases/download/db-v202610141448/202610141448.db`。
