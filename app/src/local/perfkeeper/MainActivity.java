package local.perfkeeper;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static local.perfkeeper.Ui.*;

public class MainActivity extends Activity {
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Keeper keeper;
    private Keeper.Status last;
    private boolean visible;

    // views
    private TextView banner, pillText, heroTitle, heroSub, tInternal, tInternalSub, tSensor, tSensorSub, fan1Pct, fan2Pct;
    private View pillDot, heroDot;
    private FanView heroFan;
    private Bar heroProgress, fan1Bar, fan2Bar;
    private LinearLayout activityList;
    private Segmented segMode, segFan, segStd, segTarget, segEnv, segWait, segHot, segBoot;
    private FrameLayout overlay;
    private TextView advancedBtn;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        keeper = new Keeper(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.addView(buildMain());
        overlay = buildAdvanced();
        overlay.setVisibility(View.GONE);
        root.addView(overlay);
        setContentView(root);
        segMode.chips[1].post(() -> segMode.chips[1].requestFocus());
        // `am start -n local.perfkeeper/.MainActivity --ez advanced true` opens Advanced settings directly
        if (getIntent().getBooleanExtra("advanced", false)) overlay.post(this::showAdvanced);
    }

    // =====================================================================================
    // Layout
    // =====================================================================================

    private View buildMain() {
        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setPadding(dp(this, 32), dp(this, 18), dp(this, 32), dp(this, 10));

        // ----- header -----
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        FanView logo = new FanView(this, ACCENT);
        header.addView(logo, new LinearLayout.LayoutParams(dp(this, 26), dp(this, 26)));
        header.addView(gap(this, 10, 1));
        header.addView(text(this, "Performance Keeper", 20, TEXT, MEDIUM));
        header.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        LinearLayout pill = new LinearLayout(this);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(this, 12), dp(this, 6), dp(this, 14), dp(this, 6));
        pill.setBackground(rounded(SURFACE, dp(this, 20), dp(this, 1), OUTLINE));
        pillDot = new View(this);
        pillDot.setBackground(dot(this, GREY));
        pill.addView(pillDot, new LinearLayout.LayoutParams(dp(this, 8), dp(this, 8)));
        pill.addView(gap(this, 8, 1));
        pillText = text(this, "Connecting…", 13, TEXT_2, MEDIUM);
        pill.addView(pillText);
        header.addView(pill);
        main.addView(header);

