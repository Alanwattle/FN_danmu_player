package dev.ushio.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Rational;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@androidx.media3.common.util.UnstableApi
public final class PlaybackActivity extends Activity {
    private final ExecutorService progress = Executors.newSingleThreadExecutor();
    private final ExecutorService tasks = Executors.newFixedThreadPool(3);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hide = () -> showControls(false);
    private final Runnable clearFeedback = () -> this.hud.setVisibility(View.GONE);
    private Ui ui;
    private Preferences preferences;
    private FnClient client;
    private DanmuClient danmu;
    private boolean demo, locked, controls = true, scrubbing, software, pip, stopped, loading, failed;
    private FrameLayout stage, overlay, lockLayer, middle;
    private LinearLayout top, bottom, message;
    private PlayerView videoView;
    private DanmuView comments;
    private TextView heading, subheading, seekClock, totalClock, hud, playText, danmuText, errorText;
    private View unlock, opening, next;
    private ProgressBar spinner;
    private SeekBar seekBar;
    private AudioManager audio;
    private ExoPlayer player;
    private FnClient.Video video;
    private ArrayList<String> ids, names, series;
    private ArrayList<Integer> seasons, episodes;
    private int queueIndex, sourceIndex, generation, danmuGeneration, resizeIndex;
    private long lastSync, lastSaved = -1, restorePosition = -1;
    private float speed = 1f;
    private Uri localSubtitle;
    private String localSubtitleMime;
    private String danmuStatus = "";
    private static final int[] RESIZE = {AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AspectRatioFrameLayout.RESIZE_MODE_FILL};
    private static final String[] RESIZE_NAMES = {"适应画面", "裁切填满", "拉伸填满"};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this);
        preferences = new Preferences(this);
        demo = getIntent().getBooleanExtra("demo", false);
        FnClient.Session session = preferences.session();
        if (!demo && session == null) { finish(); return; }
        if (session != null && !demo) client = new FnClient(session);
        String danmuAddress = preferences.secret("danmu");
        if (!danmuAddress.isEmpty()) danmu = new DanmuClient(danmuAddress);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        ids = getIntent().getStringArrayListExtra("queue.ids");
        names = getIntent().getStringArrayListExtra("queue.names");
        series = getIntent().getStringArrayListExtra("queue.series");
        seasons = getIntent().getIntegerArrayListExtra("queue.seasons");
        episodes = getIntent().getIntegerArrayListExtra("queue.episodes");
        if (ids == null || ids.isEmpty()) { finish(); return; }
        queueIndex = Math.max(0, ids.indexOf(getIntent().getStringExtra("id")));
        if (state != null) {
            queueIndex = Math.min(ids.size() - 1, Math.max(0, state.getInt("episode", queueIndex)));
            restorePosition = state.getLong("position", -1);
        }
        software = preferences.values.getBoolean("play.software", false);
        resizeIndex = preferences.values.getInt("play.resize", 0);
        setRequestedOrientation(preferences.values.getBoolean("play.portrait", false)
                ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        build();
        immersive();
        setResult(RESULT_OK);
        load(queueIndex);
    }

    private void build() {
        stage = new FrameLayout(this);
        stage.setBackgroundColor(Color.BLACK);
        setContentView(stage);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        videoView = new PlayerView(this);
        videoView.setUseController(false);
        videoView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER);
        videoView.setResizeMode(RESIZE[resizeIndex]);
        videoView.setShutterBackgroundColor(Color.BLACK);
        videoView.setContentDescription("影片画面");
        stage.addView(videoView, new FrameLayout.LayoutParams(-1, -1));
        comments = new DanmuView(this);
        stage.addView(comments, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        stage.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> resizeComments());

        View touch = new View(this);
        touch.setContentDescription("播放手势区域");
        touch.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        touch.setOnTouchListener(new Touch());
        stage.addView(touch, new FrameLayout.LayoutParams(-1, -1));

        overlay = new FrameLayout(this);
        stage.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        makeTop();
        makeBottom();
        middle = new FrameLayout(this);
        LinearLayout transport = ui.row();
        transport.setGravity(Gravity.CENTER);
        transport.addView(icon("rewind", "后退 10 秒", () -> jump(-10000)), size(49));
        ui.gap(transport, 16);
        playText = pill("▶", "播放", () -> toggle());
        playText.setTextSize(26);
        playText.setTextColor(Ui.INK);
        playText.setBackground(ui.filled(Ui.ACCENT, 42, 0));
        transport.addView(playText, size(63));
        ui.gap(transport, 16);
        transport.addView(icon("forward", "前进 10 秒", () -> jump(10000)), size(49));
        middle.addView(transport, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        overlay.addView(middle, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(Ui.ACCENT));
        stage.addView(spinner, new FrameLayout.LayoutParams(ui.dp(34), ui.dp(34), Gravity.CENTER));
        spinner.setVisibility(View.GONE);

        message = ui.column();
        ui.pad(message, 19, 16, 19, 16);
        message.setGravity(Gravity.CENTER);
        message.setBackground(ui.filled(0xf21a1c2a, 15, Ui.STROKE));
        errorText = ui.text("", 14, Ui.TEXT, false);
        errorText.setGravity(Gravity.CENTER);
        message.addView(errorText);
        ui.space(message, 10);
        LinearLayout retry = ui.row();
        retry.addView(ui.button("重试", "", true, this::retry));
        ui.gap(retry, 8);
        retry.addView(ui.button("返回", "", false, this::finish));
        message.addView(retry);
        FrameLayout.LayoutParams msg = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        msg.leftMargin = msg.rightMargin = ui.dp(15);
        stage.addView(message, msg);
        message.setVisibility(View.GONE);

        lockLayer = new FrameLayout(this);
        stage.addView(lockLayer, new FrameLayout.LayoutParams(-1, -1));
        unlock = icon("unlock", "解锁屏幕", () -> lock(false));
        FrameLayout.LayoutParams lockAt = new FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), Gravity.CENTER_VERTICAL | Gravity.START);
        lockAt.leftMargin = ui.dp(21);
        lockLayer.addView(unlock, lockAt);
        unlock.setVisibility(View.GONE);

        hud = ui.text("", 15, Ui.TEXT, true);
        hud.setGravity(Gravity.CENTER);
        ui.pad(hud, 16, 10, 16, 10);
        hud.setBackground(ui.filled(0xe62e2842, 24, 0x7dbfb2ff));
        FrameLayout.LayoutParams tip = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tip.topMargin = ui.dp(82);
        stage.addView(hud, tip);
        hud.setVisibility(View.GONE);
        handler.post(frame);
        handler.post(tick);
    }

    private void makeTop() {
        top = ui.row();
        ui.pad(top, 16, 13, 16, 18);
        top.setBackground(ui.gradient(0xe40c0e15, 0x000c0e15));
        top.addView(icon("back", "返回", this::onBackPressed), size(42));
        ui.gap(top, 10);
        LinearLayout words = ui.column();
        heading = ui.text("", 18, Ui.TEXT, true);
        heading.setSingleLine();
        heading.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(heading);
        ui.space(words, 4);
        subheading = ui.text("", 11, 0xffcbc6db, false);
        subheading.setSingleLine();
        subheading.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(subheading);
        top.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        ui.gap(top, 7);
        if (supportsPip()) top.addView(icon("pip", "画中画", this::enterPip), size(42));
        View more = icon("more", "播放设置", this::settings);
        top.addView(more, size(42));
        overlay.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
    }

    private void makeBottom() {
        bottom = ui.column();
        bottom.setBackground(ui.gradient(0x000c0e15, 0xc60c0e15, 0xff0c0e15));
        ui.pad(bottom, 18, 17, 18, 15);
        LinearLayout offset = ui.row();
        opening = pill("跳过片头", "跳过片头", () -> {
            if (video != null && player != null) player.seekTo(video.opening);
            showControls(true);
        });
        offset.addView(opening, new LinearLayout.LayoutParams(-2, ui.dp(33)));
        opening.setVisibility(View.GONE);
        ui.stretch(offset);
        next = pill("下一集  ›", "播放下一集", () -> switchTo(queueIndex + 1));
        offset.addView(next, new LinearLayout.LayoutParams(-2, ui.dp(33)));
        bottom.addView(offset, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout timeline = ui.row();
        seekClock = ui.text("00:00", 11, Ui.TEXT, true);
        totalClock = ui.text("00:00", 11, Ui.MUTED, false);
        seekClock.setTypeface(android.graphics.Typeface.MONOSPACE);
        totalClock.setTypeface(android.graphics.Typeface.MONOSPACE);
        timeline.addView(seekClock);
        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        seekBar.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
        seekBar.setSecondaryProgressTintList(ColorStateList.valueOf(0xff68617e));
        seekBar.setThumbTintList(ColorStateList.valueOf(Ui.ACCENT));
        seekBar.setContentDescription("播放进度");
        timeline.addView(seekBar, new LinearLayout.LayoutParams(0, ui.dp(42), 1));
        timeline.addView(totalClock);
        bottom.addView(timeline, new LinearLayout.LayoutParams(-1, -2));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (!fromUser || player == null) return;
                if (scrubbing) seekClock.setText(time(duration() * value / 1000));
                else player.seekTo(duration() * value / 1000);
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {
                scrubbing = true;
                handler.removeCallbacks(hide);
            }

            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (player != null && duration() > 0) player.seekTo(duration() * bar.getProgress() / 1000);
                scrubbing = false;
                showControls(true);
            }
        });
        LinearLayout tools = ui.row();
        danmuText = pill("弹幕", "切换弹幕", this::toggleDanmu);
        danmuText.setOnLongClickListener(v -> { danmuSettings(); return true; });
        tools.addView(danmuText, new LinearLayout.LayoutParams(-2, ui.dp(40)));
        ui.gap(tools, 6);
        tools.addView(pill("调节", "弹幕设置与片源匹配", this::danmuSettings),
                new LinearLayout.LayoutParams(-2, ui.dp(40)));
        ui.stretch(tools);
        if (ids.size() > 1) tools.addView(icon("list", "选集", this::queue), size(39));
        tools.addView(icon("lock", "锁定屏幕", () -> lock(true)), size(39));
        tools.addView(icon("full", "切换横竖屏", this::rotate), size(39));
        bottom.addView(tools);
        overlay.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        refreshDanmuButton();
    }

    private View icon(String name, String description, Runnable action) {
        return ui.iconButton(name, description, Ui.TEXT, 0xb4242533, action);
    }

    private LinearLayout.LayoutParams size(int value) {
        return new LinearLayout.LayoutParams(ui.dp(value), ui.dp(value));
    }

    private TextView pill(String name, String description, Runnable action) {
        TextView view = ui.text(name, 13, Ui.TEXT, true);
        ui.pad(view, 10, 0, 10, 0);
        view.setGravity(Gravity.CENTER);
        view.setContentDescription(description);
        ui.click(view, 0xb4242533, 17, 0, action);
        return view;
    }

    private boolean supportsPip() {
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    private void enterPip() {
        if (!supportsPip() || player == null || failed || loading || duration() <= 0) return;
        android.graphics.Rect screen = new android.graphics.Rect();
        comments.getGlobalVisibleRect(screen);
        try {
            PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(16, 9)).setSourceRectHint(screen);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(true);
            enterPictureInPictureMode(builder.build());
        }
        catch (IllegalStateException ignored) { feedback("此设备暂时无法开启画中画"); }
    }

    @Override public void onPictureInPictureModeChanged(boolean active, Configuration configuration) {
        super.onPictureInPictureModeChanged(active, configuration);
        pip = active;
        hud.setVisibility(View.GONE);
        unlock.setVisibility(View.GONE);
        comments.setVisibility(active ? View.GONE : View.VISIBLE);
        showControls(!active);
        if (!active) immersive();
    }

    private void rotate() {
        boolean land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        preferences.values.edit().putBoolean("play.portrait", land).apply();
        setRequestedOrientation(land ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        showControls(true);
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        immersive();
        comments.seek();
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus && !pip) immersive();
    }

    private void showControls(boolean value) {
        controls = value && !pip && !locked;
        overlay.setVisibility(controls ? View.VISIBLE : View.GONE);
        handler.removeCallbacks(hide);
        if (controls && !scrubbing && player != null && player.isPlaying()) handler.postDelayed(hide, 4100);
    }

    private void lock(boolean active) {
        locked = active;
        unlock.setVisibility(active ? View.VISIBLE : View.GONE);
        showControls(!active);
        feedback(active ? "已锁屏，点击锁图标解锁" : "已解锁");
        if (active) handler.postDelayed(() -> { if (locked) unlock.setVisibility(View.GONE); }, 2200);
    }

    private void resizeComments() {
        if (stage == null || comments == null) return;
        int w = stage.getWidth(), h = stage.getHeight();
        if (w == 0 || h == 0) return;
        if (resizeIndex == 0) {
            VideoSize actual = player == null ? VideoSize.UNKNOWN : player.getVideoSize();
            float aspect = actual.height == 0 ? 16f / 9 : actual.width * actual.pixelWidthHeightRatio / actual.height;
            if (aspect <= 0) aspect = 16f / 9;
            if ((float) w / h > aspect) w = (int) (h * aspect);
            else h = (int) (w / aspect);
        }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) comments.getLayoutParams();
        if (params.width != w || params.height != h) {
            params.width = w;
            params.height = h;
            comments.setLayoutParams(params);
        }
    }

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (!isDestroyed() && !stopped && player != null && !pip) comments.frame(player.getCurrentPosition());
            handler.postDelayed(this, stopped || pip || player == null || !player.isPlaying() ? 135 : 16);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (isDestroyed()) return;
            if (!stopped && player != null) {
                updateTime();
                long now = android.os.SystemClock.elapsedRealtime();
                if (player.isPlaying() && now - lastSync > 12000) {
                    lastSync = now;
                    save(false);
                }
            }
            handler.postDelayed(this, 280);
        }
    };

    private void updateTime() {
        long position = player.getCurrentPosition(), total = duration();
        seekBar.setEnabled(total > 0 && player.isCurrentMediaItemSeekable());
        if (!scrubbing) {
            seekClock.setText(time(position));
            seekBar.setProgress(total > 0 ? (int) Math.min(1000, position * 1000 / total) : 0);
        }
        seekBar.setSecondaryProgress(total > 0 ? (int) Math.min(1000, player.getBufferedPosition() * 1000 / total) : 0);
        totalClock.setText(time(total));
        opening.setVisibility(video != null && video.opening > 0 && position + 1000 < video.opening ? View.VISIBLE : View.GONE);
    }

    private long duration() {
        return player == null || player.getDuration() == C.TIME_UNSET ? 0 : Math.max(0, player.getDuration());
    }

    private String time(long milliseconds) {
        long seconds = Math.max(0, milliseconds) / 1000;
        return seconds >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private void feedback(String value) {
        if (pip) return;
        hud.setText(value);
        hud.setVisibility(View.VISIBLE);
        handler.removeCallbacks(clearFeedback);
        handler.postDelayed(clearFeedback, 1500);
    }

    private void toggle() {
        if (player == null || failed || loading) return;
        if (player.getPlaybackState() == Player.STATE_ENDED) player.seekTo(0);
        if (player.isPlaying()) player.pause();
        else player.play();
        showControls(true);
    }

    private void jump(long amount) {
        if (player == null || duration() < 1 || !player.isCurrentMediaItemSeekable()) return;
        player.seekTo(Math.max(0, Math.min(duration(), player.getCurrentPosition() + amount)));
        feedback(amount < 0 ? "−10 秒" : "+10 秒");
        if (controls) showControls(true);
    }

    private <T> void work(Callable<T> operation, LibraryActivity.Result<T> result,
                          LibraryActivity.Result<Exception> failure) {
        int requested = generation;
        tasks.execute(() -> {
            try {
                T value = operation.call();
                runOnUiThread(() -> {
                    if (!isDestroyed() && !isFinishing() && generation == requested) result.accept(value);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!isDestroyed() && !isFinishing() && generation == requested) failure.accept(error);
                });
            }
        });
    }

    private void load(int index) {
        generation++;
        danmuGeneration++;
        queueIndex = index;
        release();
        video = null;
        localSubtitle = null;
        sourceIndex = 0;
        lastSaved = -1;
        lastSync = android.os.SystemClock.elapsedRealtime();
        comments.load(Collections.emptyList());
        comments.settings(preferences.values);
        danmuStatus = demo ? "示例弹幕" : danmu == null ? "尚未配置弹幕服务" : "正在匹配弹幕…";
        seekClock.setText("00:00");
        totalClock.setText("00:00");
        seekBar.setProgress(0);
        seekBar.setSecondaryProgress(0);
        next.setVisibility(index + 1 < ids.size() ? View.VISIBLE : View.GONE);
        heading.setText(names.get(index));
        subheading.setText(subtitleHeading());
        setLoading(true);
        String titleId = ids.get(index);
        if (demo) {
            comments.load(Demo.comments());
            String asset = titleId.startsWith("night") ? "night" : titleId;
            String url = "asset:///" + asset + ".mp4";
            long position = restored(0);
            attach(new FnClient.Source("本地示例", url, false, Collections.emptyMap()), position, !stopped);
            return;
        }
        work(() -> client.video(titleId), result -> {
            video = result;
            attach(video.sources.get(0), restored(video.position), !stopped);
            if (danmu != null && preferences.values.getBoolean("comments.enabled", true)) autoDanmu();
            else if (danmu != null) danmuStatus = "弹幕已关闭";
        }, error -> showError(LibraryActivity.reason(error)));
    }

    private String subtitleHeading() {
        String show = series.get(queueIndex);
        int number = episodes.get(queueIndex);
        String label = number > 0 ? (show.isEmpty() ? "" : show + " · ") + "第 " + number + " 集" : "";
        if (demo) return label.isEmpty() ? "示例播放" : label + " · 示例播放";
        return label.isEmpty() ? "飞牛影视" : label;
    }

    private long restored(long server) {
        if (restorePosition >= 0) {
            long position = restorePosition;
            restorePosition = -1;
            return position;
        }
        return preferences.values.getLong(key() + ".pos", server);
    }

    private String key() {
        return preferences.mediaKey(client == null ? null : client.session, ids.get(queueIndex));
    }

    private void attach(FnClient.Source source, long position, boolean play) {
        release();
        setLoading(true);
        DefaultDataSource.Factory data = client == null ? new DefaultDataSource.Factory(this)
                : new DefaultDataSource.Factory(this, new OkHttpDataSource.Factory(client.streaming(source)));
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this).setEnableDecoderFallback(true);
        if (software) {
            renderers.setMediaCodecSelector((mimeType, secure, tunneling) -> {
                List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> available =
                        new ArrayList<>(MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling));
                available.sort((a, b) -> Boolean.compare(b.softwareOnly, a.softwareOnly));
                return available;
            });
        }
        player = new ExoPlayer.Builder(this, renderers).setMediaSourceFactory(new DefaultMediaSourceFactory(data)).build();
        player.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.setHandleAudioBecomingNoisy(true);
        videoView.setPlayer(player);
        if (videoView.getSubtitleView() != null) {
            videoView.getSubtitleView().setFractionalTextSize(preferences.values.getInt("play.subtitleSize", 5) / 100f);
        }
        MediaItem.Builder item = new MediaItem.Builder().setUri(source.url);
        if (source.hls) item.setMimeType(MimeTypes.APPLICATION_M3U8);
        item.setSubtitleConfigurations(subtitles());
        player.setMediaItem(item.build(), Math.max(0, position));
        player.setPlaybackSpeed(speed);
        player.addListener(new Player.Listener() {
            @Override public void onIsPlayingChanged(boolean playing) {
                refreshTransport();
                if (controls) showControls(true);
            }

            @Override public void onPlaybackStateChanged(int state) {
                if (failed) return;
                if (state == Player.STATE_BUFFERING) {
                    setLoading(true);
                } else if (state == Player.STATE_READY) {
                    setLoading(false);
                    if (duration() > 0 && player.getCurrentPosition() >= duration()) player.seekTo(0);
                } else if (state == Player.STATE_ENDED) {
                    save(true);
                    if (preferences.values.getBoolean("play.autonext", true) && queueIndex + 1 < ids.size()) {
                        switchTo(queueIndex + 1);
                    } else {
                        setLoading(false);
                        showControls(true);
                    }
                }
                refreshTransport();
            }

            @Override public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) comments.seek();
            }

            @Override public void onVideoSizeChanged(VideoSize videoSize) { resizeComments(); }

            @Override public void onPlayerError(PlaybackException error) {
                if (video != null && sourceIndex == 0 && video.sources.size() > 1) {
                    long resume = Math.max(0, player.getCurrentPosition());
                    handler.post(() -> {
                        if (isFinishing() || isDestroyed() || failed || video == null || sourceIndex != 0) return;
                        sourceIndex++;
                        attach(video.sources.get(sourceIndex), resume, !stopped);
                        feedback("已切换至备用播放源");
                    });
                    return;
                }
                String code = error.getErrorCodeName();
                String detail = code.contains("DECOD") ? "当前设备无法解码此视频。可以尝试更换播放源或解码模式。"
                        : code.contains("NETWORK") || code.contains("HTTP") ? "无法读取视频流，请检查网络或切换播放源。"
                        : "无法播放此视频。请重试或切换播放源。";
                showError(detail);
            }
        });
        player.setPlayWhenReady(play);
        player.prepare();
        resizeComments();
    }

    private List<MediaItem.SubtitleConfiguration> subtitles() {
        ArrayList<MediaItem.SubtitleConfiguration> result = new ArrayList<>();
        if (video != null) {
            for (org.json.JSONObject track : video.subtitles) {
                String mime = subtitleMime(Title.first(track, "format", "codec_name"));
                if (mime == null) continue;
                String title = Title.first(track, "title", "language", "guid");
                String language = Title.first(track, "language");
                MediaItem.SubtitleConfiguration.Builder config = new MediaItem.SubtitleConfiguration.Builder(
                        Uri.parse(client.subtitle(track.optString("guid")))).setMimeType(mime).setLabel(title);
                if (!language.isEmpty()) config.setLanguage(language);
                result.add(config.build());
            }
        }
        if (localSubtitle != null) {
            result.add(new MediaItem.SubtitleConfiguration.Builder(localSubtitle).setMimeType(localSubtitleMime)
                    .setLabel("本地字幕").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build());
        }
        return result;
    }

    private String subtitleMime(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.equals("vtt") || lower.equals("webvtt") || lower.endsWith(".vtt")) return MimeTypes.TEXT_VTT;
        if (lower.equals("srt") || lower.equals("subrip") || lower.endsWith(".srt")) return MimeTypes.APPLICATION_SUBRIP;
        if (lower.equals("ass") || lower.equals("ssa") || lower.endsWith(".ass") || lower.endsWith(".ssa")) return MimeTypes.TEXT_SSA;
        if (lower.equals("ttml") || lower.endsWith(".ttml")) return MimeTypes.APPLICATION_TTML;
        return null;
    }

    private void openSubtitle() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(pick, 31); }
        catch (android.content.ActivityNotFoundException error) { feedback("此设备未安装文件选择器"); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 31 || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String name = uri.getLastPathSegment();
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (RuntimeException ignored) {}
        String mime = subtitleMime(name == null ? "" : name);
        if (mime == null) { feedback("请选择 SRT、ASS、SSA、VTT 或 TTML 字幕"); return; }
        localSubtitle = uri;
        localSubtitleMime = mime;
        if (player != null) {
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build());
        }
        restart();
        feedback("已添加本地字幕");
    }

    private void refreshTransport() {
        boolean playing = player != null && player.isPlaying();
        playText.setText(playing ? "Ⅱ" : "▶");
        playText.setContentDescription(playing ? "暂停" : "播放");
    }

    private void setLoading(boolean value) {
        loading = value;
        failed = false;
        message.setVisibility(View.GONE);
        spinner.setVisibility(value && !pip ? View.VISIBLE : View.GONE);
        middle.setVisibility(value ? View.GONE : View.VISIBLE);
        refreshTransport();
    }

    private void showError(String detail) {
        failed = true;
        loading = false;
        spinner.setVisibility(View.GONE);
        middle.setVisibility(View.GONE);
        errorText.setText(detail);
        message.setVisibility(View.VISIBLE);
        showControls(true);
    }

    private void retry() {
        if (player == null) load(queueIndex);
        else restart();
    }

    private FnClient.Source activeSource() {
        if (!demo) return video == null ? null : video.sources.get(sourceIndex);
        String id = ids.get(queueIndex);
        return new FnClient.Source("本地示例", "asset:///" + (id.startsWith("night") ? "night" : id) + ".mp4",
                false, Collections.emptyMap());
    }

    private void restart() {
        FnClient.Source source = activeSource();
        if (source == null) return;
        long position = player == null ? 0 : player.getCurrentPosition();
        boolean play = player == null || player.getPlayWhenReady() || failed;
        attach(source, position, play && !stopped);
    }

    private void switchTo(int index) {
        if (index < 0 || index >= ids.size() || index == queueIndex) return;
        save(player != null && player.getPlaybackState() == Player.STATE_ENDED);
        load(index);
        showControls(true);
    }

    private void save(boolean ended) {
        if (player == null) return;
        long duration = duration();
        long position = ended ? 0 : player.getCurrentPosition();
        if (duration <= 0 || lastSaved == position || !ended && position == 0 && lastSaved < 0) return;
        lastSaved = position;
        preferences.values.edit().putLong(key() + ".pos", position).apply();
        FnClient.Video current = video;
        if (client != null && current != null) {
            FnClient connection = client;
            long reported = ended ? duration : position;
            progress.execute(() -> {
                try { connection.record(current, reported, duration); } catch (Exception ignored) {}
            });
        }
    }

    private void release() {
        if (player == null) return;
        videoView.setPlayer(null);
        player.release();
        player = null;
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("episode", queueIndex);
        if (player != null && duration() > 0) state.putLong("position", player.getCurrentPosition());
    }

    @Override protected void onStart() {
        super.onStart();
        stopped = false;
    }

    @Override protected void onStop() {
        super.onStop();
        stopped = true;
        if (player != null) {
            save(player.getPlaybackState() == Player.STATE_ENDED);
            player.pause();
        }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        tasks.shutdownNow();
        progress.shutdownNow();
        release();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (!locked) {
            save(player != null && player.getPlaybackState() == Player.STATE_ENDED);
            super.onBackPressed();
        } else {
            unlock.setVisibility(View.VISIBLE);
            feedback("点击锁图标解锁");
        }
    }

    private final class Touch implements View.OnTouchListener {
        private float x, y, startBrightness;
        private int startVolume, direction;
        private long startPosition, target;
        private boolean held;
        private final GestureDetector gestures = new GestureDetector(PlaybackActivity.this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(MotionEvent event) { return true; }
                    @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                        if (locked) {
                            unlock.setVisibility(unlock.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                        } else showControls(!controls);
                        return true;
                    }
                    @Override public boolean onDoubleTap(MotionEvent event) {
                        if (locked || player == null) return true;
                        float fraction = event.getX() / Math.max(1, stage.getWidth());
                        if (fraction < .37f) jump(-10000);
                        else if (fraction > .63f) jump(10000);
                        else toggle();
                        return true;
                    }
                    @Override public void onLongPress(MotionEvent event) {
                        if (locked || player == null || !player.isPlaying() || direction != 0) return;
                        held = true;
                        player.setPlaybackSpeed(2f);
                        feedback("2× 快速播放");
                    }
                });

        @Override public boolean onTouch(View view, MotionEvent event) {
            gestures.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    direction = 0;
                    held = false;
                    x = event.getX();
                    y = event.getY();
                    startPosition = player == null ? 0 : player.getCurrentPosition();
                    target = startPosition;
                    startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                    startBrightness = getWindow().getAttributes().screenBrightness;
                    if (startBrightness < 0) {
                        try {
                            startBrightness = android.provider.Settings.System.getInt(getContentResolver(),
                                    android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f;
                        } catch (Exception ignored) { startBrightness = .5f; }
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (locked || player == null || held) return true;
                    float dx = event.getX() - x, dy = event.getY() - y;
                    if (direction == 0 && Math.max(Math.abs(dx), Math.abs(dy)) > ui.dp(15)) {
                        direction = Math.abs(dx) > Math.abs(dy) ? 1 : x < stage.getWidth() * .5f ? 2 : 3;
                        handler.removeCallbacks(hide);
                    }
                    if (direction == 1 && duration() > 0 && player.isCurrentMediaItemSeekable()) {
                        double fraction = dx / Math.max(1, stage.getWidth());
                        long range = Math.min(180000, Math.max(10000, duration() / 3));
                        target = Math.max(0, Math.min(duration(), startPosition + (long) (fraction * range)));
                        feedback(time(target) + "  /  " + time(duration()));
                    } else if (direction == 2) {
                        float value = Math.max(.035f, Math.min(1f, startBrightness - 1.5f * dy / stage.getHeight()));
                        WindowManager.LayoutParams window = getWindow().getAttributes();
                        window.screenBrightness = value;
                        getWindow().setAttributes(window);
                        feedback("亮度  " + Math.round(value * 100) + "%");
                    } else if (direction == 3) {
                        int maximum = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                        int value = Math.max(0, Math.min(maximum,
                                startVolume - Math.round(1.5f * dy / stage.getHeight() * maximum)));
                        try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0); }
                        catch (SecurityException ignored) {}
                        feedback("音量  " + Math.round(value * 100f / maximum) + "%");
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (event.getActionMasked() == MotionEvent.ACTION_UP && direction == 0 && !held) {
                        view.performClick();
                    }
                    if (player != null && held) player.setPlaybackSpeed(speed);
                    if (event.getActionMasked() == MotionEvent.ACTION_UP && direction == 1 && player != null && !locked) {
                        player.seekTo(target);
                    }
                    if (controls && (direction != 0 || held)) showControls(true);
                    held = false;
                    direction = 0;
                    return true;
                default:
                    return true;
            }
        }
    }

    private void queue() {
        String[] choices = new String[ids.size()];
        for (int i = 0; i < choices.length; i++) {
            choices[i] = episodes.get(i) > 0 ? "第 " + episodes.get(i) + " 集   " + names.get(i) : names.get(i);
        }
        choose("选择剧集", choices, queueIndex, this::switchTo);
    }

    private void choose(String title, String[] options, int selection, LibraryActivity.Result<Integer> action) {
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(options, selection, (window, selected) -> {
                    window.dismiss();
                    action.accept(selected);
                    showControls(true);
                }).setNegativeButton("关闭", null).create();
        dialog.setOnDismissListener(window -> showControls(true));
        dialog.show();
        handler.removeCallbacks(hide);
    }

    private Dialog sheet(String title, LinearLayout content) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout shell = ui.column();
        ui.pad(shell, 18, 13, 18, 18);
        shell.setBackground(ui.filled(0xff171a26, 23, Ui.STROKE));
        LinearLayout header = ui.row();
        header.addView(ui.text(title, 20, Ui.TEXT, true));
        ui.stretch(header);
        header.addView(icon("close", "关闭设置", dialog::dismiss), size(37));
        shell.addView(header);
        ui.space(shell, 11);
        shell.addView(content, new LinearLayout.LayoutParams(-1, -2));
        scroll.addView(shell);
        dialog.setContentView(scroll);
        dialog.setOnDismissListener(window -> showControls(true));
        dialog.show();
        int height = getResources().getConfiguration().screenHeightDp;
        int width = getResources().getConfiguration().screenWidthDp;
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            window.setLayout(ui.dp(Math.min(width - 12, 500)), ui.dp(Math.min(height - 28, 530)));
            window.setDimAmount(.36f);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        handler.removeCallbacks(hide);
        return dialog;
    }

    private void option(LinearLayout body, String label, String current, Runnable action) {
        LinearLayout row = ui.row();
        ui.pad(row, 11, 9, 11, 9);
        TextView title = ui.text(label, 14, Ui.TEXT, true);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        if (!current.isEmpty()) {
            TextView value = ui.text(current, 12, Ui.MUTED, false);
            value.setSingleLine();
            value.setEllipsize(TextUtils.TruncateAt.END);
            value.setMaxWidth(ui.dp(128));
            row.addView(value);
            ui.gap(row, 7);
        }
        row.addView(ui.icon("right", Ui.ACCENT), new LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)));
        row.setMinimumHeight(ui.dp(41));
        ui.click(row, 0xff202333, 10, 0, action);
        body.addView(row, new LinearLayout.LayoutParams(-1, -2));
        ui.space(body, 6);
    }

    private void settings() {
        LinearLayout panel = ui.column();
        Dialog dialog = sheet("播放设置", panel);
        FnClient.Source source = activeSource();
        option(panel, "播放源", source == null ? "" : source.name, () -> { dialog.dismiss(); sources(); });
        option(panel, "播放速度", speedLabel(speed), () -> { dialog.dismiss(); speed(); });
        option(panel, "画面比例", RESIZE_NAMES[resizeIndex], () -> {
            dialog.dismiss();
            choose("画面比例", RESIZE_NAMES, resizeIndex, which -> {
                resizeIndex = which;
                preferences.values.edit().putInt("play.resize", which).apply();
                videoView.setResizeMode(RESIZE[which]);
                resizeComments();
            });
        });
        option(panel, "音轨", "", () -> { dialog.dismiss(); tracks(C.TRACK_TYPE_AUDIO); });
        option(panel, "字幕轨", "", () -> { dialog.dismiss(); tracks(C.TRACK_TYPE_TEXT); });
        option(panel, "加载本地字幕", "", () -> { dialog.dismiss(); openSubtitle(); });
        option(panel, "字幕字号", preferences.values.getInt("play.subtitleSize", 5) + "%", () -> {
            dialog.dismiss();
            String[] values = {"小", "适中", "大", "特大"};
            int[] sizes = {4, 5, 7, 9};
            int saved = preferences.values.getInt("play.subtitleSize", 5);
            int selected = saved == 4 ? 0 : saved == 7 ? 2 : saved == 9 ? 3 : 1;
            choose("字幕字号", values, selected, which -> {
                preferences.values.edit().putInt("play.subtitleSize", sizes[which]).apply();
                if (videoView.getSubtitleView() != null) videoView.getSubtitleView().setFractionalTextSize(sizes[which] / 100f);
            });
        });
        option(panel, "解码模式", software ? "系统软解优先" : "自动", () -> {
            dialog.dismiss();
            choose("解码模式", new String[]{"自动", "系统软解优先"}, software ? 1 : 0, which -> {
                software = which == 1;
                preferences.values.edit().putBoolean("play.software", software).apply();
                restart();
            });
        });
        boolean auto = preferences.values.getBoolean("play.autonext", true);
        option(panel, "自动连播", auto ? "已开启" : "已关闭", () -> {
            preferences.values.edit().putBoolean("play.autonext", !auto).apply();
            dialog.dismiss();
            feedback(auto ? "已关闭自动连播" : "已开启自动连播");
        });
    }

    private void sources() {
        if (video == null || video.sources.size() < 2) { feedback("当前只有一个播放源"); return; }
        String[] labels = new String[video.sources.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = video.sources.get(i).name;
        choose("选择播放源", labels, sourceIndex, which -> {
            if (which == sourceIndex) return;
            sourceIndex = which;
            restart();
        });
    }

    private String speedLabel(float value) { return String.format(Locale.ROOT, "%s×", Float.toString(value)); }

    private void speed() {
        float[] speeds = {.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f};
        String[] options = new String[speeds.length];
        int active = 2;
        for (int i = 0; i < speeds.length; i++) {
            options[i] = speedLabel(speeds[i]);
            if (speeds[i] == speed) active = i;
        }
        choose("播放速度", options, active, which -> {
            speed = speeds[which];
            if (player != null) player.setPlaybackSpeed(speed);
            feedback(speedLabel(speed));
        });
    }

    private void tracks(int type) {
        if (player == null) return;
        ArrayList<String> names = new ArrayList<>();
        ArrayList<TrackSelectionOverride> options = new ArrayList<>();
        boolean text = type == C.TRACK_TYPE_TEXT;
        int selected = player.getTrackSelectionParameters().disabledTrackTypes.contains(type) ? 0 : -1;
        names.add(text ? "关闭字幕" : "自动选择");
        options.add(null);
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != type) continue;
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i)) continue;
                Format format = group.getTrackFormat(i);
                String desc = format.label != null ? format.label : format.language != null
                        ? format.language : (text ? "字幕 " : "音轨 ") + names.size();
                if (!text && format.channelCount > 0) desc += " · " + format.channelCount + " 声道";
                if (group.isTrackSelected(i)) selected = names.size();
                names.add(desc);
                options.add(new TrackSelectionOverride(group.getMediaTrackGroup(), i));
            }
        }
        if (options.size() == 1) { feedback(text ? "没有可选字幕轨" : "没有其他音轨"); return; }
        choose(text ? "字幕轨" : "音轨", names.toArray(new String[0]), selected, which -> {
            if (player == null) return;
            TrackSelectionParameters.Builder update = player.getTrackSelectionParameters().buildUpon()
                    .clearOverridesOfType(type).setTrackTypeDisabled(type, text && which == 0);
            if (which > 0) update.setOverrideForType(options.get(which));
            player.setTrackSelectionParameters(update.build());
        });
    }

    private void toggleDanmu() {
        boolean enabled = !preferences.values.getBoolean("comments.enabled", true);
        preferences.values.edit().putBoolean("comments.enabled", enabled).apply();
        comments.settings(preferences.values);
        refreshDanmuButton();
        if (enabled && !demo && video != null && danmu != null
                && (danmuStatus.equals("弹幕已关闭") || danmuStatus.contains("失败"))) autoDanmu();
        feedback(enabled ? "弹幕已开启" : "弹幕已关闭");
        showControls(true);
    }

    private void refreshDanmuButton() {
        boolean enabled = preferences.values.getBoolean("comments.enabled", true);
        danmuText.setTextColor(enabled ? Ui.INK : Ui.TEXT);
        ui.click(danmuText, enabled ? Ui.ACCENT : 0xb4242533, 16, 0, this::toggleDanmu);
        danmuText.setContentDescription(enabled ? "关闭弹幕" : "开启弹幕");
    }

    private String queryName() {
        String title = series.get(queueIndex);
        return title.isEmpty() ? names.get(queueIndex) : title;
    }

    private String matchName() {
        int episode = episodes.get(queueIndex), season = seasons.get(queueIndex);
        return episode > 0 ? String.format(Locale.ROOT, "%s S%02dE%02d", queryName(), season > 0 ? season : 1, episode) : queryName();
    }

    private void autoDanmu() {
        if (danmu == null) return;
        String held = preferences.values.getString(key() + ".match", "");
        String filename = video == null ? "" : video.filename;
        String title = matchName();
        int requested = ++danmuGeneration;
        danmuStatus = "正在匹配弹幕…";
        work(() -> {
            if (!held.isEmpty()) return danmu.comments(held);
            DanmuClient.Choice choice = danmu.match(title);
            if (choice == null && !filename.isEmpty() && !filename.equals(title)) choice = danmu.match(filename);
            return choice == null ? null : danmu.comments(choice.id);
        }, result -> {
            if (requested != danmuGeneration) return;
            if (result == null) danmuStatus = "未自动匹配到弹幕，可手动搜索";
            else setComments(result);
        }, error -> {
            if (requested == danmuGeneration) danmuStatus = "弹幕加载失败，可重试或手动搜索";
        });
    }

    private void setComments(List<DanmuClient.Comment> items) {
        comments.load(items);
        danmuStatus = items.isEmpty() ? "所选来源暂无弹幕" : "已载入 " + items.size() + " 条弹幕";
    }

    private void danmuSettings() {
        LinearLayout panel = ui.column();
        TextView status = ui.text(danmuStatus, 13, Ui.MUTED, false);
        ui.pad(status, 2, 0, 2, 10);
        panel.addView(status);
        Dialog dialog = sheet("弹幕设置", panel);
        if (danmu != null && !demo) {
            option(panel, "手动搜索片源", "", () -> { dialog.dismiss(); manualSearch(queryName()); });
            option(panel, "重新自动匹配", "", () -> {
                preferences.values.edit().remove(key() + ".match").apply();
                autoDanmu();
                dialog.dismiss();
                feedback("正在重新匹配");
            });
        }
        slider(panel, "不透明度", "comments.opacity", 10, 100, 82, "%");
        slider(panel, "显示范围", "comments.area", 20, 100, 50, "%");
        slider(panel, "字体大小", "comments.size", 12, 34, 19, "");
        slider(panel, "时间偏移（正值延后）", "comments.offset", -120, 120, 0, " 秒");
        option(panel, "恢复弹幕默认设置", "", () -> {
            preferences.values.edit().remove("comments.opacity").remove("comments.area")
                    .remove("comments.size").remove("comments.offset").apply();
            comments.settings(preferences.values);
            dialog.dismiss();
            danmuSettings();
        });
    }

    private void slider(LinearLayout panel, String label, String key, int min, int max, int fallback, String unit) {
        LinearLayout row = ui.row();
        row.addView(ui.text(label, 13, Ui.TEXT, true));
        ui.stretch(row);
        int saved = preferences.values.getInt(key, fallback);
        TextView value = ui.text(saved + unit, 12, Ui.ACCENT, true);
        row.addView(value);
        ui.pad(row, 4, 9, 4, 0);
        panel.addView(row);
        SeekBar slider = new SeekBar(this);
        slider.setMax(max - min);
        slider.setProgress(saved - min);
        slider.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
        slider.setThumbTintList(ColorStateList.valueOf(Ui.ACCENT));
        slider.setContentDescription(label);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar view, int requested, boolean fromUser) {
                if (!fromUser) return;
                int next = min + requested;
                value.setText(next + unit);
                preferences.values.edit().putInt(key, next).apply();
                comments.settings(preferences.values);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        panel.addView(slider, new LinearLayout.LayoutParams(-1, ui.dp(38)));
    }

    private void manualSearch(String title) {
        EditText input = new EditText(this);
        input.setText(title);
        input.setSingleLine();
        input.selectAll();
        LinearLayout container = ui.column();
        ui.pad(container, 20, 7, 20, 0);
        container.addView(input);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("搜索弹幕片源").setView(container)
                .setNegativeButton("取消", null).setPositiveButton("搜索", (window, which) -> {
                    String query = input.getText().toString().trim();
                    if (!query.isEmpty()) manualAnime(query);
                }).create();
        dialog.show();
        handler.removeCallbacks(hide);
    }

    private void manualAnime(String query) {
        int requested = ++danmuGeneration;
        feedback("正在搜索弹幕片源…");
        work(() -> danmu.anime(query), results -> {
            if (requested != danmuGeneration) return;
            if (results.isEmpty()) {
                new AlertDialog.Builder(this).setTitle("没有找到片源").setMessage("试试原名、译名或更短的关键词。")
                        .setPositiveButton("重新搜索", (dialog, which) -> manualSearch(query))
                        .setNegativeButton("关闭", null).show();
                return;
            }
            showChoices("选择弹幕片源", results, choice -> manualEpisodes(choice.id));
        }, error -> feedback(LibraryActivity.reason(error)));
    }

    private void manualEpisodes(String id) {
        int requested = ++danmuGeneration;
        feedback("正在读取弹幕剧集…");
        work(() -> danmu.episodes(id), results -> {
            if (requested != danmuGeneration) return;
            if (results.isEmpty()) { feedback("该片源暂无剧集"); return; }
            showChoices("选择弹幕剧集", results, this::manualComments);
        }, error -> feedback(LibraryActivity.reason(error)));
    }

    private void showChoices(String title, List<DanmuClient.Choice> list, LibraryActivity.Result<DanmuClient.Choice> action) {
        String[] labels = new String[list.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = list.get(i).label;
        choose(title, labels, -1, which -> action.accept(list.get(which)));
    }

    private void manualComments(DanmuClient.Choice choice) {
        int requested = ++danmuGeneration;
        feedback("正在加载弹幕…");
        work(() -> danmu.comments(choice.id), results -> {
            if (requested != danmuGeneration) return;
            preferences.values.edit().putString(key() + ".match", choice.id).putBoolean("comments.enabled", true).apply();
            comments.settings(preferences.values);
            refreshDanmuButton();
            setComments(results);
            feedback(danmuStatus);
        }, error -> feedback(LibraryActivity.reason(error)));
    }
}


