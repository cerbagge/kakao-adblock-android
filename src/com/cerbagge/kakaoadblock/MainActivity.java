package com.cerbagge.kakaoadblock;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.VpnService;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** 시작/중지, 차단 목록 편집, 최근 DNS 기록을 보여주는 단일 화면 (레이아웃 XML 없이 코드로 구성). */
public class MainActivity extends Activity {
    private static final int REQ_VPN = 1;
    private TextView status;
    private Button toggle;
    private EditText listEdit;
    private TextView logView;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setTextSize(18);
        root.addView(status);

        toggle = new Button(this);
        toggle.setOnClickListener(v -> {
            if (AdBlockVpnService.running) stopVpn(); else startVpn();
        });
        root.addView(toggle);

        TextView hint = new TextView(this);
        hint.setText("켠 뒤에는 카카오톡을 완전히 종료했다가 다시 실행해야 이미 연결된 광고 서버가 끊깁니다.\n"
                + "부팅 후 자동 실행: 설정 → 연결 → 기타 연결 설정 → VPN → 이 앱의 톱니바퀴 → '상시 접속 VPN'.");
        root.addView(hint);

        TextView listTitle = new TextView(this);
        listTitle.setText("\n차단 도메인 (한 줄에 하나, 하위 도메인 포함)");
        listTitle.setTypeface(null, Typeface.BOLD);
        root.addView(listTitle);

        listEdit = new EditText(this);
        listEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        listEdit.setGravity(Gravity.TOP);
        listEdit.setMinLines(4);
        listEdit.setText(String.join("\n", AdBlockVpnService.loadBlocklist(this)));
        root.addView(listEdit);

        LinearLayout row = new LinearLayout(this);
        Button save = new Button(this);
        save.setText("목록 저장");
        save.setOnClickListener(v -> saveList(listEdit.getText().toString()));
        Button reset = new Button(this);
        reset.setText("기본값");
        reset.setOnClickListener(v -> {
            listEdit.setText(String.join("\n", Dns.DEFAULT_BLOCKLIST));
            saveList(listEdit.getText().toString());
        });
        row.addView(save);
        row.addView(reset);
        root.addView(row);

        LinearLayout logRow = new LinearLayout(this);
        TextView logTitle = new TextView(this);
        logTitle.setText("\n카카오톡 DNS 기록 (최신순)");
        logTitle.setTypeface(null, Typeface.BOLD);
        Button refresh = new Button(this);
        refresh.setText("새로고침");
        refresh.setOnClickListener(v -> refresh());
        logRow.addView(logTitle, new LinearLayout.LayoutParams(0, -2, 1));
        logRow.addView(refresh);
        root.addView(logRow);

        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(12);
        logView.setTextIsSelectable(true);
        root.addView(logView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean on = AdBlockVpnService.running;
        status.setText(on ? "● 차단 중" : "○ 꺼짐");
        toggle.setText(on ? "중지" : "시작");
        logView.setText(AdBlockVpnService.dumpLog());
    }

    private void startVpn() {
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) startActivityForResult(prepare, REQ_VPN);
        else onActivityResult(REQ_VPN, RESULT_OK, null);
    }

    private void stopVpn() {
        startService(new Intent(this, AdBlockVpnService.class).setAction(AdBlockVpnService.ACTION_STOP));
        status.postDelayed(this::refresh, 300);
    }

    private void saveList(String text) {
        getSharedPreferences("prefs", MODE_PRIVATE).edit().putString("blocklist", text).apply();
        if (AdBlockVpnService.running) startService(new Intent(this, AdBlockVpnService.class)); // 목록만 다시 읽음
        Toast.makeText(this, "저장했습니다", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        if (req == REQ_VPN && result == RESULT_OK) {
            startService(new Intent(this, AdBlockVpnService.class));
            status.postDelayed(this::refresh, 500);
        }
    }
}
