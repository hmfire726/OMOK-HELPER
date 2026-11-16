package com.hmfire.omokhelper.core;

/** 탐색 시간 제한 + 취소 (엔진과 위협 탐색이 함께 쓴다) */
final class Clock {
    volatile boolean cancelled;
    long deadline;
    boolean stopped;
    long nodes;

    void start(long ms) {
        deadline = System.currentTimeMillis() + Math.max(1, ms);
        stopped = false;
    }

    void until(long absoluteMs) {
        deadline = absoluteMs;
        stopped = false;
    }

    long remaining() {
        return deadline - System.currentTimeMillis();
    }

    /** 노드 하나를 셀 때마다 부르고, 시간이 다 됐으면 true */
    boolean tick() {
        if ((++nodes & 255) == 0 && (cancelled || System.currentTimeMillis() >= deadline)) stopped = true;
        return stopped;
    }
}
