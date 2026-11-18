package com.cerbagge.kakaoadblock;

/** IPv4/UDP/DNS 패킷을 다루는 순수 함수 모음 (Android API 의존 없음, PC에서도 테스트 가능). */
final class Dns {
    private Dns() {}

    static final String[] DEFAULT_BLOCKLIST = {
        "ad.daum.net",          // display., st.display., analytics., bc., wat., info. 포함
        "survey-ad.kakao.com",
        "tiara.kakao.com",      // 광고 ID 추적 쿠키
        "tiara.daum.net",       // stat.tiara.daum.net 포함
    };

    /** 정확히 같거나 하위 도메인이면 차단 대상이다. */
    static boolean isBlocked(String name, String[] list) {
        for (String d : list) {
            if (name.equals(d) || name.endsWith("." + d)) return true;
        }
        return false;
    }

    static int u16(byte[] b, int off) {
        return ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
    }

    /** IPv4 UDP 목적지 포트 53 패킷이면 DNS 페이로드 시작 위치, 아니면 -1. */
    static int dnsOffset(byte[] pkt, int len) {
        if (len < 20 || (pkt[0] >> 4 & 0xf) != 4) return -1;
        int ihl = (pkt[0] & 0xf) * 4;
        if (pkt[9] != 17 || len < ihl + 8 + 12) return -1;
        if (u16(pkt, ihl + 2) != 53) return -1;
        return ihl + 8;
    }

    /** 질의 이름의 끝(QTYPE 시작) 위치. 형식이 잘못되면 -1. */
    static int qnameEnd(byte[] dns) {
        int p = 12;
        while (p < dns.length) {
            int l = dns[p] & 0xff;
            if (l == 0) return p + 1;
            if ((l & 0xc0) != 0) return -1; // 질의에는 압축 포인터가 오지 않는다
            p += 1 + l;
        }
        return -1;
    }

    /** 첫 번째 질의 이름(소문자). 형식이 잘못되면 null. */
    static String qname(byte[] dns) {
        if (dns.length < 12 || u16(dns, 4) < 1 || qnameEnd(dns) < 0) return null;
        StringBuilder sb = new StringBuilder();
        int p = 12;
        while ((dns[p] & 0xff) != 0) {
            int l = dns[p] & 0xff;
            if (sb.length() > 0) sb.append('.');
            for (int i = 1; i <= l; i++) sb.append((char) Character.toLowerCase(dns[p + i] & 0xff));
            p += 1 + l;
        }
        return sb.toString();
    }

    /** 질의를 그대로 되돌려 NXDOMAIN 응답을 만든다 (질문 섹션만 남김). */
    static byte[] nxdomain(byte[] dns) {
        int end = qnameEnd(dns) + 4;
        byte[] r = java.util.Arrays.copyOf(dns, Math.min(end, dns.length));
        r[2] = (byte) (0x80 | (dns[2] & 0x79)); // QR=1, opcode·RD 유지
        r[3] = (byte) 0x83;                     // RA=1, RCODE=3 (NXDOMAIN)
        r[4] = 0; r[5] = 1;                     // QDCOUNT=1
        for (int i = 6; i < 12; i++) r[i] = 0;  // AN/NS/AR = 0
        return r;
    }

    /** 원래 질의 패킷의 출발지·목적지를 뒤집어 DNS 응답을 담은 IPv4/UDP 패킷을 만든다. */
    static byte[] reply(byte[] query, byte[] payload) {
        int ihl = (query[0] & 0xf) * 4;
        int total = 28 + payload.length;
        byte[] p = new byte[total];
        p[0] = 0x45;
        p[2] = (byte) (total >> 8); p[3] = (byte) total;
        p[6] = 0x40;                 // DF
        p[8] = 64;                   // TTL
        p[9] = 17;                   // UDP
        System.arraycopy(query, 16, p, 12, 4); // 출발지 = 원래 목적지(가상 DNS)
        System.arraycopy(query, 12, p, 16, 4); // 목적지 = 원래 출발지
        int sum = 0;
        for (int i = 0; i < 20; i += 2) sum += u16(p, i);
        while ((sum >> 16) != 0) sum = (sum & 0xffff) + (sum >> 16);
        sum = ~sum & 0xffff;
        p[10] = (byte) (sum >> 8); p[11] = (byte) sum;
        p[20] = query[ihl + 2]; p[21] = query[ihl + 3]; // 출발지 포트 = 53
        p[22] = query[ihl];     p[23] = query[ihl + 1]; // 목적지 포트 = 원래 출발지 포트
        int ulen = 8 + payload.length;
        p[24] = (byte) (ulen >> 8); p[25] = (byte) ulen; // UDP 체크섬 0 (IPv4에서 허용)
        System.arraycopy(payload, 0, p, 28, payload.length);
        return p;
    }
}
