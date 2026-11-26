package com.edu.guide;

import android.accessibilityservice.AccessibilityService;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import android.graphics.drawable.GradientDrawable;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;

/**
 * 다른 앱 화면을 읽어서, 지금 단계에서 눌러야 할 버튼 위에 빨간 테두리와 말풍선을 그린다.
 * 원칙: 읽고 표시만 한다. 대신 누르지 않는다 (performAction 사용 안 함).
 */
public class GuideService extends AccessibilityService {

    static final String TAG = "GUIDE";
    static final String ACT_SET = "com.edu.guide.SET_MISSION";
    static final String ACT_FIND = "com.edu.guide.FIND";
    static final String ACT_STOP = "com.edu.guide.STOP";
    static final String ACT_DUMP = "com.edu.guide.DUMP";
    static final String ACT_NAVER = "com.edu.guide.NAVER";
    // PC(AI)가 정한 위치에 직접 표시: extras l,t,r,b(int, 화면 픽셀), say, sub. l<0 이면 말풍선 카드만
    static final String ACT_SHOW = "com.edu.guide.SHOW";
    static final String ACT_DUMP2 = "com.edu.guide.DUMP2";
    // 켜 두면 화면이 바뀔 때마다(0.3초 조용해지면, 최대 1.5초) 스스로 SNAP + DUMP2 를 로그로 내보냄
    static final String ACT_STREAM = "com.edu.guide.STREAM";

    private WindowManager wm;
    private OverlayView overlay;
    private ValueAnimator pulse;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Mission mission;
    private long missionDoneAt = 0;
    private TextView helpBtn; // "안 돼요" 버튼: 누르면 PC가 실패로 기록하고 다른 방법을 찾음
    private TextView micBtn;  // 「말하기」 버튼(받아쓰기 시험)
    private VoiceLab voice;
    static final String ACT_VOICE = "com.edu.guide.VOICE"; // extra mode = rec | live | engines | stop
    private boolean manual = false; // PC가 표시를 정하는 중이면 미션 평가가 덮어쓰지 않게
    private boolean stream = false;
    private long burstStart = 0;
    private long lastTyping = 0, lastScroll = 0;
    private int snapSeq = 0;
    private final Runnable snap = this::snap;

