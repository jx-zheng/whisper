package com.jxzheng.whisper.ops;

/**
 * Progress and cancellation for a hide or reveal that runs off the UI thread.
 */
public interface Progress {

    Progress NONE = new Progress() {
        @Override
        public void stage(String name) {
        }

        @Override
        public boolean cancelled() {
            return false;
        }
    };

    void stage(String name);

    boolean cancelled();
}
