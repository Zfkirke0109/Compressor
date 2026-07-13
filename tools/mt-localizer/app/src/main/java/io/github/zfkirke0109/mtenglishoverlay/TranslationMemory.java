package io.github.zfkirke0109.mtenglishoverlay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic translation memory. No network calls and no model inference.
 * Exact MT Manager phrases are preferred, then longer technical terms are replaced first.
 */
final class TranslationMemory {
    private static final Map<String, String> EXACT = new LinkedHashMap<>();
    private static final Map<String, String> TERMS = new LinkedHashMap<>();
    private static final List<Map.Entry<String, String>> SORTED_TERMS;

    private static final Pattern AGENT_ROUND = Pattern.compile(
            "\\[Agent\\s*第(\\d+)轮\\]\\s*MCP工具已调用[，,]?\\s*获取结果中[.….]*");
    private static final Pattern CJK_OR_CYRILLIC = Pattern.compile(
            "[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}\\p{IsCyrillic}]");

    static {
        // MT Manager / plugin store / AI Chat phrases.
        exact("插件商店", "Plugin Store");
        exact("插件市场", "Plugin Marketplace");
        exact("插件详情", "Plugin Details");
        exact("安装插件", "Install Plugin");
        exact("卸载插件", "Uninstall Plugin");
        exact("更新插件", "Update Plugin");
        exact("已安装", "Installed");
        exact("未安装", "Not installed");
        exact("立即安装", "Install now");
        exact("重新安装", "Reinstall");
        exact("检查更新", "Check for updates");
        exact("暂无更新", "No updates available");
        exact("下载次数", "Downloads");
        exact("更新时间", "Updated");
        exact("版本信息", "Version information");
        exact("作者信息", "Author information");
        exact("插件描述", "Plugin description");
        exact("查看详情", "View details");
        exact("展开全部", "Show all");
        exact("收起", "Collapse");
        exact("欢迎使用AI聊天！", "Welcome to AI Chat!");
        exact("您可以在这里与AI直接对话。", "You can have direct conversations with AI here.");
        exact("如果配置了MCP服务，AI会自动使用MCP工具增强回答。", "If an MCP service is configured, AI will automatically use MCP tools to enhance responses.");
        exact("MCP工具已调用，获取结果中...", "MCP tool called; retrieving results...");
        exact("AI API错误", "AI API error");
        exact("工具调用失败", "Tool call failed");
        exact("最大工具调用次数已达到", "Maximum tool-call count reached");
        exact("继续", "Continue");
        exact("发送", "Send");
        exact("停止", "Stop");
        exact("清空会话", "Clear conversation");
        exact("复制代码", "Copy code");
        exact("系统提示词", "System prompt");
        exact("选择模型", "Select model");
        exact("服务已连接", "Service connected");
        exact("服务未连接", "Service disconnected");
        exact("MCP服务", "MCP service");
        exact("启动MCP服务", "Start MCP service");
        exact("停止MCP服务", "Stop MCP service");
        exact("连接超时", "Connection timed out");
        exact("请求超时", "Request timed out");
        exact("请稍后重试", "Please try again later");

        // File manager and editor phrases.
        exact("新建文件", "New file");
        exact("新建文件夹", "New folder");
        exact("选择文件", "Select file");
        exact("选择文件夹", "Select folder");
        exact("选择目录", "Select directory");
        exact("文件属性", "File properties");
        exact("复制路径", "Copy path");
        exact("打开方式", "Open with");
        exact("上级目录", "Parent directory");
        exact("内部存储", "Internal storage");
        exact("外部存储", "External storage");
        exact("根目录", "Root directory");
        exact("添加到收藏", "Add to favorites");
        exact("从收藏移除", "Remove from favorites");
        exact("全选", "Select all");
        exact("反选", "Invert selection");
        exact("取消选择", "Clear selection");
        exact("批量重命名", "Batch rename");
        exact("解压到当前目录", "Extract to current directory");
        exact("添加到压缩包", "Add to archive");
        exact("压缩级别", "Compression level");
        exact("替换文件", "Replace file");
        exact("文件已存在", "File already exists");
        exact("是否覆盖？", "Overwrite?");
        exact("操作成功", "Operation completed successfully");
        exact("操作失败", "Operation failed");
        exact("正在处理", "Processing");
        exact("处理完成", "Processing complete");
        exact("正在加载", "Loading");
        exact("加载更多", "Load more");
        exact("没有更多了", "No more items");

        // APK / DEX / resources.
        exact("APK查看器", "APK Viewer");
        exact("APK编辑", "APK Editor");
        exact("Dex编辑器++", "Dex Editor++");
        exact("字符串搜索", "String search");
        exact("方法搜索", "Method search");
        exact("类搜索", "Class search");
        exact("引用搜索", "Reference search");
        exact("查找引用", "Find references");
        exact("交叉引用", "Cross references");
        exact("反编译", "Decompile");
        exact("回编译", "Recompile");
        exact("资源编辑器", "Resource editor");
        exact("清单文件", "Manifest file");
        exact("权限声明", "Permission declarations");
        exact("导出组件", "Exported components");
        exact("签名校验", "Signature verification");
        exact("APK签名", "APK signing");
        exact("证书指纹", "Certificate fingerprint");
        exact("资源混淆", "Resource obfuscation");
        exact("区分大小写", "Case sensitive");
        exact("正则表达式", "Regular expression");
        exact("搜索范围", "Search scope");
        exact("当前文件", "Current file");
        exact("所有文件", "All files");
        exact("跳转到行", "Go to line");
        exact("调用者", "Callers");
        exact("被调用者", "Callees");
        exact("应用名称", "App name");
        exact("包名", "Package name");
        exact("版本名称", "Version name");
        exact("版本号", "Version code");
        exact("最低版本", "Minimum SDK");
        exact("目标版本", "Target SDK");

        // Permissions and common dialogs.
        exact("需要权限", "Permission required");
        exact("授予权限", "Grant permission");
        exact("允许", "Allow");
        exact("拒绝", "Deny");
        exact("永久允许", "Always allow");
        exact("仅本次", "Only this time");
        exact("确定", "OK");
        exact("取消", "Cancel");
        exact("保存", "Save");
        exact("关闭", "Close");
        exact("打开", "Open");
        exact("删除", "Delete");
        exact("复制", "Copy");
        exact("移动", "Move");
        exact("重命名", "Rename");
        exact("编辑", "Edit");
        exact("查看", "View");
        exact("搜索", "Search");
        exact("替换", "Replace");
        exact("刷新", "Refresh");
        exact("分享", "Share");
        exact("导入", "Import");
        exact("导出", "Export");
        exact("上传", "Upload");
        exact("下载", "Download");
        exact("同步", "Sync");
        exact("设置", "Settings");
        exact("语言", "Language");
        exact("主题", "Theme");
        exact("深色模式", "Dark mode");
        exact("浅色模式", "Light mode");
        exact("自动", "Automatic");
        exact("关于", "About");
        exact("帮助", "Help");
        exact("反馈", "Feedback");
        exact("日志", "Log");
        exact("调试", "Debug");
        exact("错误", "Error");
        exact("警告", "Warning");
        exact("信息", "Information");
        exact("成功", "Success");
        exact("失败", "Failed");
        exact("完成", "Complete");
        exact("未知", "Unknown");
        exact("不支持", "Not supported");

        // Longer terms first. Both Simplified and Traditional Chinese are included.
        term("AndroidManifest清单文件", "AndroidManifest manifest file");
        term("AndroidManifest.xml清单文件", "AndroidManifest.xml manifest file");
        term("应用程序接口", "application programming interface");
        term("无障碍服务", "accessibility service");
        term("辅助功能服务", "accessibility service");
        term("插件商店", "Plugin Store");
        term("插件市场", "Plugin Marketplace");
        term("插件詳情", "Plugin Details");
        term("插件详情", "Plugin Details");
        term("安装插件", "install plugin");
        term("安裝插件", "install plugin");
        term("卸载插件", "uninstall plugin");
        term("解除安裝插件", "uninstall plugin");
        term("更新插件", "update plugin");
        term("插件描述", "plugin description");
        term("插件說明", "plugin description");
        term("下载次数", "download count");
        term("下載次數", "download count");
        term("更新时间", "update time");
        term("更新時間", "update time");
        term("版本信息", "version information");
        term("版本資訊", "version information");
        term("作者信息", "author information");
        term("作者資訊", "author information");
        term("文件管理器", "file manager");
        term("檔案管理器", "file manager");
        term("文件夹", "folder");
        term("資料夾", "folder");
        term("上级目录", "parent directory");
        term("上級目錄", "parent directory");
        term("当前目录", "current directory");
        term("當前目錄", "current directory");
        term("内部存储", "internal storage");
        term("內部儲存", "internal storage");
        term("外部存储", "external storage");
        term("外部儲存", "external storage");
        term("文件属性", "file properties");
        term("檔案屬性", "file properties");
        term("文件大小", "file size");
        term("檔案大小", "file size");
        term("修改时间", "modified time");
        term("修改時間", "modified time");
        term("创建时间", "created time");
        term("建立時間", "created time");
        term("所有者", "owner");
        term("用户组", "user group");
        term("使用者群組", "user group");
        term("读取权限", "read permission");
        term("讀取權限", "read permission");
        term("写入权限", "write permission");
        term("寫入權限", "write permission");
        term("执行权限", "execute permission");
        term("執行權限", "execute permission");
        term("操作失败", "operation failed");
        term("操作失敗", "operation failed");
        term("操作成功", "operation succeeded");
        term("网络错误", "network error");
        term("網路錯誤", "network error");
        term("连接超时", "connection timed out");
        term("連線逾時", "connection timed out");
        term("请求超时", "request timed out");
        term("請求逾時", "request timed out");
        term("请稍后重试", "please try again later");
        term("請稍後再試", "please try again later");
        term("正在加载", "loading");
        term("正在載入", "loading");
        term("正在处理", "processing");
        term("正在處理", "processing");
        term("加载更多", "load more");
        term("載入更多", "load more");
        term("没有更多", "no more");
        term("沒有更多", "no more");
        term("服务未启动", "service is not running");
        term("服務未啟動", "service is not running");
        term("服务已启动", "service is running");
        term("服務已啟動", "service is running");
        term("已连接", "connected");
        term("已連線", "connected");
        term("已断开", "disconnected");
        term("已中斷連線", "disconnected");
        term("重新连接", "reconnect");
        term("重新連線", "reconnect");
        term("启动服务", "start service");
        term("啟動服務", "start service");
        term("停止服务", "stop service");
        term("停止服務", "stop service");
        term("本地服务", "local service");
        term("本機服務", "local service");
        term("远程服务", "remote service");
        term("遠端服務", "remote service");
        term("服务器地址", "server address");
        term("伺服器位址", "server address");
        term("端口号", "port number");
        term("連接埠號", "port number");
        term("AI聊天", "AI Chat");
        term("AI對話", "AI Chat");
        term("工具调用", "tool call");
        term("工具呼叫", "tool call");
        term("获取结果", "retrieve results");
        term("取得結果", "retrieve results");
        term("系统提示词", "system prompt");
        term("系統提示詞", "system prompt");
        term("最大轮数", "maximum rounds");
        term("最大輪數", "maximum rounds");
        term("模型设置", "model settings");
        term("模型設定", "model settings");
        term("APK查看器", "APK Viewer");
        term("APK檢視器", "APK Viewer");
        term("Dex编辑器", "Dex Editor");
        term("Dex編輯器", "Dex Editor");
        term("字符串搜索", "string search");
        term("字串搜尋", "string search");
        term("方法搜索", "method search");
        term("方法搜尋", "method search");
        term("类搜索", "class search");
        term("類別搜尋", "class search");
        term("引用搜索", "reference search");
        term("參照搜尋", "reference search");
        term("查找引用", "find references");
        term("尋找參照", "find references");
        term("交叉引用", "cross references");
        term("反编译", "decompile");
        term("反編譯", "decompile");
        term("回编译", "recompile");
        term("重新編譯", "recompile");
        term("资源编辑器", "resource editor");
        term("資源編輯器", "resource editor");
        term("资源文件", "resource file");
        term("資源檔案", "resource file");
        term("清单文件", "manifest file");
        term("資訊清單檔", "manifest file");
        term("权限声明", "permission declaration");
        term("權限宣告", "permission declaration");
        term("导出组件", "exported component");
        term("匯出元件", "exported component");
        term("广播接收器", "broadcast receiver");
        term("廣播接收器", "broadcast receiver");
        term("内容提供器", "content provider");
        term("內容提供者", "content provider");
        term("签名校验", "signature verification");
        term("簽章驗證", "signature verification");
        term("证书指纹", "certificate fingerprint");
        term("憑證指紋", "certificate fingerprint");
        term("资源混淆", "resource obfuscation");
        term("資源混淆", "resource obfuscation");
        term("正则表达式", "regular expression");
        term("規則運算式", "regular expression");
        term("区分大小写", "case sensitive");
        term("區分大小寫", "case sensitive");
        term("搜索范围", "search scope");
        term("搜尋範圍", "search scope");
        term("当前文件", "current file");
        term("目前檔案", "current file");
        term("所有文件", "all files");
        term("所有檔案", "all files");
        term("应用名称", "app name");
        term("應用程式名稱", "app name");
        term("包名", "package name");
        term("套件名稱", "package name");
        term("版本名称", "version name");
        term("版本名稱", "version name");
        term("版本号", "version code");
        term("版本號", "version code");
        term("最低版本", "minimum SDK");
        term("目標版本", "target SDK");
        term("目标版本", "target SDK");
        term("需要权限", "permission required");
        term("需要權限", "permission required");
        term("授予权限", "grant permission");
        term("授予權限", "grant permission");
        term("永久允许", "always allow");
        term("永久允許", "always allow");
        term("仅本次", "only this time");
        term("僅限這次", "only this time");

        // Generic Simplified/Traditional Chinese terms.
        term("文件", "file"); term("檔案", "file");
        term("目录", "directory"); term("目錄", "directory");
        term("路径", "path"); term("路徑", "path");
        term("插件", "plugin"); term("外掛", "plugin");
        term("脚本", "script"); term("指令碼", "script");
        term("资源", "resource"); term("資源", "resource");
        term("字符串", "string"); term("字串", "string");
        term("方法", "method");
        term("字段", "field"); term("欄位", "field");
        term("类", "class"); term("類別", "class");
        term("接口", "interface"); term("介面", "interface");
        term("父类", "superclass"); term("父類別", "superclass");
        term("活动", "activity"); term("活動", "activity");
        term("服务", "service"); term("服務", "service");
        term("权限", "permission"); term("權限", "permission");
        term("证书", "certificate"); term("憑證", "certificate");
        term("签名", "signature"); term("簽章", "signature");
        term("哈希", "hash"); term("雜湊", "hash");
        term("编码", "encode"); term("編碼", "encode");
        term("解码", "decode"); term("解碼", "decode");
        term("压缩", "compress"); term("壓縮", "compress");
        term("解压", "extract"); term("解壓縮", "extract");
        term("复制", "copy"); term("複製", "copy");
        term("移动", "move"); term("移動", "move");
        term("删除", "delete"); term("刪除", "delete");
        term("重命名", "rename"); term("重新命名", "rename");
        term("新建", "new"); term("新增", "new");
        term("选择", "select"); term("選擇", "select");
        term("保存", "save"); term("儲存", "save");
        term("取消", "cancel");
        term("确定", "OK"); term("確定", "OK");
        term("打开", "open"); term("開啟", "open");
        term("关闭", "close"); term("關閉", "close");
        term("编辑", "edit"); term("編輯", "edit");
        term("查看", "view"); term("檢視", "view");
        term("搜索", "search"); term("搜尋", "search");
        term("替换", "replace"); term("取代", "replace");
        term("分享", "share");
        term("导入", "import"); term("匯入", "import");
        term("导出", "export"); term("匯出", "export");
        term("上传", "upload"); term("上傳", "upload");
        term("下载", "download"); term("下載", "download");
        term("更新", "update");
        term("版本", "version");
        term("作者", "author");
        term("描述", "description"); term("說明", "description");
        term("评分", "rating"); term("評分", "rating");
        term("兼容", "compatible"); term("相容", "compatible");
        term("支持", "support"); term("支援", "support");
        term("需要", "requires");
        term("成功", "success");
        term("失败", "failed"); term("失敗", "failed");
        term("错误", "error"); term("錯誤", "error");
        term("警告", "warning");
        term("信息", "information"); term("資訊", "information");
        term("日志", "log"); term("記錄", "log");
        term("调试", "debug"); term("偵錯", "debug");
        term("设置", "settings"); term("設定", "settings");
        term("语言", "language"); term("語言", "language");
        term("主题", "theme"); term("主題", "theme");
        term("关于", "about"); term("關於", "about");
        term("帮助", "help"); term("說明", "help");
        term("反馈", "feedback"); term("意見反應", "feedback");
        term("本地", "local"); term("本機", "local");
        term("远程", "remote"); term("遠端", "remote");
        term("网络", "network"); term("網路", "network");
        term("服务器", "server"); term("伺服器", "server");
        term("客户端", "client"); term("用戶端", "client");
        term("端口", "port"); term("連接埠", "port");
        term("地址", "address"); term("位址", "address");
        term("连接", "connect"); term("連線", "connect");
        term("断开", "disconnect"); term("中斷連線", "disconnect");
        term("刷新", "refresh"); term("重新整理", "refresh");
        term("同步", "sync");
        term("等待", "waiting");
        term("处理中", "processing"); term("處理中", "processing");
        term("完成", "complete");
        term("未知", "unknown");
        term("不支持", "not supported"); term("不支援", "not supported");
        term("允许", "allow"); term("允許", "allow");
        term("拒绝", "deny"); term("拒絕", "deny");
        term("试用", "trial"); term("試用", "trial");
        term("许可证", "license"); term("授權", "license");
        term("购买", "purchase"); term("購買", "purchase");
        term("恢复", "restore"); term("還原", "restore");
        term("会员", "membership"); term("會員", "membership");
        term("设备", "device"); term("裝置", "device");
        term("结果", "result"); term("結果", "result");
        term("继续", "continue"); term("繼續", "continue");
        term("停止", "stop");
        term("发送", "send"); term("傳送", "send");
        term("聊天", "chat"); term("對話", "chat");
        term("模型", "model");
        term("工具", "tool");
        term("调用", "call"); term("呼叫", "call");
        term("获取", "retrieve"); term("取得", "retrieve");
        term("结果中", "retrieving results"); term("取得結果中", "retrieving results");

        // Japanese common UI and technical terms.
        term("プラグインストア", "Plugin Store");
        term("プラグイン", "plugin");
        term("インストール", "install");
        term("アンインストール", "uninstall");
        term("アップデート", "update");
        term("ダウンロード", "download");
        term("説明", "description");
        term("作者", "author");
        term("バージョン", "version");
        term("設定", "settings");
        term("検索", "search");
        term("置換", "replace");
        term("保存", "save");
        term("削除", "delete");
        term("コピー", "copy");
        term("移動", "move");
        term("名前変更", "rename");
        term("開く", "open");
        term("閉じる", "close");
        term("キャンセル", "cancel");
        term("確認", "confirm");
        term("エラー", "error");
        term("失敗", "failed");
        term("成功", "success");
        term("権限", "permission");
        term("ファイル", "file");
        term("フォルダー", "folder");
        term("サービス", "service");
        term("接続", "connect");
        term("切断", "disconnect");

        // Korean common UI and technical terms.
        term("플러그인 스토어", "Plugin Store");
        term("플러그인", "plugin");
        term("설치", "install");
        term("제거", "uninstall");
        term("업데이트", "update");
        term("다운로드", "download");
        term("설명", "description");
        term("작성자", "author");
        term("버전", "version");
        term("설정", "settings");
        term("검색", "search");
        term("바꾸기", "replace");
        term("저장", "save");
        term("삭제", "delete");
        term("복사", "copy");
        term("이동", "move");
        term("이름 바꾸기", "rename");
        term("열기", "open");
        term("닫기", "close");
        term("취소", "cancel");
        term("확인", "confirm");
        term("오류", "error");
        term("실패", "failed");
        term("성공", "success");
        term("권한", "permission");
        term("파일", "file");
        term("폴더", "folder");
        term("서비스", "service");
        term("연결", "connect");

        // Cyrillic / European common UI terms.
        term("Магазин плагинов", "Plugin Store");
        term("плагин", "plugin"); term("Плагин", "Plugin");
        term("Установить", "Install"); term("Удалить", "Delete");
        term("Обновить", "Update"); term("Настройки", "Settings");
        term("Поиск", "Search"); term("Сохранить", "Save");
        term("Отмена", "Cancel"); term("Ошибка", "Error");
        term("archivo", "file"); term("carpeta", "folder");
        term("instalar", "install"); term("desinstalar", "uninstall");
        term("actualizar", "update"); term("configuración", "settings");
        term("buscar", "search"); term("guardar", "save");
        term("cancelar", "cancel"); term("error", "error");
        term("fichier", "file"); term("dossier", "folder");
        term("installer", "install"); term("désinstaller", "uninstall");
        term("mettre à jour", "update"); term("paramètres", "settings");
        term("rechercher", "search"); term("enregistrer", "save");
        term("annuler", "cancel");
        term("Datei", "file"); term("Ordner", "folder");
        term("installieren", "install"); term("deinstallieren", "uninstall");
        term("aktualisieren", "update"); term("Einstellungen", "settings");
        term("suchen", "search"); term("speichern", "save");
        term("abbrechen", "cancel"); term("Fehler", "error");
        term("arquivo", "file"); term("pasta", "folder");
        term("instalar", "install"); term("desinstalar", "uninstall");
        term("atualizar", "update"); term("configurações", "settings");
        term("pesquisar", "search"); term("salvar", "save");
        term("cancelar", "cancel");
        term("file", "file"); term("cartella", "folder");
        term("installare", "install"); term("disinstallare", "uninstall");
        term("aggiornare", "update"); term("impostazioni", "settings");
        term("cercare", "search"); term("salvare", "save");
        term("annullare", "cancel"); term("errore", "error");

        ArrayList<Map.Entry<String, String>> sorted = new ArrayList<>(TERMS.entrySet());
        Collections.sort(sorted, new Comparator<Map.Entry<String, String>>() {
            @Override
            public int compare(Map.Entry<String, String> a, Map.Entry<String, String> b) {
                return Integer.compare(b.getKey().length(), a.getKey().length());
            }
        });
        SORTED_TERMS = Collections.unmodifiableList(sorted);
    }

