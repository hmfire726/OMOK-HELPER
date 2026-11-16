package com.hmfire.omokhelper.core;

/** 게임 규칙 */
public enum Rule {
    /** 금수 없음, 5목 이상이면 승리 */
    FREESTYLE("자유룰"),
    /** 흑만 3-3, 4-4, 6목 이상 금지. 백은 6목 이상도 승리 */
    RENJU("렌주룰");

    public final String label;

    Rule(String label) {
        this.label = label;
    }
}
