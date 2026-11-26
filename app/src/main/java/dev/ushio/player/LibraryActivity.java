package dev.ushio.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LibraryActivity extends Activity {
    interface Result<T> { void accept(T value); }
    private final ExecutorService requests = Executors.newFixedThreadPool(3);
    private final ExecutorService art = Executors.newFixedThreadPool(4);
    private final LruCache<String, Bitmap> pictures = new LruCache<String, Bitmap>(18 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Ui ui;
    private Preferences preferences;
    private FnClient client;
    private boolean demo;
    private LinearLayout root, content;
    private ScrollView scroll;
    private int screen;
    private volatile int generation;
    private String tab = "home", filter = "";
    private Runnable back;
    private boolean loaded, loading;
    private List<Title> libraries = Collections.emptyList();
    private List<Title> recent = Collections.emptyList();
    private List<Title> watching = Collections.emptyList();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this);
        preferences = new Preferences(this);
        demo = preferences.demo();
        FnClient.Session session = preferences.session();
        if (session != null) client = new FnClient(session);
        if (demo || client != null) home();
        else settings(true);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        requests.shutdownNow();
        art.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 21 || isFinishing() || demo) return;
        FnClient current = client;
        if (current == null) return;
        handler.postDelayed(() -> {
            if (isDestroyed() || current != client) return;
            requests.execute(() -> {
                try {
                    List<Title> updated = current.resume();
                    runOnUiThread(() -> {
                        if (isDestroyed() || current != client || demo) return;
                        watching = updated;
                        if (tab.equals("home") && back == null) home();
                    });
                } catch (Exception ignored) {}
            });
        }, 350);
    }

    private void page(String tab, Runnable back, boolean nav) {
        generation++;
        screen++;
        this.tab = tab;
        this.back = back;
        root = ui.column();
        root.setBackgroundColor(Ui.BASE);
        setContentView(root);
        fitSystemBars(root);
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        content = ui.column();
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        if (nav) root.addView(navigation(), new LinearLayout.LayoutParams(-1, ui.dp(65)));
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    private void fitSystemBars(View view) {
        view.setOnApplyWindowInsetsListener((target, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.statusBars()
                                | android.view.WindowInsets.Type.navigationBars());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            target.setPadding(left, top, right, bottom);
            return insets;
        });
        view.requestApplyInsets();
    }

    private <T> void run(Callable<T> call, Result<T> success, Result<Exception> failure) {
        int requested = screen;
        requests.execute(() -> {
            try {
                T result = call.call();
                runOnUiThread(() -> {
                    if (!isDestroyed() && requested == screen) success.accept(result);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!isDestroyed() && requested == screen) failure.accept(error);
                });
            }
        });
    }

    static String reason(Exception error) {
        if (error instanceof FnClient.ApiError) return error.getMessage();
        if (error instanceof java.net.SocketTimeoutException) return "连接超时，请检查手机与服务的网络连接。";
        if (error instanceof javax.net.ssl.SSLException) return "HTTPS 证书校验失败，请检查服务器证书。";
        if (error instanceof java.net.UnknownHostException || error instanceof java.net.ConnectException) {
            return "无法连接服务，请检查地址、端口和网络。";
        }
        if (error instanceof IOException && error.getMessage() != null
                && error.getMessage().matches(".*[\\u4e00-\\u9fa5].*")) return error.getMessage();
        return "暂时无法读取服务数据，请重试。";
    }

    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }

    private LinearLayout section() {
        LinearLayout body = ui.column();
        ui.pad(body, 20, 0, 20, 0);
        content.addView(body, new LinearLayout.LayoutParams(-1, -2));
        return body;
    }

    private View navigation() {
        LinearLayout bar = ui.row();
        bar.setBackgroundColor(0xff11131c);
        ui.pad(bar, 15, 0, 15, 0);
        String[] keys = {"home", "library", "search", "settings"};
        String[] labels = {"首页", "片库", "搜索", "连接"};
        String[] icons = {"home", "grid", "search", "plug"};
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            boolean active = tab.equals(key);
            LinearLayout item = ui.column();
            item.setGravity(Gravity.CENTER);
            FrameLayout badge = new FrameLayout(this);
            if (active) badge.setBackground(ui.filled(0xff30284d, 17, 0));
            badge.addView(ui.icon(icons[i], active ? Ui.ACCENT : Ui.MUTED),
                    new FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.CENTER));
            item.addView(badge, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(28)));
            ui.space(item, 4);
            TextView label = ui.text(labels[i], 11, active ? Ui.ACCENT : Ui.MUTED, active);
            label.setGravity(Gravity.CENTER);
            item.addView(label);
            item.setContentDescription(labels[i]);
            item.setSelected(active);
            ui.click(item, 0, 12, 0, () -> navigate(key));
            bar.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
        }
        return bar;
    }

    private void navigate(String key) {
        if (key.equals("settings") || !demo && client == null) { settings(!demo && client == null); return; }
        if (key.equals("library")) library();
        else if (key.equals("search")) searchPage("");
        else home();
    }

    private void brand(LinearLayout body) {
        LinearLayout bar = ui.row();
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.ic_ushio);
        bar.addView(mark, new LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)));
        ui.gap(bar, 10);
        bar.addView(ui.text("潮汐放映", 20, Ui.TEXT, true));
        ui.stretch(bar);
        LinearLayout status = ui.row();
        ui.pad(status, 10, 6, 10, 6);
        View dot = new View(this);
        dot.setBackground(ui.filled(demo ? Ui.ACCENT : client == null ? Ui.MUTED : Ui.GREEN, 9, 0));
        status.addView(dot, new LinearLayout.LayoutParams(ui.dp(6), ui.dp(6)));
        ui.gap(status, 6);
        status.addView(ui.text(demo ? "示例模式" : client == null ? "未连接" : "飞牛影视", 11, Ui.MUTED, true));
        if (!tab.equals("settings")) ui.click(status, Ui.PANEL, 13, Ui.STROKE, () -> settings(false));
        else status.setBackground(ui.filled(Ui.PANEL, 13, Ui.STROKE));
        bar.addView(status);
        body.addView(bar, new LinearLayout.LayoutParams(-1, ui.dp(61)));
    }

    private void heading(LinearLayout body, String title, String subtitle) {
        body.addView(ui.text(title, 28, Ui.TEXT, true));
        if (!subtitle.isEmpty()) {
            ui.space(body, 8);
            TextView text = ui.text(subtitle, 13, Ui.MUTED, false);
            text.setLineSpacing(ui.dp(4), 1);
            body.addView(text);
        }
    }

    private void sectionTitle(LinearLayout body, String name, String action, Runnable run) {
        LinearLayout line = ui.row();
        line.addView(ui.text(name, 19, Ui.TEXT, true));
        if (!action.isEmpty()) {
            ui.stretch(line);
            TextView link = ui.text(action + "  ›", 12, Ui.ACCENT, true);
            ui.pad(link, 8, 9, 0, 9);
            link.setOnClickListener(v -> run.run());
            link.setFocusable(true);
            line.addView(link);
        }
        body.addView(line, new LinearLayout.LayoutParams(-1, ui.dp(36)));
    }

    private void home() {
        page("home", null, true);
        LinearLayout body = section();
        brand(body);
        ui.space(body, 7);
        if (!demo && !loaded) { startLoad(body); return; }
        List<Title> all = demo ? Demo.TITLES : recent;
        Title featured = null;
        for (Title title : all) if (!title.folder()) { featured = title; break; }
        if (featured != null) body.addView(hero(featured, false, this::home),
                new LinearLayout.LayoutParams(-1, ui.dp(298)));
        if (all.isEmpty()) {
            message(body, "片库暂无内容", "飞牛影视完成媒体扫描后，影片会显示在这里。", "刷新", this::refresh);
            return;
        }
        if (!demo && !watching.isEmpty()) {
            LinearLayout section = section();
            ui.space(section, 15);
            sectionTitle(section, "继续观看", "", null);
            LinearLayout rail = rail();
            for (Title title : watching.subList(0, Math.min(watching.size(), 12))) {
                rail.addView(card(title, 85, true, this::home), new LinearLayout.LayoutParams(ui.dp(158), -2));
                ui.gap(rail, 11);
            }
        }
        LinearLayout shelf = section();
        ui.space(shelf, 15);
        sectionTitle(shelf, "探索片库", "查看片库", this::library);
        ui.space(shelf, 5);
        LinearLayout chips = ui.row();
        String[] keys = {"", "Movie", "TV"};
        String[] names = {"全部", "电影", "剧集"};
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            boolean active = filter.equals(key);
            TextView label = ui.text(names[i], 12, active ? Ui.ACCENT : Ui.MUTED, true);
            label.setGravity(Gravity.CENTER);
            ui.pad(label, 15, 0, 15, 0);
            ui.click(label, active ? 0xff2d2547 : Ui.PANEL, 17, Ui.STROKE, () -> {
                int y = scroll.getScrollY();
                filter = key;
                home();
                scroll.post(() -> scroll.scrollTo(0, y));
            });
            chips.addView(label, new LinearLayout.LayoutParams(-2, ui.dp(35)));
            ui.gap(chips, 8);
        }
        shelf.addView(chips);
        ui.space(shelf, 16);
        ArrayList<Title> shown = new ArrayList<>();
        for (Title title : all) if (filter.isEmpty() || title.is(filter)) shown.add(title);
        if (shown.size() > 1 && shown.remove(featured)) shown.add(featured);
        LinearLayout rail = rail();
        for (Title title : shown.subList(0, Math.min(shown.size(), 24))) {
            rail.addView(card(title, 128, false, this::home), new LinearLayout.LayoutParams(ui.dp(111), -2));
            ui.gap(rail, 11);
        }
        if (shown.isEmpty()) rail.addView(ui.text("此分类暂无内容", 13, Ui.MUTED, false));
        ui.space(content, 23);
    }

    private void startLoad(LinearLayout body) {
        progress(body, "正在读取飞牛影视");
        if (loading) return;
        loading = true;
        FnClient current = client;
        requests.execute(() -> {
            try {
                List<Title> groups = current.libraries();
                List<Title> resume;
                try { resume = current.resume(); } catch (Exception ignored) { resume = Collections.emptyList(); }
                LinkedHashMap<String, Title> records = new LinkedHashMap<>();
                if (groups.isEmpty()) {
                    collectPages(current, "", records);
                } else {
                    for (Title group : groups) {
                        collectPages(current, group.id, records);
                    }
                }
                List<Title> newRecent = new ArrayList<>(records.values());
                newRecent.sort((a, b) -> b.year.compareTo(a.year));
                List<Title> newWatching = resume;
                runOnUiThread(() -> {
                    if (isDestroyed() || current != client) return;
                    libraries = groups;
                    watching = newWatching;
                    recent = newRecent;
                    loading = false;
                    loaded = true;
                    if (back == null && !demo && (tab.equals("home") || tab.equals("library"))) navigate(tab);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isDestroyed() || current != client) return;
                    loading = false;
                    if (back == null && !demo && (tab.equals("home") || tab.equals("library"))) {
                        page(tab, null, true);
                        LinearLayout failed = section();
                        brand(failed);
                        message(failed, "无法读取片库", reason(error), "重试", this::refresh);
                    }
                });
            }
        });
    }

    private void collectPages(FnClient current, String library, LinkedHashMap<String, Title> records)
            throws Exception {
        for (int page = 1; ; page++) {
            FnClient.Page result = current.page(library, "", page);
            for (Title title : result.titles) records.putIfAbsent(title.id, title);
            if (!result.more) return;
        }
    }

    private void refresh() { loaded = false; navigate(tab); }

    private FrameLayout hero(Title title, boolean detail, Runnable back) {
        FrameLayout hero = new FrameLayout(this);
        hero.setBackground(ui.filled(Ui.PANEL, 22, 0));
        hero.setClipToOutline(true);
        hero.addView(image(title, true), new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this);
        shade.setBackground(ui.gradient(0x1010121c, 0x2010121c, 0xb20d101a, 0xff0d101a));
        hero.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        if (!detail) {
            TextView badge = ui.text(title.category(), 11, Ui.TEXT, true);
            badge.setBackground(ui.filled(0xd0272444, 9, 0x54c4b5ff));
            ui.pad(badge, 9, 5, 9, 5);
            FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            at.setMargins(ui.dp(16), ui.dp(15), 0, 0);
            hero.addView(badge, at);
        }
        LinearLayout overlay = ui.column();
        ui.pad(overlay, 17, 0, 17, 17);
        TextView name = ui.text(title.name, detail ? 32 : 35, Ui.TEXT, true);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        overlay.addView(name);
        ui.space(overlay, 6);
        overlay.addView(ui.text(title.meta(), 12, 0xffd6cee5, false));
        if (!detail) {
            ui.space(overlay, 13);
            LinearLayout actions = ui.row();
            actions.addView(ui.button("播放", "play", true, () -> open(title, true, back)));
            ui.gap(actions, 9);
            actions.addView(ui.button("查看详情", "", false, () -> open(title, false, back)));
            overlay.addView(actions);
        }
        hero.addView(overlay, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        return hero;
    }

    private ImageView image(Title title, boolean wide) {
        ImageView view = new ImageView(this);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setBackground(ui.filled(0xff252239, 12, 0));
        if (title.image != 0) view.setImageResource(title.image);
        if (demo || client == null) return view;
        String path = wide ? title.art : title.poster;
        if (path.isEmpty()) return view;
        FnClient current = client;
        int size = ui.dp(wide ? 520 : 200);
        String key = current.session.base + "#" + size + "#" + path;
        Bitmap cached = pictures.get(key);
        if (cached != null) view.setImageBitmap(cached);
        else art.execute(() -> {
            try {
                Bitmap bitmap = current.image(path, size);
                if (bitmap != null) {
                    pictures.put(key, bitmap);
                    runOnUiThread(() -> {
                        if (!isDestroyed() && current == client) view.setImageBitmap(bitmap);
                    });
                }
            } catch (Exception ignored) {}
        });
        return view;
    }

    private LinearLayout rail() {
        HorizontalScrollView view = new HorizontalScrollView(this);
        view.setHorizontalScrollBarEnabled(false);
        view.setClipToPadding(false);
        ui.pad(view, 20, 0, 20, 0);
        LinearLayout cards = ui.row();
        cards.setGravity(Gravity.TOP);
        view.addView(cards);
        content.addView(view);
        return cards;
    }

    private LinearLayout card(Title title, int height, boolean resume, Runnable back) {
        LinearLayout card = ui.column();
        ImageView cover = image(title, resume);
        cover.setClipToOutline(true);
        card.addView(cover, new LinearLayout.LayoutParams(-1, ui.dp(height)));
        if (resume && title.duration > 0) {
            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            bar.setProgress((int) Math.min(100, title.position * 100 / title.duration));
            bar.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
            card.addView(bar, new LinearLayout.LayoutParams(-1, ui.dp(3)));
        }
        ui.space(card, 8);
        String display = resume && !title.series.isEmpty() ? title.series + " · " + title.category() : title.name;
        TextView name = ui.text(display, 14, Ui.TEXT, true);
        name.setSingleLine();
        name.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(name);
        ui.space(card, 4);
        card.addView(ui.text(title.meta(), 10, Ui.MUTED, false));
        card.setContentDescription(display);
        ui.click(card, 0, 12, 0, () -> open(title, resume, back));
        return card;
    }

    private void library() {
        page("library", null, true);
        LinearLayout body = section();
        brand(body);
        ui.space(body, 12);
        heading(body, "我的片库", demo ? "浏览示例电影与剧集" : "按飞牛影视媒体库浏览影片");
        ui.space(body, 17);
        if (demo) { grid(body, Demo.TITLES, this::library); return; }
        if (!loaded) { startLoad(body); return; }
        if (libraries.isEmpty()) {
            libraryFolder("", "", "全部影片", this::library);
            return;
        }
        for (Title item : libraries) line(body, item.name, "打开媒体库", "grid",
                () -> libraryFolder(item.id, "", item.name, this::library));
        ui.space(body, 8);
        body.addView(ui.button("刷新媒体库", "", false, this::refresh));
    }

    private void libraryFolder(String library, String folder, String name, Runnable previous) {
        page("library", previous, false);
        LinearLayout body = section();
        top(body, "片库", previous);
        heading(body, name, "");
        ui.space(body, 16);
        LinearLayout items = ui.column();
        body.addView(items);
        Runnable restore = () -> libraryFolder(library, folder, name, previous);
        loadFolder(library, folder, 1, items, new LinkedHashMap<>(), restore);
    }

    private void loadFolder(String library, String folder, int page, LinearLayout items,
                            LinkedHashMap<String, Title> loaded, Runnable restore) {
        if (page == 1) progress(items, "正在读取影片");
        FnClient current = client;
        run(() -> current.page(library, folder, page), result -> {
            int count = loaded.size();
            for (Title title : result.titles) loaded.putIfAbsent(title.id, title);
            items.removeAllViews();
            if (loaded.isEmpty()) { message(items, "这里还没有影片", "", "刷新", restore); return; }
            ArrayList<Title> media = new ArrayList<>();
            for (Title title : loaded.values()) {
                if (title.folder()) line(items, title.name, "文件夹", "list",
                        () -> libraryFolder(library, title.id, title.name, restore));
                else media.add(title);
            }
            grid(items, media, restore);
            if (result.more && loaded.size() > count) {
                items.addView(ui.button("加载更多", "", false, () -> {
                    items.getChildAt(items.getChildCount() - 2).setEnabled(false);
                    loadFolder(library, folder, page + 1, items, loaded, restore);
                }));
                ui.space(items, 17);
            }
        }, error -> {
            if (page > 1) {
                items.getChildAt(items.getChildCount() - 2).setEnabled(true);
                toast(reason(error));
            } else {
                items.removeAllViews();
                message(items, "读取失败", reason(error), "重试", restore);
            }
        });
    }

    private void grid(LinearLayout body, List<Title> titles, Runnable back) {
        int width = getResources().getConfiguration().screenWidthDp;
        int columns = width >= 640 ? 5 : width >= 345 ? 3 : 2;
        int height = Math.max(90, Math.min(218, (width - 40 - (columns - 1) * 11) / columns * 133 / 100));
        for (int i = 0; i < titles.size(); i += columns) {
            LinearLayout row = ui.row();
            row.setGravity(Gravity.TOP);
            for (int column = 0; column < columns; column++) {
                if (column > 0) ui.gap(row, 11);
                View card = i + column < titles.size() ? card(titles.get(i + column), height, false, back) : new View(this);
                row.addView(card, new LinearLayout.LayoutParams(0, -2, 1));
            }
            body.addView(row);
            ui.space(body, 15);
        }
    }

    private void searchPage(String initial) {
        page("search", null, true);
        LinearLayout body = section();
        body.setFocusableInTouchMode(true);
        body.requestFocus();
        brand(body);
        ui.space(body, 12);
        heading(body, "搜索片库", "按影片名或剧集名查找");
        ui.space(body, 16);
        EditText input = field("搜索片名", initial, InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        body.addView(input, new LinearLayout.LayoutParams(-1, ui.dp(49)));
        ui.space(body, 16);
        LinearLayout results = ui.column();
        body.addView(results);
        if (initial.isEmpty()) results.addView(ui.text("输入片名开始搜索", 14, Ui.MUTED, false));
        else search(initial, ++generation, results);
        input.setOnEditorActionListener((view, action, event) -> {
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) return false;
            hideKeyboard(input);
            return true;
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String keyword = s.toString().trim();
                int pending = ++generation;
                handler.postDelayed(() -> {
                    if (pending == generation) search(keyword, pending, results);
                }, 300);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void search(String raw, int pending, LinearLayout results) {
        results.removeAllViews();
        if (raw.isEmpty()) { results.addView(ui.text("输入片名开始搜索", 14, Ui.MUTED, false)); return; }
        String query = raw.toLowerCase(Locale.ROOT);
        Runnable restore = () -> searchPage(raw);
        if (demo) {
            ArrayList<Title> found = new ArrayList<>();
            for (Title title : Demo.TITLES) if (matching(title, query)) found.add(title);
            searchResult(results, found, restore);
            return;
        }
        progress(results, "正在搜索媒体库");
        FnClient current = client;
        run(() -> {
            if (pending != generation) return Collections.<Title>emptyList();
            List<Title> serverSearch = current.search(raw);
            if (serverSearch != null) return serverSearch;
            LinkedHashMap<String, Title> found = new LinkedHashMap<>();
            List<String> scopes = new ArrayList<>();
            for (Title category : current.libraries()) scopes.add(category.id);
            if (scopes.isEmpty()) scopes.add("");
            for (String scope : scopes) {
                java.util.HashSet<String> seen = new java.util.HashSet<>();
                for (int index = 1; pending == generation; index++) {
                    FnClient.Page result = current.page(scope, "", index);
                    boolean newItems = false;
                    for (Title title : result.titles) {
                        newItems |= seen.add(title.id);
                        if (!title.folder() && matching(title, query)) found.putIfAbsent(title.id, title);
                    }
                    if (!result.more || !newItems || found.size() >= 120) break;
                }
            }
            return new ArrayList<>(found.values());
        }, resultsList -> {
            if (pending == generation) searchResult(results, resultsList, restore);
        }, error -> {
            if (pending != generation) return;
            results.removeAllViews();
            message(results, "搜索失败", reason(error), "重试", () -> search(raw, pending, results));
        });
    }

    private boolean matching(Title title, String query) {
        return (title.name + " " + title.series).toLowerCase(Locale.ROOT).contains(query);
    }

    private void searchResult(LinearLayout results, List<Title> found, Runnable back) {
        results.removeAllViews();
        if (found.isEmpty()) results.addView(ui.text("没有找到匹配的影片。", 14, Ui.MUTED, false));
        else {
            sectionTitle(results, "搜索结果", "", null);
            ui.space(results, 5);
            grid(results, found, back);
        }
    }

    private void open(Title title, boolean play, Runnable previous) {
        hideKeyboard(content);
        if (title.folder()) {
            libraryFolder(title.is("MediaDB") ? title.id : "", title.is("MediaDB") ? "" : title.id, title.name, previous);
        } else if (play && !title.is("TV") && !title.is("Season")) {
            if (!demo && title.is("Episode") && !title.parent.isEmpty()) {
                FnClient current = client;
                run(() -> current.episodes(title.parent), queue -> launch(title, queue),
                        error -> launch(title, Collections.singletonList(title)));
            } else launch(title, title.is("Episode") && demo ? Demo.EPISODES : Collections.singletonList(title));
        } else details(title, previous, play);
    }

    private void details(Title title, Runnable previous, boolean autoplay) {
        page(tab, previous, false);
        LinearLayout body = section();
        top(body, "影片详情", previous);
        body.addView(hero(title, true, previous), new LinearLayout.LayoutParams(-1, ui.dp(235)));
        ui.space(body, 14);
        boolean series = title.is("TV") || title.is("Season");
        if (!series) {
            long pos = demo ? preferences.values.getLong(preferences.mediaKey(null, title.id) + ".pos", 0) : title.position;
            body.addView(ui.button(pos > 0 ? "继续播放" : "播放影片", "play", true,
                    () -> launch(title, Collections.singletonList(title))), new LinearLayout.LayoutParams(-1, ui.dp(47)));
            ui.space(body, 16);
        }
        TextView synopsis = ui.text(title.overview, 14, Ui.MUTED, false);
        synopsis.setLineSpacing(ui.dp(4), 1);
        synopsis.setVisibility(title.overview.isEmpty() ? View.GONE : View.VISIBLE);
        body.addView(synopsis);
        if (!demo && title.overview.isEmpty()) {
            FnClient current = client;
            run(() -> current.details(title.id), expanded -> {
                if (expanded.overview.isEmpty()) return;
                synopsis.setText(expanded.overview);
                synopsis.setVisibility(View.VISIBLE);
            }, error -> {});
        }
        if (series) {
            ui.space(body, 16);
            sectionTitle(body, "选集", "", null);
            LinearLayout area = ui.column();
            body.addView(area);
            if (demo) {
                episodes(area, Demo.EPISODES, autoplay);
            } else if (title.is("Season")) {
                season(area, title, autoplay);
            } else {
                progress(area, "正在读取季与剧集");
                FnClient current = client;
                run(() -> current.seasons(title.id), seasons -> {
                    area.removeAllViews();
                    seasons.sort((a, b) -> Integer.compare(a.season, b.season));
                    if (seasons.size() <= 1) {
                        season(area, seasons.isEmpty() ? title : seasons.get(0), autoplay);
                        return;
                    }
                    String[] choices = new String[seasons.size()];
                    for (int i = 0; i < seasons.size(); i++) choices[i] = seasons.get(i).name.isEmpty()
                            ? seasons.get(i).category() : seasons.get(i).name;
                    LinearLayout episodes = ui.column();
                    TextView selected = ui.text(choices[0] + "  ▾", 14, Ui.ACCENT, true);
                    ui.pad(selected, 2, 9, 4, 9);
                    selected.setFocusable(true);
                    selected.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("选择季")
                            .setItems(choices, (dialog, which) -> {
                                selected.setText(choices[which] + "  ▾");
                                season(episodes, seasons.get(which), false);
                            }).show());
                    area.addView(selected);
                    area.addView(episodes);
                    season(episodes, seasons.get(0), autoplay);
                }, error -> {
                    area.removeAllViews();
                    message(area, "无法读取剧集", reason(error), "重试", () -> details(title, previous, autoplay));
                });
            }
        }
        ui.space(body, 23);
    }

    private void season(LinearLayout area, Title season, boolean autoplay) {
        area.removeAllViews();
        area.setTag(season.id);
        progress(area, "正在读取剧集");
        FnClient current = client;
        run(() -> current.episodes(season.id), list -> {
            if (season.id.equals(area.getTag())) episodes(area, list, autoplay);
        }, error -> {
            if (!season.id.equals(area.getTag())) return;
            area.removeAllViews();
            message(area, "读取失败", reason(error), "重试", () -> season(area, season, autoplay));
        });
    }

    private void episodes(LinearLayout area, List<Title> list, boolean autoplay) {
        area.removeAllViews();
        if (list.isEmpty()) { message(area, "暂无可播放剧集", "", "", null); return; }
        for (Title title : list) line(area, title.name, title.episode > 0 ? title.category() : "剧集",
                "play", () -> launch(title, list));
        if (!autoplay) return;
        Title target = list.get(0);
        for (Title title : list) {
            long position = demo ? preferences.values.getLong(preferences.mediaKey(null, title.id) + ".pos", 0) : title.position;
            if (position > 0 && (title.duration <= 0 || position + 30000 < title.duration)) { target = title; break; }
        }
        launch(target, list);
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private void launch(Title title, List<Title> choices) {
        hideKeyboard(content);
        Intent intent = new Intent(this, PlaybackActivity.class);
        intent.putExtra("id", title.id);
        intent.putExtra("name", title.name);
        intent.putExtra("demo", demo);
        List<Title> queue = choices;
        boolean contains = false;
        for (Title item : queue) if (item.id.equals(title.id)) contains = true;
        if (!contains) queue = Collections.singletonList(title);
        ArrayList<String> ids = new ArrayList<>(), names = new ArrayList<>(), series = new ArrayList<>();
        ArrayList<Integer> seasons = new ArrayList<>(), episodes = new ArrayList<>();
        for (Title item : queue) {
            ids.add(item.id);
            names.add(item.name);
            series.add(item.series);
            seasons.add(item.season);
            episodes.add(item.episode);
        }
        intent.putStringArrayListExtra("queue.ids", ids);
        intent.putStringArrayListExtra("queue.names", names);
        intent.putStringArrayListExtra("queue.series", series);
        intent.putIntegerArrayListExtra("queue.seasons", seasons);
        intent.putIntegerArrayListExtra("queue.episodes", episodes);
        startActivityForResult(intent, 21);
    }

    private void top(LinearLayout body, String label, Runnable previous) {
        LinearLayout line = ui.row();
        line.addView(ui.iconButton("back", "返回", Ui.TEXT, Ui.PANEL, previous),
                new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        ui.gap(line, 10);
        line.addView(ui.text(label, 14, Ui.MUTED, true));
        body.addView(line, new LinearLayout.LayoutParams(-1, ui.dp(60)));
    }

    private void line(LinearLayout body, String title, String subtitle, String icon, Runnable action) {
        LinearLayout line = ui.row();
        ui.pad(line, 12, 9, 12, 9);
        FrameLayout badge = new FrameLayout(this);
        badge.setBackground(ui.filled(0xff27213d, 10, 0));
        badge.addView(ui.icon(icon, Ui.ACCENT), new FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.CENTER));
        line.addView(badge, new LinearLayout.LayoutParams(ui.dp(39), ui.dp(39)));
        ui.gap(line, 11);
        LinearLayout words = ui.column();
        TextView name = ui.text(title, 15, Ui.TEXT, true);
        name.setMaxLines(2);
        words.addView(name);
        ui.space(words, 4);
        words.addView(ui.text(subtitle, 11, Ui.MUTED, false));
        line.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        line.addView(ui.icon("right", Ui.MUTED), new LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)));
        ui.click(line, Ui.PANEL, 12, Ui.STROKE, action);
        body.addView(line, new LinearLayout.LayoutParams(-1, -2));
        ui.space(body, 8);
    }

    private EditText field(String hint, String value, int type) {
        EditText input = new EditText(this);
        input.setSingleLine();
        input.setInputType(type);
        input.setText(value);
        input.setHint(hint);
        input.setTextSize(14);
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.MUTED);
        input.setBackground(ui.filled(Ui.BASE, 10, Ui.STROKE));
        ui.pad(input, 12, 8, 12, 8);
        return input;
    }

    private EditText labeled(LinearLayout form, String label, String hint, String value, int type) {
        TextView name = ui.text(label, 12, Ui.MUTED, true);
        form.addView(name);
        ui.space(form, 6);
        EditText input = field(hint, value, type);
        input.setId(View.generateViewId());
        name.setLabelFor(input.getId());
        form.addView(input, new LinearLayout.LayoutParams(-1, ui.dp(44)));
        ui.space(form, 12);
        return input;
    }

    private void settings(boolean welcome) {
        page("settings", null, !welcome);
        LinearLayout body = section();
        body.setFocusableInTouchMode(true);
        body.requestFocus();
        brand(body);
        ui.space(body, 9);
        heading(body, welcome ? "你的私人放映室" : "服务连接", "连接飞牛影视，在手机上看自己的片库。");
        ui.space(body, 13);
        if (welcome) {
            body.addView(ui.button("先体验示例片库", "play", false, this::enterDemo));
            ui.space(body, 11);
        }
        FnClient.Session saved = client == null ? null : client.session;
        LinearLayout server = form(body, "飞牛影视", "plug");
        int url = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI;
        EditText host = labeled(server, "服务器地址或 FN ID",
                "http://192.168.1.10:5666 或 demo-ushio-4827",
                saved == null ? "" : saved.relay ? saved.relayId : saved.base, url);
        EditText user = labeled(server, "账号", "飞牛影视账号", saved == null ? "" : saved.username,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        EditText password = labeled(server, "密码", saved == null ? "输入密码" : "重新连接时填写", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        TextView state = ui.text("", 12, Ui.ACCENT, false);
        final View[] connect = {null};
        connect[0] = ui.button(saved == null ? "连接飞牛影视" : "重新连接", "check", true, () -> {
            hideKeyboard(password);
            String address = host.getText().toString(), username = user.getText().toString(), pass = password.getText().toString();
            state.setText("正在连接…");
            state.setVisibility(View.VISIBLE);
            connect[0].setEnabled(false);
            run(() -> FnClient.login(address, username, pass), session -> {
                try {
                    preferences.save(session);
                    client = new FnClient(session);
                    resetLibrary();
                    demo = false;
                    home();
                } catch (Exception error) {
                    state.setText("无法安全保存会话。");
                    connect[0].setEnabled(true);
                }
            }, error -> {
                state.setText(reason(error));
                connect[0].setEnabled(true);
            });
        });
        server.addView(connect[0], new LinearLayout.LayoutParams(-1, ui.dp(47)));
        state.setVisibility(View.GONE);
        ui.pad(state, 0, 8, 0, 0);
        server.addView(state);
        TextView fnidHint = ui.text("使用 FN ID 时无需填写端口，仍需输入飞牛影视本地账号和密码。", 11, Ui.MUTED, false);
        ui.pad(fnidHint, 0, 8, 0, 0);
        server.addView(fnidHint);
        if (saved != null && !demo) {
            ui.space(server, 9);
            server.addView(ui.button("断开连接", "", false, () -> new AlertDialog.Builder(this)
                    .setTitle("断开飞牛影视连接？").setMessage("系统将清除本机保存的飞牛影视会话。")
                    .setNegativeButton("取消", null).setPositiveButton("断开", (dialog, which) -> {
                        preferences.clearSession();
                        client = null;
                        resetLibrary();
                        settings(true);
                    }).show()));
        }

        ui.space(body, 13);
        LinearLayout danmu = form(body, "弹幕服务", "chat");
        String old = preferences.secret("danmu");
        EditText address = labeled(danmu, "danmu_api 地址", "http://192.168.1.10:9321/令牌", old, url);
        TextView result = ui.text(old.isEmpty() ? "可在连接飞牛影视后继续配置。" : "已保存弹幕服务地址。",
                12, Ui.MUTED, false);
        final View[] test = {null};
        test[0] = ui.button("测试并保存", "", false, () -> {
            hideKeyboard(address);
            String input = address.getText().toString().trim();
            test[0].setEnabled(false);
            result.setText("正在测试弹幕服务…");
            run(() -> {
                if (input.isEmpty()) return "";
                String checked = FnClient.base(input, false);
                new DanmuClient(checked).verify();
                return checked;
            }, checked -> {
                test[0].setEnabled(true);
                try {
                    preferences.saveDanmu(checked);
                    result.setText(checked.isEmpty() ? "弹幕服务地址已清除" : "弹幕服务连接成功");
                    result.setTextColor(Ui.GREEN);
                } catch (Exception error) { result.setText("无法安全保存地址。"); }
            }, error -> {
                test[0].setEnabled(true);
                result.setTextColor(Ui.ACCENT);
                result.setText(reason(error));
            });
        });
        danmu.addView(test[0], new LinearLayout.LayoutParams(-1, ui.dp(45)));
        ui.space(danmu, 8);
        danmu.addView(result);
        ui.space(body, 13);
        if (!welcome) {
            if (!demo) body.addView(ui.button("体验示例片库", "play", false, this::enterDemo));
            else if (client != null) body.addView(ui.button("返回我的片库", "grid", false, () -> {
                preferences.demo(false);
                demo = false;
                home();
            }));
            else body.addView(ui.button("返回示例片库", "grid", false, this::home));
        }
        ui.space(body, 23);
    }

    private void enterDemo() {
        hideKeyboard(content);
        preferences.demo(true);
        demo = true;
        home();
    }

    private void resetLibrary() {
        loaded = loading = false;
        libraries = recent = watching = Collections.emptyList();
        pictures.evictAll();
    }

    private LinearLayout form(LinearLayout parent, String name, String glyph) {
        LinearLayout form = ui.column();
        form.setBackground(ui.filled(Ui.PANEL, 17, Ui.STROKE));
        ui.pad(form, 14, 13, 14, 13);
        LinearLayout bar = ui.row();
        bar.addView(ui.icon(glyph, Ui.ACCENT), new LinearLayout.LayoutParams(ui.dp(21), ui.dp(21)));
        ui.gap(bar, 8);
        bar.addView(ui.text(name, 17, Ui.TEXT, true));
        form.addView(bar);
        ui.space(form, 12);
        parent.addView(form, new LinearLayout.LayoutParams(-1, -2));
        return form;
    }

    private void hideKeyboard(View view) {
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    private void progress(LinearLayout body, String title) {
        LinearLayout row = ui.row();
        ProgressBar progress = new ProgressBar(this);
        progress.setIndeterminateTintList(ColorStateList.valueOf(Ui.ACCENT));
        row.addView(progress, new LinearLayout.LayoutParams(ui.dp(21), ui.dp(21)));
        ui.gap(row, 10);
        row.addView(ui.text(title, 13, Ui.MUTED, false));
        ui.pad(row, 0, 13, 0, 13);
        body.addView(row);
    }

    private void message(LinearLayout body, String title, String detail, String label, Runnable action) {
        LinearLayout card = ui.column();
        card.setBackground(ui.filled(Ui.PANEL, 15, Ui.STROKE));
        ui.pad(card, 15, 16, 15, 16);
        card.addView(ui.text(title, 17, Ui.TEXT, true));
        if (!detail.isEmpty()) {
            ui.space(card, 7);
            TextView note = ui.text(detail, 13, Ui.MUTED, false);
            note.setLineSpacing(ui.dp(3), 1);
            card.addView(note);
        }
        if (!label.isEmpty()) {
            ui.space(card, 10);
            card.addView(ui.button(label, "", false, action));
        }
        body.addView(card, new LinearLayout.LayoutParams(-1, -2));
    }

    @Override public void onBackPressed() {
        if (back != null) back.run();
        else if (!tab.equals("home") && (demo || client != null)) home();
        else super.onBackPressed();
    }
}