    private final Runnable evaluate = this::evaluate;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (ACT_SET.equals(a)) {
                String json = i.getStringExtra("json");
                try {
                    Mission.parse(json);
                    Mission.save(c, json);
                    Log.i(TAG, "mission set: " + json);
                } catch (Exception e) {
                    Log.w(TAG, "bad mission json", e);
                }
                reload();
            } else if (ACT_FIND.equals(a)) {
                String q = i.getStringExtra("q");
                if (q != null) Mission.save(c, Mission.singleJson(q));
                reload();
            } else if (ACT_STOP.equals(a)) {
                manual = false;
                if (helpBtn != null) helpBtn.setVisibility(View.GONE);
                Mission.clear(c);
                reload();
            } else if (ACT_SHOW.equals(a)) {
                manual = true;
                if (helpBtn != null) helpBtn.setVisibility(View.VISIBLE);
                Mission.clear(c);
                mission = null;
                String say = i.getStringExtra("say");
                String sub = i.getStringExtra("sub");
                int l = i.getIntExtra("l", -1);
                if (l < 0) {
                    overlay.showBanner(say == null ? "" : say, sub == null ? "" : sub);
                } else {
                    Rect r = new Rect(l, i.getIntExtra("t", 0), i.getIntExtra("r", 0), i.getIntExtra("b", 0));
                    overlay.show(r, say == null ? "" : say, sub == null ? "" : sub);
                }
                Log.i(TAG, "SHOW " + l + " " + say);
            } else if (ACT_VOICE.equals(a) && voice != null) {
                String m = i.getStringExtra("mode");
                if ("listen".equals(m)) voice.toggleListen();
                else if ("set".equals(m)) voice.setPrefs(i.getLongExtra("min", -1), i.getLongExtra("silence", -1));
                else if ("live".equals(m)) voice.toggleLive();
                else if ("engines".equals(m)) voice.engines();
                else if ("stop".equals(m)) voice.stopAll();
                else voice.toggleRecord();
            } else if (ACT_DUMP2.equals(a)) {
                dump2();
            } else if (ACT_STREAM.equals(a)) {
                stream = i.getBooleanExtra("on", true);
                burstStart = System.currentTimeMillis();
                if (stream) handler.post(snap);
                Log.i(TAG, "STREAM " + stream);
            } else if (ACT_DUMP.equals(a)) {
                dump();
            } else if (ACT_NAVER.equals(a)) {
                manual = false;
                Mission.save(c, MainActivity.NAVER_ROUTE);
                reload();
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlay = new OverlayView(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        wm.addView(overlay, lp);

        helpBtn = new TextView(this);
        helpBtn.setText("안 돼요");
        helpBtn.setTextSize(22);
        helpBtn.setTextColor(0xFFFFFFFF);
        helpBtn.setPadding(48, 28, 48, 28);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEE444444);
        bg.setCornerRadius(60);
        helpBtn.setBackground(bg);
        helpBtn.setVisibility(View.GONE);
        helpBtn.setOnClickListener(v -> {
            Log.i(TAG, "HELP|" + System.currentTimeMillis());
            overlay.showBanner("알겠어요. 다른 방법을 찾아볼게요", "잠깐만 기다려 주세요");
        });
        WindowManager.LayoutParams hp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        hp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        hp.x = 16;
        hp.y = 420; // 화면 가운데보다 조금 아래, 왼쪽 끝
        wm.addView(helpBtn, hp);

        // 11/25 밤 우석: 누르면 안내 글자는 바로 없어져야 함. 앱에 따라 클릭 이벤트가 안 와서(코레일)
        // 1픽셀 창으로 「화면 어딘가를 만졌다」만 받음(FLAG_WATCH_OUTSIDE_TOUCH, 다른 앱 위 좌표는 안드로이드가 0으로 가림)
        View touchWatch = new View(this);
        touchWatch.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_OUTSIDE) onUserTouch();
            return false;
        });
        WindowManager.LayoutParams tp = new WindowManager.LayoutParams(1, 1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        tp.gravity = Gravity.TOP | Gravity.START;
        wm.addView(touchWatch, tp);

        // 11/26 우석: 받아쓰기 시험용 떠 있는 「말하기」 버튼 (짧게 = 녹음 후 엔진 비교, 길게 = 실시간)
        micBtn = new TextView(this);
        micBtn.setText("말하기");
        micBtn.setTextSize(20);
        micBtn.setTextColor(0xFFFFFFFF);
        micBtn.setPadding(44, 30, 44, 30);
        GradientDrawable mbg = new GradientDrawable();
        mbg.setColor(0xEE1565C0);
        mbg.setCornerRadius(80);
        micBtn.setBackground(mbg);
        voice = new VoiceLab(this, new VoiceLab.Ui() {
            @Override public void card(String big, String small) { manual = true; overlay.showBanner(big, small); }
            @Override public void state(String label, boolean busy) {
                micBtn.setText(label);
                ((GradientDrawable) micBtn.getBackground()).setColor(busy ? 0xEED32F2F : 0xEE1565C0);
            }
        });
        // 11/27 A안: 한 번 누름 = 폰 내장 엔진으로 듣기. 녹음 비교(rec) · 옛 실시간(live)은 PC 명령(VOICE mode)으로만
        micBtn.setOnClickListener(v -> voice.toggleListen());
        WindowManager.LayoutParams mp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        mp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        mp.x = 16;
        mp.y = 300;
        wm.addView(micBtn, mp);
        voice.engines(); // 폰에 있는 받아쓰기 엔진을 로그로(ENGINES|...)

        pulse = ValueAnimator.ofFloat(0f, 1f, 0f);
        pulse.setDuration(1200);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.addUpdateListener(v -> overlay.setPulse((float) v.getAnimatedValue()));
        pulse.start();

        IntentFilter f = new IntentFilter();
        f.addAction(ACT_SET);
        f.addAction(ACT_FIND);
        f.addAction(ACT_STOP);
        f.addAction(ACT_DUMP);
        f.addAction(ACT_NAVER);
        f.addAction(ACT_SHOW);
        f.addAction(ACT_DUMP2);
        f.addAction(ACT_STREAM);
        f.addAction(ACT_VOICE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(receiver, f);
        }
        reload();
        Log.i(TAG, "service connected");
    }

    private void reload() {
        mission = Mission.load(this);
        missionDoneAt = 0;
        schedule();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getPackageName() != null && getPackageName().contentEquals(e.getPackageName())) return;
        if (stream) {
            // "화면이 멈추면 판단": 변화가 0.4초 끊기면 보냄. 글자 입력 중(마지막 입력 뒤 2초)·스크롤 중엔 보내지 않음
            long now = System.currentTimeMillis();
            int type = e.getEventType();
            if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                lastTyping = now;
                if (manual) overlay.showBanner("천천히 쓰세요", "다 쓰시면 '검색'이나 '완료'를 눌러 주세요");
            }
            if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) lastScroll = now;
            if (burstStart == 0) burstStart = now;
            handler.removeCallbacks(snap);
            boolean busy = now - lastTyping < 2000 || now - lastScroll < 500;
            if (!busy && now - burstStart > 1500) handler.post(snap);   // 계속 움직이는 화면(광고 등)도 1.5초마다는 보냄
            else handler.postDelayed(snap, 400);
        }
        if (manual && e.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            onUserTouch(); // 누르자마자 안내 글자 · 동그라미 지움(11/25 밤 우석: 계속 떠 있을 이유 없음)
        }
        if (e.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            // 11/25 밤: 사람이 실제로 누른 칸 → PC 판단기가 「안내대로 눌렀나」를 앎(경험을 확인된 성공으로만 쌓음)
            AccessibilityNodeInfo src = e.getSource();
            String lab = describe(src).trim();
            if (lab.isEmpty()) lab = textUnder(src, 0).trim();
            if (lab.isEmpty() && e.getText() != null && !e.getText().isEmpty()) lab = String.valueOf(e.getText()).replace("[", "").replace("]", "");
            Rect cr = new Rect();
            if (src != null) src.getBoundsInScreen(cr);
            Log.i(TAG, "CLICK|" + System.currentTimeMillis() + "|" + e.getPackageName() + "|"
                    + cr.left + "," + cr.top + "," + cr.right + "," + cr.bottom + "|" + lab.replace('\n', ' '));
        }
        if (mission == null || mission.done()) return;

        if (e.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            AccessibilityNodeInfo src = e.getSource();
            String clicked = describe(src) + " " + String.valueOf(e.getText());
            Mission.Step st = mission.currentStep();
            if (st != null && matches(clicked, st.keywords)) {
                Log.i(TAG, "step " + mission.current + " clicked: " + clicked.trim());
                advance();
            }
        }
        schedule();
    }

    private long lastTouchLog = 0;

    /** 사람이 화면을 만짐 → PC가 띄운 안내는 바로 지우고, PC에 「만졌다」를 알림(TOUCH 로그) */
    private void onUserTouch() {
        if (!manual || overlay == null) return;
        overlay.clear(); // 「안 돼요」 버튼은 그대로(그 버튼을 누르는 순간 숨기면 눌리지 않음)
        long now = System.currentTimeMillis();
        if (now - lastTouchLog > 300) { lastTouchLog = now; Log.i(TAG, "TOUCH|" + now); }
    }

    private void schedule() {
        handler.removeCallbacks(evaluate);
        handler.postDelayed(evaluate, 250);
    }

    private void advance() {
        mission.current++;
        Mission.saveStep(this, mission.current);
        if (mission.done()) {
            missionDoneAt = System.currentTimeMillis();
            Log.i(TAG, "mission done");
        }
    }

    private void evaluate() {
        if (overlay == null || manual) return;
        if (mission == null) {
            overlay.clear();
            return;
        }
        if (mission.done()) {
            if (System.currentTimeMillis() - missionDoneAt < 4000) {
                overlay.showBanner("다 했어요! 잘하셨어요 👏", mission.title);
                handler.postDelayed(evaluate, 4000);
            } else {
                overlay.clear();
            }
            return;
        }

        Mission.Step st = mission.currentStep();

        // 안내만 하는 단계: 다음 단계 버튼이 보일 때까지 말풍선 카드만 띄운다
        if (st.keywords.isEmpty()) {
            Mission.Step nx = mission.nextStep();
            Rect next = nx == null ? null : find(nx.keywords);
            if (next != null) {
                Log.i(TAG, "info-step " + mission.current + " done (next target visible)");
                advance();
                schedule();
                return;
            }
            overlay.showBanner(st.say, mission.title + " · " + (mission.current + 1) + "/" + mission.steps.size() + "단계");
            Log.i(TAG, "step " + mission.current + " info: " + st.say);
            return;
        }

        // 다음 단계 버튼이 이미 보이면 (사용자가 이미 해냈거나 클릭 감지가 안 된 경우) 다음 단계로 넘어간다
        Rect r = null;
        if (mission.nextStep() != null && !mission.nextStep().keywords.isEmpty()) {
            Rect next = find(mission.nextStep().keywords);
            if (next != null) {
                Log.i(TAG, "auto-advance from step " + mission.current);
                advance();
                st = mission.currentStep();
                r = next;
            }
        }
        if (r == null) r = find(st.keywords);

        // 지금 버튼이 사라졌고 다음이 '안내만 하는 단계'면, 화면이 넘어간 것으로 보고 다음 단계로
        if (r == null && mission.nextStep() != null && mission.nextStep().keywords.isEmpty()) {
            Log.i(TAG, "step " + mission.current + " gone, moving to info step");
            advance();
            schedule();
            return;
        }

        String progress = mission.title + " · " + (mission.current + 1) + "/" + mission.steps.size() + "단계";
        if (r != null) {
            overlay.show(r, st.say, progress);
            Log.i(TAG, "step " + mission.current + " target " + st.keywords + " at " + r.toShortString());
        } else {
            overlay.showBanner("찾는 중: " + st.keywords.get(0),
                    "화면을 위아래로 움직여 보세요 · " + progress);
            Log.i(TAG, "step " + mission.current + " target " + st.keywords + " not found");
        }
    }

    /** 모든 창(키보드·팝업 포함)에서 글자나 설명이 맞는 것을 찾아, 누를 수 있는 부모의 위치를 돌려준다. */
    private Rect find(List<String> keywords) {
        Rect best = null;
        long bestArea = Long.MAX_VALUE;
        Rect screen = new Rect();
        List<AccessibilityWindowInfo> windows = getWindows();
        for (AccessibilityWindowInfo w : windows) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) continue;
            if (root.getPackageName() != null && getPackageName().contentEquals(root.getPackageName())) continue;
            ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
            q.add(root);
            while (!q.isEmpty()) {
                AccessibilityNodeInfo n = q.poll();
                if (n == null) continue;
                for (int i = 0; i < n.getChildCount(); i++) { AccessibilityNodeInfo ch = n.getChild(i); if (ch != null) q.add(ch); }
                if (!n.isVisibleToUser()) continue;
                if (n.isEditable()) continue; // 입력 칸 안의 회색 안내 글자는 버튼이 아님
                if (!matches(describe(n), keywords)) continue;
                AccessibilityNodeInfo t = clickableAncestor(n);
                t.getBoundsInScreen(screen);
                if (screen.width() <= 0 || screen.height() <= 0) continue;
                long area = (long) screen.width() * screen.height();
                if (area < bestArea) {
                    bestArea = area;
                    best = new Rect(screen);
                }
            }
        }
        return best;
    }

    private static AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 4 && cur != null; i++) {
            if (cur.isClickable()) return cur;
            cur = cur.getParent();
        }
        return n;
    }

    /** 누를 칸 자체에 글자가 없을 때(글자는 안쪽 칸에) 안쪽 글자를 모음. 너무 길면 자름 */
    private static String textUnder(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 3) return "";
        StringBuilder sb = new StringBuilder(describe(n));
        for (int i = 0; i < n.getChildCount() && sb.length() < 60; i++) sb.append(textUnder(n.getChild(i), depth + 1));
        return sb.length() > 60 ? sb.substring(0, 60) : sb.toString();
    }

    private static String describe(AccessibilityNodeInfo n) {
        if (n == null) return "";
        StringBuilder sb = new StringBuilder();
        if (n.getText() != null) sb.append(n.getText()).append(' ');
        if (n.getContentDescription() != null) sb.append(n.getContentDescription()).append(' ');
        return sb.toString();
    }

    private static boolean matches(String text, List<String> keywords) {
        String t = text.toLowerCase(Locale.ROOT).replace(" ", "");
        if (t.isEmpty()) return false;
        for (String k : keywords) {
            if (t.contains(k.toLowerCase(Locale.ROOT).replace(" ", ""))) return true;
        }
        return false;
    }

    /** 미션 만들 때 쓰려고, 지금 화면의 글자·설명을 로그로 남긴다. */
    private void dump() {
        Rect r = new Rect();
        for (AccessibilityWindowInfo w : getWindows()) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) continue;
            ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
            q.add(root);
            while (!q.isEmpty()) {
                AccessibilityNodeInfo n = q.poll();
                if (n == null) continue;
                for (int i = 0; i < n.getChildCount(); i++) { AccessibilityNodeInfo ch = n.getChild(i); if (ch != null) q.add(ch); }
                String d = describe(n).trim();
                if (d.isEmpty() || !n.isVisibleToUser()) continue;
                n.getBoundsInScreen(r);
                Log.i(TAG, "DUMP|" + root.getPackageName() + "|" + n.getClassName() + "|click=" + n.isClickable()
                        + "|checked=" + n.isChecked() + "|" + r.toShortString() + "|" + d.replace('\n', ' '));
            }
        }
        Log.i(TAG, "DUMP|end");
    }

    /** AI용: 앞에 있는 앱 창만, 누를 수 있는 부모 위치와 입력 칸 여부까지. DUMP2|pkg|click|edit|누를곳 위치|글자 */
    private void snap() {
        if (!stream) return;
        long wait = 2000 - (System.currentTimeMillis() - lastTyping);
        if (wait > 0) { // 아직 쓰는 중일 수 있음 → 마지막 입력 뒤 2초까지 미룸
            handler.removeCallbacks(snap);
            handler.postDelayed(snap, wait);
            return;
        }
        long evt = burstStart;
        burstStart = 0;
        AccessibilityNodeInfo act = getRootInActiveWindow();
        CharSequence ap = act == null ? "" : act.getPackageName();
        Log.i(TAG, "SNAP|" + (++snapSeq) + "|" + evt + "|" + System.currentTimeMillis() + "|" + ap);
        dump2();
    }

    private void dump2() {
        // 11/27 폰 시험: 코레일 「열차 조회」 → 목록으로 넘어가도 화면 바뀜 신호가 안 와서 안드로이드 접근성 캐시에 첫 화면이 남음
        // → 목록이 떠 있는데 첫 화면 글자를 75초 동안 보냄(uiautomator를 한 번 돌리면 풀림). 읽기 전에 캐시를 비우고 창 뿌리를 새로 고침
        if (Build.VERSION.SDK_INT >= 33) clearCache();
        Rect r = new Rect();
        AccessibilityNodeInfo top = null;
        for (AccessibilityWindowInfo w : getWindows()) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION && w.getType() != AccessibilityWindowInfo.TYPE_SYSTEM) continue;
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) continue;
            CharSequence pkg = root.getPackageName();
            if (pkg != null && (getPackageName().contentEquals(pkg) || "com.android.systemui".contentEquals(pkg))) continue;
            ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
            q.add(root);
            while (!q.isEmpty()) {
                AccessibilityNodeInfo n = q.poll();
                if (n == null) continue;
                for (int i = 0; i < n.getChildCount(); i++) { AccessibilityNodeInfo ch = n.getChild(i); if (ch != null) q.add(ch); }
                if (!n.isVisibleToUser()) continue;
                String d = describe(n).trim();
                if (d.isEmpty() && n.getHintText() != null && n.isEditable()) d = "(입력 칸) " + n.getHintText();
                // 11/27 DUMP5: 비밀번호 칸은 글자가 비어 있어도 기록하고, 적힌 글자(가려진 점 포함)는 절대 남기지 않음
                boolean pwd = n.isPassword();
                if (pwd) d = "(비밀번호 칸)";
                if (d.isEmpty()) continue;
                AccessibilityNodeInfo t = clickableAncestor(n);
                t.getBoundsInScreen(r);
                if (r.width() <= 0 || r.height() <= 0) continue;
                // 11/25 밤 DUMP3: 누를 칸의 resource-id · 클래스 이름도(화면 지문을 글자 대신 구조로)
                String rid = t.getViewIdResourceName() == null ? "" : t.getViewIdResourceName();
                String cls = t.getClassName() == null ? "" : t.getClassName().toString();
                cls = cls.substring(cls.lastIndexOf('.') + 1);
                // 11/27 DUMP4: 「선택됨」(지금 고른 탭 · 칩)도. 글자 칸부터 누를 칸까지(최대 5칸 위) 중 하나라도 isSelected면 true
                boolean sel = false;
                AccessibilityNodeInfo p = n;
                for (int k = 0; p != null && k < 6; k++) {
                    if (p.isSelected()) { sel = true; break; }
                    if (p.equals(t)) break;
                    p = p.getParent();
                }
                // DUMP5 = DUMP4 + 비밀번호 칸 여부(selected 다음). DUMP5|pkg|click|edit|l,t,r,b|rid|cls|selected|password|text
                Log.i(TAG, "DUMP5|" + pkg + "|" + t.isClickable() + "|" + n.isEditable() + "|"
                        + r.left + "," + r.top + "," + r.right + "," + r.bottom + "|" + rid.replace('|', '/') + "|" + cls + "|" + sel + "|" + pwd + "|" + d.replace('\n', ' '));
            }
        }
        Log.i(TAG, "DUMP2|end");
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
        if (pulse != null) pulse.cancel();
        if (overlay != null && wm != null) wm.removeView(overlay);
        if (helpBtn != null && wm != null) wm.removeView(helpBtn);
        if (voice != null) voice.stopAll();
        if (micBtn != null && wm != null) wm.removeView(micBtn);
        super.onDestroy();
    }
}
