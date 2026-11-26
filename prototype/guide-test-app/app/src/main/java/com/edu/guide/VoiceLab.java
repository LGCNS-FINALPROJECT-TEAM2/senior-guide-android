package com.edu.guide;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * 받아쓰기(STT) 시험 (11/26 우석): 떠 있는 「말하기」 버튼으로
 *  ① 녹음 모드(짧게 누름): 우리 앱이 직접 녹음(16kHz) → wav 저장 → 같은 소리를 폰에 있는 받아쓰기 엔진마다 넣어 봄(안드로이드 13+)
 *     PC(phone_stt.py)가 wav를 가져가 SenseVoice로도 받아써서 엔진끼리 비교
 *  ② 실시간 모드(길게 누름): 기본 엔진이 마이크로 바로 듣고 말하는 동안 글자를 화면에 보여 줌
 * 로그(태그 GUIDE): UTT|id|파일|길이ms|끝난이유|최대소리  ·  STT|id|엔진|ms|글자 또는 ERR 코드  ·  LIVE|엔진|ms|글자  ·  ENGINES|목록
 */
public class VoiceLab {

    interface Ui { void card(String big, String small); void state(String label, boolean busy); }

    static final String TAG = "GUIDE";
    static final int RATE = 16000;
    static final int SILENCE_MS = 1500, MAX_MS = 15000, MIN_SPEECH_MS = 300;

    private final Context ctx;
    private final Ui ui;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean recording = false, stopNow = false;
    private SpeechRecognizer live;

    VoiceLab(Context c, Ui ui) { this.ctx = c; this.ui = ui; }

    boolean busy() { return recording || live != null || ear != null || contWaiting; }

    boolean micAllowed() {
        return ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /** 폰에 있는 받아쓰기 엔진(삼성 · 구글 등) */
    List<ComponentName> engines() {
        List<ComponentName> out = new ArrayList<>();
        List<ResolveInfo> l = ctx.getPackageManager().queryIntentServices(new Intent(RecognitionService.SERVICE_INTERFACE), 0);
        for (ResolveInfo r : l) {
            String p = r.serviceInfo.packageName;
            // 시험 대상은 폰 내장(삼성 · 구글)만. 다른 앱(예: AI 앱)의 받아쓰기 서비스로는 소리를 보내지 않음
            if (p.contains("samsung") || p.startsWith("com.google.")) out.add(new ComponentName(p, r.serviceInfo.name));
        }
        StringBuilder sb = new StringBuilder();
        for (ComponentName c : out) sb.append(c.flattenToShortString()).append(',');
        boolean od = Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx);
        Log.i(TAG, "ENGINES|" + sb + "|ondevice=" + od + "|sdk=" + Build.VERSION.SDK_INT);
        return out;
    }

    static String shortName(ComponentName c) {
        if (c == null) return "ondevice";
        String p = c.getPackageName();
        if (p.contains("samsung")) return "samsung";
        if (p.equals("com.google.android.as")) return "google-ondevice";   // 구글 폰 안 인식(Private Compute Core)
        if (p.equals("com.google.android.tts")) return "google-tts";
        if (p.contains("google")) return "google";
        return p;
    }

    // ---------------------------------------------------------------- ① 녹음 모드
    void toggleRecord() {
        if (recording) { stopNow = true; return; }
        if (!micAllowed()) { ui.card("마이크 허용이 필요해요", "길잡이 앱을 열고 '마이크 허용'을 눌러 주세요"); return; }
        recording = true; stopNow = false;
        ui.state("다 말했어요", true);
        ui.card("말씀하세요", "다 말하면 버튼을 누르거나 1.5초 쉬세요");
        new Thread(this::recordLoop, "voice-rec").start();
    }

