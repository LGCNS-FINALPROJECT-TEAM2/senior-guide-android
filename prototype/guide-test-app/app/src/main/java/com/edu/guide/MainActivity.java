package com.edu.guide;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** 시제품 조작 화면: 접근성 켜기, 찾을 글자 넣기, 미리 만든 미션 고르기. */
public class MainActivity extends Activity {

    // 네이버지도 화면을 읽은 뒤 단계 글자를 맞춘다. 처음 값은 추측이라 고칠 수 있다.
    // 실제 갤럭시 네이버지도 화면에서 읽은 글자로 맞춤 (2026-11-23)
    // 목표: 지금 위치 → 서울역, 대중교통 경로로 안내 시작. 버튼 글자는 직접 끝까지 해 보며 확인함.
    static final String NAVER_ROUTE = "{\"title\":\"서울역 가는 길 찾기\",\"steps\":["
            + "{\"k\":[\"길찾기\"],\"say\":\"오른쪽 위 '길찾기'를 눌러 주세요\"},"
            + "{\"k\":[\"도착지 입력\"],\"say\":\"'도착지 입력'을 눌러 주세요\"},"
            + "{\"k\":[],\"say\":\"'서울역'이라고 적어 주세요\"},"
            + "{\"k\":[\"KTX정차역\"],\"say\":\"목록에서 '서울역 (고속철도)'를 눌러 주세요\"},"
            + "{\"k\":[\"대중교통\"],\"say\":\"버스·지하철로 가려면 왼쪽 버스 그림을 눌러 주세요\"},"
            + "{\"k\":[\"바로 안내시작\"],\"say\":\"맨 위 경로가 가장 빨라요. '바로 안내시작'을 눌러 주세요\"}"
            + "]}";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        col.setPadding(p, p, p, p);

        TextView title = text("화면 길잡이 (시제품)", 26, true);
        TextView info = text("다른 앱에서 다음에 누를 곳을 빨간 테두리로 짚어 줘요.\n"
                + "화면을 읽기만 하고, 대신 누르지는 않아요.", 17, false);

        Button a11y = button("1. 접근성에서 '화면 길잡이' 켜기");
        a11y.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        Button mic = button("1-B. 마이크 허용 (받아쓰기 시험)");
        mic.setOnClickListener(v -> requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 7));

        EditText word = new EditText(this);
        word.setHint("찾을 글자 (예: 길찾기)");
        word.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);

        Button find = button("2-A. 이 글자 짚어 주기");
        find.setOnClickListener(v -> {
            String w = word.getText().toString().trim();
            if (w.isEmpty()) return;
            Mission.save(this, Mission.singleJson(w));
            send(GuideService.ACT_FIND, "q", w);
            toast("이제 다른 앱을 열어 보세요");
        });

        Button naver = button("2-B. 네이버지도 길찾기 연습");
        naver.setOnClickListener(v -> {
            Mission.save(this, NAVER_ROUTE);
            send(GuideService.ACT_SET, "json", NAVER_ROUTE);
            toast("네이버지도를 열어 보세요");
        });

        Button stop = button("멈추기");
        stop.setOnClickListener(v -> {
            Mission.clear(this);
            send(GuideService.ACT_STOP, null, null);
            toast("안내를 멈췄어요");
        });

        col.addView(title);
        col.addView(info);
        col.addView(a11y);
        col.addView(mic);
        col.addView(word);
        col.addView(find);
        col.addView(naver);
        col.addView(stop);
        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);
    }

    private void send(String action, String key, String val) {
        Intent i = new Intent(action).setPackage(getPackageName());
        if (key != null) i.putExtra(key, val);
        sendBroadcast(i);
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(Color.rgb(25, 25, 25));
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setPadding(0, 0, 0, dp(14));
        return t;
    }

    private Button button(String s) {
        Button btn = new Button(this);
        btn.setText(s);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        btn.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
        lp.topMargin = dp(10);
        btn.setLayoutParams(lp);
        return btn;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
