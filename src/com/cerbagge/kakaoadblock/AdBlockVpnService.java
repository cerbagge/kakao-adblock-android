package com.cerbagge.kakaoadblock;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 카카오톡 앱에만 적용되는 로컬 VPN.
 * 가상 DNS 주소(/32)로 가는 DNS 요청만 받아서, 광고 도메인은 NXDOMAIN 으로 응답하고 나머지는 외부 DNS 로 전달한다.
 * 채팅·사진 등 일반 데이터는 VPN 을 거치지 않는다.
 */
public class AdBlockVpnService extends VpnService implements Runnable {
    static final String KAKAO = "com.kakao.talk";
    static final String ACTION_STOP = "stop";
    private static final String VPN_ADDR = "10.111.222.1";
    private static final String DNS_ADDR = "10.111.222.2";
    private static final String[] UPSTREAM = {"1.1.1.1", "8.8.8.8"};
    private static final int LOG_MAX = 300;

    static volatile boolean running;
    private static final ArrayDeque<String> LOG = new ArrayDeque<>();

    private ParcelFileDescriptor tun;
    private FileOutputStream out;
    private Thread thread;
    private ExecutorService pool;
    private volatile String[] blocklist = Dns.DEFAULT_BLOCKLIST;

    static String[] loadBlocklist(Context c) {
        String saved = c.getSharedPreferences("prefs", MODE_PRIVATE).getString("blocklist", null);
        if (saved == null) return Dns.DEFAULT_BLOCKLIST;
        ArrayList<String> list = new ArrayList<>();
        for (String line : saved.split("\n")) {
            String d = line.trim().toLowerCase(Locale.ROOT);
            if (!d.isEmpty() && !d.startsWith("#")) list.add(d);
        }
        return list.toArray(new String[0]);
    }

    static void log(String msg) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
        synchronized (LOG) {
            if (LOG.size() >= LOG_MAX) LOG.removeFirst();
            LOG.addLast(t + "  " + msg);
        }
    }

    static String dumpLog() {
        StringBuilder sb = new StringBuilder();
        synchronized (LOG) {
            for (java.util.Iterator<String> it = LOG.descendingIterator(); it.hasNext(); ) {
                sb.append(it.next()).append('\n');
            }
        }
        return sb.toString();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopVpn();
            stopSelf();
            return START_NOT_STICKY;
        }
        blocklist = loadBlocklist(this); // 실행 중이면 차단 목록만 다시 읽는다
        if (tun == null) startVpn();
        return START_STICKY;
    }

    private void startVpn() {
        Builder b = new Builder()
                .setSession("카카오 광고 차단")
                .addAddress(VPN_ADDR, 32)
                .addDnsServer(DNS_ADDR)
                .addRoute(DNS_ADDR, 32)
                .setBlocking(true);
        try {
            b.addAllowedApplication(KAKAO);
        } catch (PackageManager.NameNotFoundException e) {
            log("카카오톡이 설치되어 있지 않습니다");
            stopSelf();
            return;
        }
        tun = b.establish();
        if (tun == null) { // VPN 권한이 없음
            stopSelf();
            return;
        }
        out = new FileOutputStream(tun.getFileDescriptor());
        pool = Executors.newFixedThreadPool(2);
        running = true;
        thread = new Thread(this, "dns-filter");
        thread.start();
        log("시작 (차단 도메인 " + blocklist.length + "개)");
    }

    private void stopVpn() {
        running = false;
        if (thread != null) thread.interrupt();
        if (pool != null) pool.shutdownNow();
        try {
            if (tun != null) tun.close(); // 막혀 있는 read() 를 깨운다
        } catch (IOException ignored) {
        }
        tun = null;
        thread = null;
        pool = null;
        log("중지");
    }

    @Override
    public void run() {
        byte[] buf = new byte[32767];
        try (FileInputStream in = new FileInputStream(tun.getFileDescriptor())) {
            while (running) {
                int n = in.read(buf); // 패킷이 올 때까지 블로킹 (대기 중 CPU 사용 없음)
                if (n > 0) handle(Arrays.copyOf(buf, n));
            }
        } catch (IOException ignored) {
            // tun 을 닫으면 여기로 빠져나온다
        }
    }

    private void handle(byte[] pkt) {
        int off = Dns.dnsOffset(pkt, pkt.length);
        if (off < 0) return;
        byte[] dns = Arrays.copyOfRange(pkt, off, pkt.length);
        String name = Dns.qname(dns);
        if (name == null) return;
        if (Dns.isBlocked(name, blocklist)) {
            log("차단  " + name);
            write(Dns.reply(pkt, Dns.nxdomain(dns)));
        } else {
            log("허용  " + name);
            ExecutorService p = pool;
            if (p != null) p.execute(() -> forward(pkt, dns));
        }
    }

    private void forward(byte[] pkt, byte[] dns) {
        try (DatagramSocket s = new DatagramSocket()) {
            protect(s);
            s.setSoTimeout(3000);
            byte[] resp = new byte[4096];
            for (String up : UPSTREAM) {
                try {
                    s.send(new DatagramPacket(dns, dns.length, InetAddress.getByName(up), 53));
                    DatagramPacket r = new DatagramPacket(resp, resp.length);
                    s.receive(r);
                    write(Dns.reply(pkt, Arrays.copyOf(resp, r.getLength())));
                    return;
                } catch (SocketTimeoutException ignored) {
                    // 다음 DNS 로 재시도
                }
            }
        } catch (IOException ignored) {
        }
    }

    private synchronized void write(byte[] p) {
        try {
            if (out != null && running) out.write(p);
        } catch (IOException ignored) {
        }
    }

    @Override
    public void onRevoke() { // 다른 VPN 이 켜지거나 사용자가 VPN 을 끈 경우
        stopVpn();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }
}
