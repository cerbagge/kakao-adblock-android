package com.cerbagge.kakaoadblock;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;

/** Dns.java 단위 테스트 (PC의 JDK로 실행). 실패 시 예외로 종료한다. */
public class DnsTest {
    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        System.out.println("PASS  " + what);
    }

    /** 10.111.222.1:40000 -> 10.111.222.2:53 로 가는 A 질의 패킷(EDNS OPT 포함)을 만든다. */
    static byte[] queryPacket(String name) {
        java.io.ByteArrayOutputStream d = new java.io.ByteArrayOutputStream();
        d.write(0x12); d.write(0x34); d.write(0x01); d.write(0x00); // id, RD=1
        d.write(0); d.write(1); d.write(0); d.write(0); d.write(0); d.write(0); d.write(0); d.write(1); // QD=1, AR=1
        for (String l : name.split("\\.")) { d.write(l.length()); d.write(l.getBytes(), 0, l.length()); }
        d.write(0); d.write(0); d.write(1); d.write(0); d.write(1);          // QTYPE=A, QCLASS=IN
        byte[] opt = {0, 0, 41, 0x10, 0, 0, 0, 0, 0, 0, 0};                  // EDNS OPT
        d.write(opt, 0, opt.length);
        byte[] dns = d.toByteArray();
        byte[] p = new byte[28 + dns.length];
        p[0] = 0x45; p[2] = (byte) (p.length >> 8); p[3] = (byte) p.length; p[8] = 64; p[9] = 17;
        byte[] src = {10, 111, (byte) 222, 1}, dst = {10, 111, (byte) 222, 2};
        System.arraycopy(src, 0, p, 12, 4); System.arraycopy(dst, 0, p, 16, 4);
        p[20] = (byte) (40000 >> 8); p[21] = (byte) 40000; p[22] = 0; p[23] = 53;
        p[24] = (byte) ((8 + dns.length) >> 8); p[25] = (byte) (8 + dns.length);
        System.arraycopy(dns, 0, p, 28, dns.length);
        return p;
    }

    static boolean ipChecksumOk(byte[] p) {
        int sum = 0;
        for (int i = 0; i < 20; i += 2) sum += Dns.u16(p, i);
        while ((sum >> 16) != 0) sum = (sum & 0xffff) + (sum >> 16);
        return sum == 0xffff;
    }

    public static void main(String[] args) throws Exception {
        String[] bl = Dns.DEFAULT_BLOCKLIST;
        check(Dns.isBlocked("display.ad.daum.net", bl), "display.ad.daum.net 차단");
        check(Dns.isBlocked("st.display.ad.daum.net", bl), "st.display.ad.daum.net 차단");
        check(Dns.isBlocked("analytics.ad.daum.net", bl), "analytics.ad.daum.net 차단");
        check(Dns.isBlocked("stat.tiara.daum.net", bl), "stat.tiara.daum.net 차단");
        check(Dns.isBlocked("survey-ad.kakao.com", bl), "survey-ad.kakao.com 차단");
        check(!Dns.isBlocked("read.daum.net", bl), "read.daum.net 은 허용 (접미사 오탐 없음)");
        check(!Dns.isBlocked("talk.kakao.com", bl), "talk.kakao.com 허용");
        check(!Dns.isBlocked("t1.kakaocdn.net", bl), "t1.kakaocdn.net 허용");

        byte[] q = queryPacket("Display.Ad.Daum.net");
        int off = Dns.dnsOffset(q, q.length);
        check(off == 28, "DNS 페이로드 위치 계산");
        byte[] dns = Arrays.copyOfRange(q, off, q.length);
        check("display.ad.daum.net".equals(Dns.qname(dns)), "질의 이름 추출 (소문자화)");

        byte[] nx = Dns.nxdomain(dns);
        check(nx[0] == 0x12 && nx[1] == 0x34, "NXDOMAIN 응답 ID 유지");
        check((nx[2] & 0x80) != 0 && (nx[2] & 0x01) != 0, "QR=1, RD 유지");
        check((nx[3] & 0x0f) == 3, "RCODE=3 (NXDOMAIN)");
        check(Dns.u16(nx, 10) == 0 && nx.length == 12 + 21 + 4, "추가 섹션 제거, 질문 섹션만 남김");

        byte[] r = Dns.reply(q, nx);
        check(ipChecksumOk(r), "응답 IP 헤더 체크섬");
        check(r[12] == 10 && (r[15] & 0xff) == 2 && (r[19] & 0xff) == 1, "출발지/목적지 IP 뒤집기");
        check(Dns.u16(r, 20) == 53 && Dns.u16(r, 22) == 40000, "출발지/목적지 포트 뒤집기");
        check(Dns.u16(r, 2) == r.length && Dns.u16(r, 24) == r.length - 20, "IP/UDP 길이 필드");

        check(Dns.dnsOffset(new byte[]{0x60, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, 20) == -1, "IPv6 패킷 무시");

        // 허용 도메인은 실제 DNS 서버로 전달했을 때 정상 응답이 돌아오는지 확인
        byte[] q2 = queryPacket("talk.kakao.com");
        byte[] dns2 = Arrays.copyOfRange(q2, 28, q2.length);
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(3000);
            s.send(new DatagramPacket(dns2, dns2.length, InetAddress.getByName("1.1.1.1"), 53));
            byte[] buf = new byte[4096];
            DatagramPacket resp = new DatagramPacket(buf, buf.length);
            try {
                s.receive(resp);
            } catch (java.net.SocketTimeoutException e) {
                System.out.println("SKIP  외부 DNS 응답 없음 (PC 방화벽이 java.exe 의 UDP 를 막는 경우)");
                System.out.println("ALL PASS (네트워크 테스트 제외)");
                return;
            }
            byte[] rr = Dns.reply(q2, Arrays.copyOf(buf, resp.getLength()));
            check((buf[3] & 0x0f) == 0 && Dns.u16(buf, 6) > 0, "talk.kakao.com 전달 시 정상 응답 (ANCOUNT=" + Dns.u16(buf, 6) + ")");
            check(ipChecksumOk(rr), "전달 응답 패킷 체크섬");
        }
        System.out.println("ALL PASS");
    }
}