    private TranslationMemory() {}

    static String translate(String source) {
        if (source == null) return null;
        String normalized = normalize(source);
        if (normalized.isEmpty()) return null;

        Matcher round = AGENT_ROUND.matcher(normalized);
        if (round.matches()) {
            return "[Agent round " + round.group(1) + "] MCP tool called; retrieving results...";
        }

        String exact = EXACT.get(normalized);
        if (exact != null) return exact;

        String output = normalized;
        boolean changed = false;
        for (Map.Entry<String, String> entry : SORTED_TERMS) {
            String before = output;
            output = replaceIgnoreCaseForLatin(output, entry.getKey(), entry.getValue());
            if (!before.equals(output)) changed = true;
        }

        output = output
                .replace('，', ',')
                .replace('。', '.')
                .replace('：', ':')
                .replace('；', ';')
                .replace('（', '(')
                .replace('）', ')')
                .replace('【', '[')
                .replace('】', ']')
                .replace('“', '"')
                .replace('”', '"')
                .replace('！', '!')
                .replace('？', '?');
        output = output.replaceAll("\\s+", " ").trim();
        output = output.replaceAll("\\s+([,.;:!?])", "$1");

        if (!changed || output.equals(normalized)) return null;
        if (CJK_OR_CYRILLIC.matcher(output).find()) {
            return "Partial: " + output;
        }
        return output;
    }

    static boolean containsForeignScript(String text) {
        return text != null && CJK_OR_CYRILLIC.matcher(text).find();
    }

    private static String normalize(String text) {
        return text.replace('\u00A0', ' ')
                .replaceAll("[\\t\\r\\n]+", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private static String replaceIgnoreCaseForLatin(String input, String key, String value) {
        if (key.isEmpty()) return input;
        boolean latinOnly = key.matches("[\\p{IsLatin}0-9 _-]+");
        if (!latinOnly) return input.replace(key, value);
        return input.replaceAll("(?i)" + Pattern.quote(key), Matcher.quoteReplacement(value));
    }

    private static void exact(String source, String english) {
        EXACT.put(source, english);
    }

    private static void term(String source, String english) {
        TERMS.put(source, english);
    }
}
