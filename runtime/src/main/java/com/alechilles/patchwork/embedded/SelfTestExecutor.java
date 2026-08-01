package com.alechilles.patchwork.embedded;

import com.alechilles.patchwork.selftest.PatchworkSelfTestPack;
import com.alechilles.patchwork.selftest.PatchworkSelfTestResult;

/** Narrow administration seam for running one isolated self-test. */
@FunctionalInterface
interface SelfTestExecutor {
    PatchworkSelfTestResult run(PatchworkSelfTestPack pack);
    /** Requests prompt cancellation when elected administration ownership is fenced. */
    default void cancel() { }
}
