package com.edu.guide;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 미션 = 단계 목록. 단계마다 찾을 글자(여러 개)와 어르신께 보여 줄 말. */
public class Mission {

    public static class Step {
        public final List<String> keywords = new ArrayList<>();
        public String say;
    }

    public String title = "";
    public final List<Step> steps = new ArrayList<>();
    public int current = 0;

    static final String PREF = "guide";
    static final String KEY_JSON = "mission_json";
    static final String KEY_STEP = "mission_step";

    /**
     * JSON 형식:
     * {"title":"길찾기 연습","steps":[{"k":["길찾기"],"say":"'길찾기'를 눌러 주세요"}, ...]}
     */
    public static Mission parse(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        Mission m = new Mission();
        m.title = o.optString("title", "");
        JSONArray arr = o.getJSONArray("steps");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.getJSONObject(i);
            Step st = new Step();
            JSONArray ks = s.getJSONArray("k");
            for (int j = 0; j < ks.length(); j++) st.keywords.add(ks.getString(j));
            // 글자가 없는 단계 = 정해진 버튼 없이 안내만 하는 단계 (예: 검색어 입력). 다음 단계 버튼이 보이면 넘어간다.
            String def = st.keywords.isEmpty() ? "다음 화면으로 넘어가 주세요" : "'" + st.keywords.get(0) + "'을(를) 눌러 주세요";
            st.say = s.optString("say", def);
            m.steps.add(st);
        }
        return m;
    }

    /** 글자 하나만 찾는 간단 모드 */
    public static String singleJson(String word) {
        try {
            JSONObject o = new JSONObject();
            o.put("title", "'" + word + "' 찾기");
            JSONArray steps = new JSONArray();
            JSONObject s = new JSONObject();
            s.put("k", new JSONArray().put(word));
            s.put("say", "'" + word + "'을(를) 눌러 주세요");
            steps.put(s);
            o.put("steps", steps);
            return o.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static void save(Context c, String json) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY_JSON, json).putInt(KEY_STEP, 0).apply();
    }

    public static void clear(Context c) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .remove(KEY_JSON).remove(KEY_STEP).apply();
    }

    public static Mission load(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String json = p.getString(KEY_JSON, null);
        if (json == null) return null;
        try {
            Mission m = parse(json);
            m.current = p.getInt(KEY_STEP, 0);
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    public static void saveStep(Context c, int step) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt(KEY_STEP, step).apply();
    }

    public Step currentStep() {
        return current < steps.size() ? steps.get(current) : null;
    }

    public Step nextStep() {
        return current + 1 < steps.size() ? steps.get(current + 1) : null;
    }

    public boolean done() {
        return current >= steps.size();
    }
}