        banner = text(this, "", 14, 0xFF1A1300, MEDIUM);
        banner.setPadding(dp(this, 14), dp(this, 10), dp(this, 14), dp(this, 10));
        banner.setBackground(rounded(AMBER, dp(this, 10), 0, 0));
        banner.setVisibility(View.GONE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(this, 12);
        main.addView(banner, blp);
        main.addView(gap(this, 1, 12));

        // ----- body: two columns -----
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        main.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));

        // left column
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setClipChildren(false);
        left.setPadding(dp(this, 4), dp(this, 4), dp(this, 8), dp(this, 4));
        left.addView(buildHero());
        left.addView(gap(this, 1, 12));

        segMode = new Segmented(this, new String[]{"Standard", "Performance", "Eco"},
                new String[]{"standard", "performance", "eco"}, v -> run(null, () -> keeper.request("request " + v)));
        left.addView(section("Brightness mode", "The mode your projector should stay in", segMode));

        segFan = new Segmented(this, new String[]{"Quiet", "Balanced", "Strong", "Max"},
                new String[]{"160", "190", "220", "255"}, v -> saveConfig("FAN_HOLD", v));
        left.addView(section("Fan noise in Performance", "Quieter means less cooling — it jumps to Max by itself if it gets hot", segFan));

        segStd = new Segmented(this, new String[]{"Auto", "Medium", "High"},
                new String[]{"0", "160", "200"}, v -> saveConfig("STD_FAN", v));
        left.addView(section("Fans in Standard & Eco", "Faster fans keep it cool, so Performance turns on instantly", segStd));

        LinearLayout actions = new LinearLayout(this);
        actions.setClipChildren(false);
        actions.addView(button(this, "Boost fans now", true, () -> run("Boosting fans for 2 minutes…", () -> keeper.request("boost 120"))));
        actions.addView(gap(this, 12, 1));
        advancedBtn = button(this, "Advanced settings", false, this::showAdvanced);
        actions.addView(advancedBtn);
        left.addView(actions);

        ScrollView leftScroll = new ScrollView(this);
        leftScroll.setClipChildren(false);
        leftScroll.setClipToPadding(false);
        leftScroll.addView(left);
        body.addView(leftScroll, new LinearLayout.LayoutParams(0, -1, 1.32f));
        body.addView(gap(this, 20, 1));

        // right column
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(0, dp(this, 4), 0, dp(this, 4));
        LinearLayout tiles = new LinearLayout(this);
        TextView[] t1 = tile(tiles, "Inside projector");
        tiles.addView(gap(this, 12, 1));
        TextView[] t2 = tile(tiles, "Air sensor");
        tInternal = t1[0]; tInternalSub = t1[1]; tSensor = t2[0]; tSensorSub = t2[1];
        right.addView(tiles);
        right.addView(gap(this, 12, 1));

        LinearLayout fans = card(this);
        fans.addView(overline(this, "Fans"));
        fans.addView(gap(this, 1, 10));
        TextView[] f1 = fanRow(fans, "Fan 1");
        fan1Pct = f1[0]; fan1Bar = (Bar) f1[1].getTag();
        fans.addView(gap(this, 1, 10));
        TextView[] f2 = fanRow(fans, "Fan 2");
        fan2Pct = f2[0]; fan2Bar = (Bar) f2[1].getTag();
        right.addView(fans);
        right.addView(gap(this, 12, 1));

        LinearLayout act = card(this);
        act.addView(overline(this, "Activity"));
        act.addView(gap(this, 1, 8));
        activityList = new LinearLayout(this);
        activityList.setOrientation(LinearLayout.VERTICAL);
        act.addView(activityList);
        right.addView(act, new LinearLayout.LayoutParams(-1, 0, 1f));
        body.addView(right, new LinearLayout.LayoutParams(0, -1, 1f));
        return main;
    }

    private View buildHero() {
        LinearLayout hero = new LinearLayout(this);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setBackground(rounded(SURFACE, dp(this, 18), dp(this, 1), OUTLINE));
        hero.setPadding(dp(this, 18), dp(this, 12), dp(this, 20), dp(this, 12));
        heroFan = new FanView(this, GREY);
        hero.addView(heroFan, new LinearLayout.LayoutParams(dp(this, 60), dp(this, 60)));
        hero.addView(gap(this, 18, 1));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        heroDot = new View(this);
        heroDot.setBackground(dot(this, GREY));
        titleRow.addView(heroDot, new LinearLayout.LayoutParams(dp(this, 10), dp(this, 10)));
        titleRow.addView(gap(this, 10, 1));
        heroTitle = text(this, "Checking projector…", 24, TEXT, MEDIUM);
        titleRow.addView(heroTitle);
        col.addView(titleRow);
        col.addView(gap(this, 1, 6));
        heroSub = text(this, "", 14, TEXT_2, REGULAR);
        heroSub.setLineSpacing(0, 1.15f);
        col.addView(heroSub);
        heroProgress = new Bar(this);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, dp(this, 6));
        plp.topMargin = dp(this, 10);
        heroProgress.setVisibility(View.GONE);
        col.addView(heroProgress, plp);
        hero.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        return hero;
    }

    private View section(String title, String helper, Segmented seg) {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setClipChildren(false);
        s.addView(text(this, title, 15, TEXT, MEDIUM));
        s.addView(gap(this, 1, 4));
        s.addView(text(this, helper, 12, TEXT_3, REGULAR));
        s.addView(gap(this, 1, 8));
        s.addView(seg, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(this, 10);
        s.setLayoutParams(lp);
        return s;
    }

    private TextView[] tile(LinearLayout parent, String label) {
        LinearLayout c = card(this);
        c.addView(overline(this, label));
        c.addView(gap(this, 1, 8));
        TextView value = text(this, "–", 34, TEXT, LIGHT);
        c.addView(value);
        c.addView(gap(this, 1, 6));
        TextView sub = text(this, "", 12, TEXT_3, REGULAR);
        c.addView(sub);
        parent.addView(c, new LinearLayout.LayoutParams(0, -2, 1f));
        return new TextView[]{value, sub};
    }

    private TextView[] fanRow(LinearLayout parent, String label) {
        LinearLayout row = new LinearLayout(this);
        row.addView(text(this, label, 13, TEXT_2, MEDIUM));
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView pct = text(this, "–", 13, TEXT, MEDIUM);
        row.addView(pct);
        parent.addView(row);
        parent.addView(gap(this, 1, 6));
        Bar bar = new Bar(this);
        parent.addView(bar, new LinearLayout.LayoutParams(-1, dp(this, 8)));
        TextView holder = new TextView(this);
        holder.setTag(bar);
        return new TextView[]{pct, holder};
    }

    private FrameLayout buildAdvanced() {
        FrameLayout dim = new FrameLayout(this);
        dim.setBackgroundColor(0xD9000000);
        LinearLayout panel = card(this);
        panel.setClipChildren(false);
        panel.setPadding(dp(this, 26), dp(this, 22), dp(this, 26), dp(this, 22));
        panel.addView(text(this, "Advanced settings", 20, TEXT, MEDIUM));
        panel.addView(gap(this, 1, 4));
        panel.addView(text(this, "Fine-tune when Performance is switched on. The defaults work well for most rooms.", 12, TEXT_3, REGULAR));
        panel.addView(gap(this, 1, 16));

        LinearLayout cols = new LinearLayout(this);
        cols.setClipChildren(false);
        LinearLayout c1 = new LinearLayout(this), c2 = new LinearLayout(this);
        c1.setOrientation(LinearLayout.VERTICAL); c2.setOrientation(LinearLayout.VERTICAL);
        c1.setClipChildren(false); c2.setClipChildren(false);
        cols.addView(c1, new LinearLayout.LayoutParams(0, -2, 1f));
        cols.addView(gap(this, 24, 1));
        cols.addView(c2, new LinearLayout.LayoutParams(0, -2, 1f));
        panel.addView(cols);

        segTarget = new Segmented(this, new String[]{"48°", "50°", "51°", "53°", "55°"},
                new String[]{"48", "50", "51", "53", "55"}, v -> saveConfig("TARGET_SYS", v));
        c1.addView(section("Cool inside to", "Lower waits longer, but Performance lasts longer", segTarget));
        segEnv = new Segmented(this, new String[]{"33°", "34°", "35°", "36°", "37°"},
                new String[]{"33", "34", "35", "36", "37"}, v -> saveConfig("TARGET_ENV", v));
        c1.addView(section("…and air sensor to", "XGIMI checks this sensor before allowing Performance", segEnv));
        segWait = new Segmented(this, new String[]{"2m", "3m", "4m", "6m", "8m"},
                new String[]{"120", "180", "240", "360", "480"}, v -> saveConfig("MAX_WAIT", v));
        c1.addView(section("Longest cool-down", "Try anyway after this long with fans at max", segWait));

        segHot = new Segmented(this, new String[]{"52°", "55°", "58°", "60°"},
                new String[]{"52", "55", "58", "60"}, v -> saveConfig("HOT_SYS", v));
        c2.addView(section("Fans to Max in Performance at", "Only used when Fan noise isn't Max", segHot));
        segBoot = new Segmented(this, new String[]{"On", "Off"}, new String[]{"on", "off"}, v -> {
            Keeper.prefs(this).edit().putBoolean("autostart", v.equals("on")).apply();
        });
        c2.addView(section("Start when projector turns on", "Keeps your mode after every power-on", segBoot));
        c2.addView(text(this, "Keeper", 15, TEXT, MEDIUM));
        c2.addView(gap(this, 1, 4));
        c2.addView(text(this, "Stopping puts the fans back to the projector's own speeds", 12, TEXT_3, REGULAR));
        c2.addView(gap(this, 1, 8));
        LinearLayout kRow = new LinearLayout(this);
        kRow.setClipChildren(false);
        kRow.addView(button(this, "Restart", false, () -> run("Restarting…", () -> { BootService.restart(this); Thread.sleep(5000); })),
                new LinearLayout.LayoutParams(0, -2, 1f));
        kRow.addView(gap(this, 10, 1));
        kRow.addView(button(this, "Stop", false, () -> run("Stopping — fans back to normal…", () -> keeper.stop())),
                new LinearLayout.LayoutParams(0, -2, 1f));
        c2.addView(kRow);

        panel.addView(gap(this, 1, 14));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.END);
        row.setClipChildren(false);
        row.addView(text(this, "Press Back to close", 12, TEXT_3, REGULAR), new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(button(this, "Done", true, this::hideAdvanced));
        panel.addView(row);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(this, 860), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        dim.addView(panel, lp);
        return dim;
    }

    private void showAdvanced() {
        overlay.setVisibility(View.VISIBLE);
        segTarget.chips[0].requestFocus();
        Map<String, String> cfg = last == null ? null : last.config;
        if (cfg != null) renderAdvanced(cfg);
    }

    private void hideAdvanced() {
        overlay.setVisibility(View.GONE);
        advancedBtn.requestFocus();
    }

    @Override public void onBackPressed() {
        if (overlay.getVisibility() == View.VISIBLE) hideAdvanced(); else super.onBackPressed();
    }

    // =====================================================================================
    // Behaviour
    // =====================================================================================

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        heroFan.setAnimating(true);
        bg.submit(() -> {
            try {
                keeper.connect(() -> banner("On the projector: tick “Always allow from this computer”, then press OK", AMBER));
                banner(null, 0);
                if (Keeper.prefs(this).getBoolean("autostart", true) && !keeper.running()) {
                    banner("Starting keeper…", ACCENT);
                    BootService.ensure(this);
                }
            } catch (Exception e) {
                banner("Can't reach the projector's debug service — turn on Network debugging in Developer options", RED);
            }
        });
        poll();
    }

    @Override protected void onPause() {
        super.onPause();
        visible = false;
        heroFan.setAnimating(false);
        ui.removeCallbacksAndMessages(null);
    }

    private void poll() {
        if (!visible) return;
        run(null, () -> { });
        ui.postDelayed(this::poll, 8000);
    }

    private interface Action { void go() throws Exception; }

    private void run(String busy, Action a) {
        if (busy != null) banner(busy, ACCENT);
        bg.submit(() -> {
            try {
                a.go();
                Keeper.Status s = keeper.status();
                ui.post(() -> {
                    last = s;
                    render();
                    CharSequence cur = banner.getText();
                    if (busy != null || (s.running && cur.toString().startsWith("Starting"))) banner(null, 0);
                });
            } catch (Exception e) {
                banner("Something went wrong: " + e.getMessage(), RED);
            }
        });
    }

    private void saveConfig(String key, String value) {
        if (last == null) return;
        Map<String, String> cfg = last.config;
        cfg.put(key, value);
        run("Saving…", () -> { keeper.writeConfig(cfg); BootService.restart(this); Thread.sleep(5000); });
    }

    private void banner(String msg, int color) {
        ui.post(() -> {
            if (msg == null) { banner.setVisibility(View.GONE); return; }
            banner.setText(msg);
            banner.setBackground(rounded(color, dp(this, 10), 0, 0));
            banner.setTextColor(color == ACCENT || color == RED ? Color.WHITE : 0xFF1A1300);
            banner.setVisibility(View.VISIBLE);
        });
    }

    // =====================================================================================
    // Rendering
    // =====================================================================================

    private void render() {
        Keeper.Status s = last;
        Map<String, String> cfg = s.config;
        int internal = num(s.sensors, "sys"), sensor = num(s.sensors, "s1");
        int f1 = pct(num(s.sensors, "fan0")), f2 = pct(num(s.sensors, "fan1"));
        boolean perfOn = "18".equals(s.mode) || "1".equals(s.mode);
        int targetSys = parse(cfg.get("TARGET_SYS"), 51), targetEnv = parse(cfg.get("TARGET_ENV"), 35);

        // pill
        pillDot.setBackground(dot(this, s.running ? GREEN : GREY));
        pillText.setText(s.running ? "Keeper running" : "Keeper stopped");

        // hero
        String title, sub;
        int color;
        boolean showProgress = false;
        float progress = 0;
        List<String> lines = new ArrayList<>();
        for (String l : s.log.split("\n")) if (l.length() > 20) lines.add(l);
        if (!s.running) {
            color = GREY; title = "Keeper is off";
            sub = "Your mode won't be kept. Open Advanced settings → Restart keeper.";
        } else if ("performance".equals(s.desired)) {
            if (perfOn) {
                color = GREEN; title = "Performance is ON";
                sub = "Fans at " + Math.max(f1, f2) + "% keep it cool · inside " + internal + "°C";
            } else if (lastMatching(lines, "resting", "Performance is ON", "fans to MAX", "requested")) {
                color = RED; title = "Too warm right now";
                sub = "Taking a short break with normal fans, then trying again automatically";
            } else {
                color = AMBER; title = "Cooling down…";
                sub = "Performance turns on by itself at ≤ " + targetSys + "°C inside and ≤ " + targetEnv + "°C at the air sensor";
                Integer start = coolingStart(lines);
                if (start != null && internal > 0 && start > targetSys) {
                    showProgress = true;
                    progress = (start - internal) / (float) (start - targetSys);
                }
            }
        } else if ("eco".equals(s.desired)) {
            color = TEAL; title = "Eco mode";
            sub = "Lowest brightness and power. Pick Performance any time.";
        } else {
            color = BLUE; title = "Standard mode";
            sub = "Balanced brightness and noise. Pick Performance any time.";
        }
        heroTitle.setText(title);
        heroSub.setText(sub);
        heroDot.setBackground(dot(this, color));
        heroFan.setColor(color);
        heroFan.setSpeed(Math.max(f1, f2));
        heroFan.setAnimating(visible);
        heroProgress.setVisibility(showProgress ? View.VISIBLE : View.GONE);
        heroProgress.set(progress, AMBER, GREEN);

        // tiles
        tInternal.setText(internal > 0 ? internal + "°" : "–");
        tInternal.setTextColor(internal >= targetSys + 6 ? RED : internal > targetSys ? AMBER : TEXT);
        tInternalSub.setText("Performance needs ≤ " + targetSys + "°");
        tSensor.setText(sensor > 0 ? sensor + "°" : "–");
        tSensor.setTextColor(sensor > targetEnv + 1 ? AMBER : TEXT);
        tSensorSub.setText("Performance needs ≤ " + targetEnv + "°");

        // fans
        fan1Pct.setText(f1 + "%");
        fan2Pct.setText(f2 + "%");
        fan1Bar.set(f1 / 100f, ACCENT, f1 >= 95 ? AMBER : ACCENT_2);
        fan2Bar.set(f2 / 100f, ACCENT, f2 >= 95 ? AMBER : ACCENT_2);

        // segmented selections
        segMode.select(s.desired);
        segFan.select(cfg.get("FAN_HOLD"));
        segStd.select(cfg.get("STD_FAN"));
        if (overlay.getVisibility() == View.VISIBLE) renderAdvanced(cfg);

        renderActivity(lines);
    }

    private void renderAdvanced(Map<String, String> cfg) {
        segTarget.select(cfg.get("TARGET_SYS"));
        segEnv.select(cfg.get("TARGET_ENV"));
        segWait.select(cfg.get("MAX_WAIT"));
        segHot.select(cfg.get("HOT_SYS"));
        segBoot.select(Keeper.prefs(this).getBoolean("autostart", true) ? "on" : "off");
    }

    private void renderActivity(List<String> lines) {
        activityList.removeAllViews();
        List<String> rev = new ArrayList<>(lines);
        Collections.reverse(rev);
        int shown = 0;
        for (String l : rev) {
            String[] f = friendly(l);
            if (f == null) continue;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            View d = new View(this);
            d.setBackground(dot(this, Integer.parseInt(f[2])));
            row.addView(d, new LinearLayout.LayoutParams(dp(this, 7), dp(this, 7)));
            row.addView(gap(this, 10, 1));
            TextView time = text(this, f[0], 12, TEXT_3, MEDIUM);
            row.addView(time, new LinearLayout.LayoutParams(dp(this, 44), -2));
            TextView msg = text(this, f[1], 13, shown == 0 ? TEXT : TEXT_2, REGULAR);
            msg.setSingleLine(true);
            msg.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(msg, new LinearLayout.LayoutParams(0, -2, 1f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(this, 9);
            activityList.addView(row, lp);
            if (++shown >= 7) break;
        }
        if (shown == 0) activityList.addView(text(this, "Nothing yet", 13, TEXT_3, REGULAR));
    }

    /** log line -> {HH:MM, plain-language message, color} or null to hide. */
    private static String[] friendly(String line) {
        if (line.length() < 21) return null;
        String time = line.substring(11, 16), m = line.substring(20);
        String msg; int c;
        Matcher t = Pattern.compile("internal (\\d+)°C").matcher(m);
        String temp = t.find() ? t.group(1) + "°C" : "";
        if (m.contains("Performance is ON"))                { msg = "Performance mode turned on"; c = GREEN; }
        else if (m.contains("fans to MAX to cool down"))    { msg = "Cooling down — fans at max" + (temp.isEmpty() ? "" : " (" + temp + ")"); c = AMBER; }
        else if (m.contains("switching Performance on"))   { msg = "Cool enough (" + temp + ") — turning on Performance"; c = BLUE; }
        else if (m.contains("refused"))                    { msg = "Still too warm — fans stay at max"; c = AMBER; }
        else if (m.contains("resting"))                    { msg = "Too warm — short break, will retry"; c = RED; }
        else if (m.contains("fans back to firmware"))      { msg = "Fans back to normal"; c = GREY; }
        else if (m.contains("you chose"))                  { msg = "You picked " + cap(word(m, "you chose ")) + " in the menu"; c = BLUE; }
        else if (m.startsWith("requested:"))               { msg = "Mode set to " + cap(m.substring(10).trim()); c = BLUE; }
        else if (m.contains("holding fans at"))            { msg = "Fans at " + pct(parse(word(m, "holding fans at "), 255)) + "% in Performance"; c = ACCENT; }
        else if (m.contains("manual boost finished"))      { msg = "Fan boost finished"; c = GREY; }
        else if (m.contains("manual boost"))               { msg = "Fans boosted to max for 2 min"; c = ACCENT; }
        else if (m.contains("— fans to max"))              { msg = "Getting warm (" + temp + ") — fans to max"; c = AMBER; }
        else if (m.contains("back to fan level"))          { msg = "Cooled down — fans back to your level"; c = GREY; }
        else if (m.contains("temperature too high"))       { msg = "Projector reported high temperature"; c = AMBER; }
        else if (m.contains("keeper started"))             { msg = "Keeper started"; c = GREY; }
        else if (m.contains("keeper stopped"))             { msg = "Keeper stopped"; c = GREY; }
        else if (m.contains(": fans at fan0="))            { msg = "Fans raised for Standard/Eco"; c = GREY; }
        else return null;
        return new String[]{time, msg, String.valueOf(c)};
    }

    // ---------- small helpers ----------
    private static boolean lastMatching(List<String> lines, String want, String... resets) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            String l = lines.get(i);
            if (l.contains(want)) return true;
            for (String r : resets) if (l.contains(r)) return false;
        }
        return false;
    }

    private static Integer coolingStart(List<String> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            String l = lines.get(i);
            if (l.contains("Performance is ON")) return null;
            if (l.contains("fans to MAX to cool down")) {
                Matcher m = Pattern.compile("internal (\\d+)°C").matcher(l);
                return m.find() ? Integer.parseInt(m.group(1)) : null;
            }
        }
        return null;
    }

    private static int num(String s, String k) {
        Matcher m = Pattern.compile("(?:^|\\s)" + k + "=(-?\\d+)").matcher(s == null ? "" : s);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static int pct(int raw) { return raw < 0 ? 0 : Math.round(raw * 100f / 255f); }

    private static int parse(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static String word(String s, String after) {
        int i = s.indexOf(after);
        if (i < 0) return "";
        String rest = s.substring(i + after.length()).trim();
        int sp = rest.indexOf(' ');
        return sp < 0 ? rest : rest.substring(0, sp);
    }

    private static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    @Override protected void onDestroy() {
        super.onDestroy();
        bg.submit(() -> keeper.close());
        bg.shutdown();
    }
}