    private void recordLoop() {
        long id = System.currentTimeMillis();
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord ar;
        try {
            ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE));
            ar.startRecording();
        } catch (Exception e) {
            Log.i(TAG, "UTT|" + id + "|-|0|mic_error " + e.getClass().getSimpleName() + "|0");
            finishRec("마이크를 못 열었어요", e.getClass().getSimpleName());
            return;
        }
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        short[] buf = new short[RATE / 20]; // 50ms
        long t0 = System.currentTimeMillis(), lastLoud = 0;
        double floor = -1; int maxAmp = 0; boolean spoke = false; long speechMs = 0;
        String reason = "button";
        while (!stopNow) {
            int n = ar.read(buf, 0, buf.length);
            if (n <= 0) continue;
            long sum = 0; int peak = 0;
            ByteBuffer bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < n; i++) { short s = buf[i]; bb.putShort(s); sum += (long) s * s; peak = Math.max(peak, Math.abs(s)); }
            pcm.write(bb.array(), 0, n * 2);
            maxAmp = Math.max(maxAmp, peak);
            double rms = Math.sqrt(sum / (double) n);
            long now = System.currentTimeMillis();
            if (now - t0 < 300) { floor = floor < 0 ? rms : (floor + rms) / 2; continue; } // 처음 0.3초 = 주변 소리 크기
            boolean loud = rms > Math.max(500, floor * 3);                                    // 주변보다 3배 크면 말소리로 봄
            if (loud) { lastLoud = now; speechMs += 50; if (speechMs >= MIN_SPEECH_MS) spoke = true; }
            if (spoke && now - lastLoud > SILENCE_MS) { reason = "silence"; break; }
            if (now - t0 > MAX_MS) { reason = "max"; break; }
        }
        try { ar.stop(); } catch (Exception ignored) { }
        ar.release();
        byte[] raw = pcm.toByteArray();
        long durMs = raw.length / 2 * 1000L / RATE;
        File dir = new File(ctx.getFilesDir(), "voice");
        dir.mkdirs();
        File wav = new File(dir, "utt_" + id + ".wav");
        try (OutputStream o = new FileOutputStream(wav)) { o.write(wavHeader(raw.length)); o.write(raw); } catch (Exception e) { Log.w(TAG, "wav save", e); }
        Log.i(TAG, "UTT|" + id + "|" + wav.getName() + "|" + durMs + "|" + reason + "|" + maxAmp);
        if (maxAmp < 200) { finishRec("소리가 안 들어왔어요", "마이크가 막혔을 수 있어요(최대 소리 " + maxAmp + ")"); return; }
        main.post(() -> { ui.state("받아쓰는 중", true); ui.card("받아쓰는 중", (durMs / 100) / 10.0 + "초 녹음 · 폰 엔진에 넣어 보는 중"); });
        if (Build.VERSION.SDK_INT < 33) { Log.i(TAG, "STT|" + id + "|all|0|ERR need_android13"); finishRec("녹음 끝", "이 폰은 저장한 소리를 엔진에 넣을 수 없어요(PC에서 SenseVoice만)"); return; }
        List<ComponentName> es = engines();
        List<ComponentName> todo = new ArrayList<>(es);
        if (SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) todo.add(null);
        main.post(() -> runEngines(id, raw, todo, 0, new StringBuilder()));
    }

    private void finishRec(String big, String small) {
        recording = false;
        main.post(() -> { ui.state("말하기", false); ui.card(big, small); });
    }

    /** 같은 소리를 엔진 하나씩 차례로 (안드로이드 13+ EXTRA_AUDIO_SOURCE) */
    private void runEngines(long id, byte[] raw, List<ComponentName> todo, int i, StringBuilder shown) {
        if (i >= todo.size()) {
            recording = false;
            ui.state("말하기", false);
            ui.card("받아쓰기 끝", shown.length() == 0 ? "폰 엔진 결과 없음(PC에서 확인)" : shown.toString().trim());
            return;
        }
        ComponentName c = todo.get(i);
        String name = shortName(c);
        long t0 = System.currentTimeMillis();
        SpeechRecognizer sr;
        try {
            sr = c == null ? SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) : SpeechRecognizer.createSpeechRecognizer(ctx, c);
        } catch (Exception e) {
            Log.i(TAG, "STT|" + id + "|" + name + "|0|ERR create " + e.getClass().getSimpleName());
            runEngines(id, raw, todo, i + 1, shown); return;
        }
        final boolean[] done = {false};
        Runnable next = () -> { if (done[0]) return; done[0] = true; try { sr.destroy(); } catch (Exception ignored) { } runEngines(id, raw, todo, i + 1, shown); };
        main.postDelayed(() -> { if (!done[0]) { Log.i(TAG, "STT|" + id + "|" + name + "|" + (System.currentTimeMillis() - t0) + "|ERR timeout"); next.run(); } }, 20000);
        sr.setRecognitionListener(new Listener() {
            @Override public void onResults(Bundle b) {
                String txt = first(b);
                Log.i(TAG, "STT|" + id + "|" + name + "|" + (System.currentTimeMillis() - t0) + "|" + txt);
                shown.append(name).append(": ").append(txt).append('\n');
                next.run();
            }
            @Override public void onError(int err) {
                Log.i(TAG, "STT|" + id + "|" + name + "|" + (System.currentTimeMillis() - t0) + "|ERR " + err);
                next.run();
            }
        });
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            Intent it = baseIntent();
            it.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0]);
            it.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
            it.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            it.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE);
            sr.startListening(it);
            new Thread(() -> {
                try (OutputStream o = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    for (int p = 0; p < raw.length; p += 3200) { o.write(raw, p, Math.min(3200, raw.length - p)); Thread.sleep(20); } // 실제보다 5배 빠르게
                    o.write(new byte[RATE * 2]); // 끝에 1초 조용함
                } catch (Exception e) { Log.w(TAG, "pipe", e); }
            }, "voice-pipe").start();
        } catch (Exception e) {
            Log.i(TAG, "STT|" + id + "|" + name + "|0|ERR start " + e.getClass().getSimpleName());
            next.run();
        }
    }

    // ---------------------------------------------------------------- ② 실시간 모드
    void toggleLive() {
        if (live != null) { live.stopListening(); return; }
        if (recording) return;
        if (!micAllowed()) { ui.card("마이크 허용이 필요해요", "길잡이 앱을 열고 '마이크 허용'을 눌러 주세요"); return; }
        long t0 = System.currentTimeMillis();
        live = SpeechRecognizer.createSpeechRecognizer(ctx);
        ui.state("듣는 중(실시간)", true);
        ui.card("말씀하세요(실시간)", "말하는 동안 글자가 여기에 나와요");
        live.setRecognitionListener(new Listener() {
            @Override public void onPartialResults(Bundle b) { ui.card(first(b), "실시간 · 듣는 중"); }
            @Override public void onResults(Bundle b) {
                String txt = first(b);
                Log.i(TAG, "LIVE|default|" + (System.currentTimeMillis() - t0) + "|" + txt);
                endLive(txt, "실시간 받아쓰기 끝");
            }
            @Override public void onError(int err) {
                Log.i(TAG, "LIVE|default|" + (System.currentTimeMillis() - t0) + "|ERR " + err);
                endLive("못 들었어요 (오류 " + err + ")", "다시 길게 눌러 주세요");
            }
        });
        Intent it = baseIntent();
        it.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, (long) SILENCE_MS);   // 엔진이 안 따를 수 있음
        live.startListening(it);
    }

    private void endLive(String big, String small) {
        if (live != null) { try { live.destroy(); } catch (Exception ignored) { } live = null; }
        ui.state("말하기", false);
        ui.card(big, small);
    }

    void stopAll() { stopNow = true; if (live != null) endLive("멈춤", ""); if (ear != null || contWaiting) endListen(); }

    // ---------------------------------------------------------------- ③ 듣기 (11/27 A안: 실제로 쓸 방식)
    // 버튼 한 번 = 폰 내장 엔진이 바로 듣기(말하는 동안 글자). 끝 = 엔진이 스스로 / 「다 말했어요」 버튼 / 최대 15초
    // 엔진 순서: 구글 음성 인식 → 삼성 → 폰 기본. 못 알아들으면 「다시 한번」, 두 번째도 안 되면 예시 안내
    // 로그: LIVE|엔진|누른 뒤ms|글자 (PC phone_stt.py가 받음) · TIMING|엔진|준비|말시작|말끝|최종|끝난이유 (누른 뒤 ms)
    static final String GOOGLE_PKG = "com.google.android.tts";
    private SpeechRecognizer ear;
    private String earName = "";
    private long earT0, tReady, tBegin, tEnd;
    private String endReason = "engine";
    private int failCount = 0;
    private final Runnable maxTimer = () -> {
        endReason = "max";
        if (ear != null) ear.stopListening(); else if (this.contWaiting) finishSaid();
    };

    /** 쓸 엔진 순서: 구글 → 삼성 → null(폰 기본) */
    private List<ComponentName> engineOrder() {
        List<ComponentName> es = engines(), out = new ArrayList<>();
        for (ComponentName c : es) if (c.getPackageName().equals(GOOGLE_PKG)) out.add(c);
        for (ComponentName c : es) if (c.getPackageName().contains("samsung")) out.add(c);
        out.add(null);
        return out;
    }

    // ② 이어 듣기(11/27): 구글 엔진은 쉬면 바로 끊고 기다림 힌트 · 최소 시간(①)을 무시함 →
    //    엔진이 스스로 끊으면 곧바로 다시 듣고, 기다림(silenceMs, 기본 2초) 안에 말이 또 나오면 앞 글자에 이어 붙임
    static final long WAIT_MS = 2000;
    private final StringBuilder said = new StringBuilder();
    private ComponentName earComp;
    private boolean contWaiting = false;
    private int contCount = 0;
    private final Runnable contTimeout = () -> {
        if (!contWaiting) return;
        Log.i(TAG, "CONT|timeout|" + since());
        finishSaid();
    };

    private long waitMs() { return prefs().getLong("silenceMs", WAIT_MS); }

    void toggleListen() {
        if (ear != null || contWaiting) {                       // 「다 말했어요」
            endReason = "button";
            if (ear == null || contWaiting) { finishSaid(); return; }
            ear.stopListening(); ui.state("받아쓰는 중", true); return;
        }
        if (recording || live != null) return;
        if (!micAllowed()) { ui.card("마이크 허용이 필요해요", "길잡이 앱을 열고 '마이크 허용'을 눌러 주세요"); return; }
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { noEngine(); return; }
        earComp = null; earName = "";
        for (ComponentName c : engineOrder()) {
            try {   // 만들 수 있는 첫 엔진을 고름
                SpeechRecognizer t = c == null ? SpeechRecognizer.createSpeechRecognizer(ctx) : SpeechRecognizer.createSpeechRecognizer(ctx, c);
                t.destroy();
                earComp = c; earName = c == null ? "default" : shortName(c);
                break;
            } catch (Exception e) {
                Log.i(TAG, "LISTEN|create_fail|" + (c == null ? "default" : shortName(c)) + "|" + e.getClass().getSimpleName());
            }
        }
        if (earName.isEmpty()) { noEngine(); return; }
        said.setLength(0); contCount = 0; contWaiting = false;
        earT0 = System.currentTimeMillis(); tReady = tBegin = tEnd = -1; endReason = "engine";
        ui.state("다 말했어요", true);
        ui.card("말씀하세요", "다 말하면 「다 말했어요」를 누르세요");
        main.postDelayed(maxTimer, MAX_MS);
        startEar(false);
    }

    private void startEar(boolean cont) {
        try {
            ear = earComp == null ? SpeechRecognizer.createSpeechRecognizer(ctx) : SpeechRecognizer.createSpeechRecognizer(ctx, earComp);
        } catch (Exception e) {
            ear = null;
            if (said.length() > 0) finishSaid(); else { endListen(); noEngine(); }
            return;
        }
        contWaiting = cont;
        if (cont) main.postDelayed(contTimeout, waitMs());
        ear.setRecognitionListener(new Listener() {
            @Override public void onReadyForSpeech(Bundle p) { if (tReady < 0) tReady = since(); }
            @Override public void onBeginningOfSpeech() {
                if (tBegin < 0) tBegin = since();
                if (contWaiting) {   // 기다리는 동안 다시 말함 → 이어 듣기
                    contWaiting = false; main.removeCallbacks(contTimeout); contCount++;
                    Log.i(TAG, "CONT|resume|" + since());
                }
            }
            @Override public void onEndOfSpeech() { tEnd = since(); }
            @Override public void onPartialResults(Bundle b) {
                String t = first(b);
                if (!t.isEmpty()) ui.card(joinSaid(t), "듣는 중");
            }
            @Override public void onResults(Bundle b) {
                String txt = first(b);
                destroyEar();
                if (!txt.isEmpty()) said.append(said.length() > 0 ? " " : "").append(txt);
                if (!txt.isEmpty() && "engine".equals(endReason) && since() < MAX_MS) {   // 빈 결과면 이어 듣기 안 함(무한 반복 막기)
                    // 엔진이 스스로 끊음 → 기다림 시간 동안 다시 들어 봄
                    ui.card(said.toString(), "더 하실 말씀이 있으면 이어서 하세요");
                    main.post(() -> { if (ear == null && "engine".equals(endReason)) startEar(true); });
                } else {
                    finishSaid();
                }
            }
            @Override public void onError(int err) {
                destroyEar();
                if (said.length() > 0) {   // 이어 듣기에서 말이 없었음 등 → 지금까지 들은 걸로 끝
                    Log.i(TAG, "CONT|err|" + err + "|" + since());
                    finishSaid();
                    return;
                }
                main.removeCallbacks(contTimeout); contWaiting = false;
                Log.i(TAG, "LIVE|" + earName + "|" + since() + "|ERR " + err);
                Log.i(TAG, "TIMING|" + earName + "|" + tReady + "|" + tBegin + "|" + tEnd + "|" + since() + "|err" + err);
                endListen();
                failed(err);
            }
        });
        Intent it = baseIntent();
        long sil = waitMs(), min = prefs().getLong("minListenMs", 0);
        it.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, sil);  // 힌트(구글은 무시, 11/27)
        it.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, sil);
        if (min > 0) it.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, min);  // ① 시험: 구글은 무시
        ear.startListening(it);
        Log.i(TAG, "LISTEN|start|" + earName + "|cont=" + cont + "|wait=" + sil);
    }

    private String joinSaid(String t) { return said.length() == 0 ? t : said + " " + t; }

    private void destroyEar() { if (ear != null) { try { ear.destroy(); } catch (Exception ignored) { } ear = null; } }

    /** 지금까지 들은 글자로 끝냄 */
    private void finishSaid() {
        main.removeCallbacks(contTimeout); main.removeCallbacks(maxTimer);
        contWaiting = false;
        destroyEar();
        String txt = said.toString().trim();
        long fin = since();
        String why = endReason + (contCount > 0 ? "+이어" + contCount : "");
        if (txt.isEmpty()) {
            Log.i(TAG, "LIVE|" + earName + "|" + fin + "|ERR 7");
            Log.i(TAG, "TIMING|" + earName + "|" + tReady + "|" + tBegin + "|" + tEnd + "|" + fin + "|" + why);
            endListen(); failed(SpeechRecognizer.ERROR_NO_MATCH); return;
        }
        Log.i(TAG, "LIVE|" + earName + "|" + fin + "|" + txt);
        Log.i(TAG, "TIMING|" + earName + "|" + tReady + "|" + tBegin + "|" + tEnd + "|" + fin + "|" + why);
        failCount = 0;
        ui.state("말하기", false);
        ui.card("알아들었어요: " + txt, "화면을 보고 있어요. 잠깐만 기다려 주세요");
    }
    private long since() { return System.currentTimeMillis() - earT0; }

    private android.content.SharedPreferences prefs() { return ctx.getSharedPreferences("voice", Context.MODE_PRIVATE); }

    /** 시험용 설정(PC: am broadcast -a com.edu.guide.VOICE --es mode set --el min 6000 --el silence 2000). 0 또는 음수 = 기본값 */
    void setPrefs(long minMs, long silenceMs) {
        android.content.SharedPreferences.Editor e = prefs().edit();
        if (minMs >= 0) e.putLong("minListenMs", minMs);
        if (silenceMs > 0) e.putLong("silenceMs", silenceMs);
        e.apply();
        Log.i(TAG, "LISTEN|set|min=" + minMs + "|silence=" + silenceMs);
        ui.card("설정 바꿈", "최소 듣기 " + (minMs / 1000.0) + "초 · 기다림 " + (silenceMs > 0 ? silenceMs / 1000.0 + "초" : "그대로"));
    }

    private void endListen() {
        main.removeCallbacks(maxTimer); main.removeCallbacks(contTimeout);
        contWaiting = false;
        destroyEar();
        ui.state("말하기", false);
    }

    private void failed(int err) {
        if (err == SpeechRecognizer.ERROR_NO_MATCH || err == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            failCount++;
            if (failCount < 2) { ui.card("다시 한번 말씀해 주세요", "「말하기」를 누르고 천천히 말해 주세요"); return; }
            failCount = 0;
            ui.card("잘 못 알아들었어요", "이 중에서 골라 주세요: 기차표 · 배달 · 사진 보내기");
            return;
        }
        if (err == SpeechRecognizer.ERROR_NETWORK || err == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) { ui.card("인터넷이 안 돼요", "와이파이나 데이터를 확인해 주세요"); return; }
        if (err == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { ui.card("마이크 허용이 필요해요", "길잡이 앱을 열고 '마이크 허용'을 눌러 주세요"); return; }
        ui.card("잠깐 문제가 있어요 (오류 " + err + ")", "「말하기」를 다시 눌러 주세요");
    }

    private void noEngine() {
        Log.i(TAG, "LISTEN|no_engine");
        ui.card("이 폰은 말로 하기가 안 돼요", "이 중에서 골라 주세요: 기차표 · 배달 · 사진 보내기");
    }

    // ---------------------------------------------------------------- 도우미
    private Intent baseIntent() {
        Intent it = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        it.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        it.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR");
        it.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        it.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        return it;
    }

    private static String first(Bundle b) {
        ArrayList<String> r = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return r == null || r.isEmpty() ? "" : r.get(0).replace('\n', ' ').replace('|', '/');
    }

    private static byte[] wavHeader(int dataLen) {
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()).putInt(36 + dataLen).put("WAVE".getBytes()).put("fmt ".getBytes())
                .putInt(16).putShort((short) 1).putShort((short) 1).putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes()).putInt(dataLen);
        return h.array();
    }

    abstract static class Listener implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle p) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float v) { }
        @Override public void onBufferReceived(byte[] b) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onPartialResults(Bundle b) { }
        @Override public void onEvent(int t, Bundle p) { }
    }
}
