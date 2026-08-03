package com.bedwarsbot.control;

public interface InputSink {
    void apply(InputFrame permittedFrame);

    void releaseAll();

    InputFrame getActiveFrame();
}
